#!/usr/bin/env bb

(deps/add-deps
  '{:deps {slingshot/slingshot {:mvn/version "0.12.2"}}})

(ns maelstrom.demo.txn
  "A simple list-append transactional service backed by a single record in
  lin-kv."
  (:require [cheshire.core :as json]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (clojure.lang ExceptionInfo)
           (java.io BufferedReader)
           (java.util.concurrent CompletableFuture
                                 ExecutionException)))

(def node-id
  "Our own node ID"
  (promise))

(def node-ids
  "All node IDs in the cluster."
  (promise))

(def next-message-id
  "What's the next message ID we'll emit?"
  (atom 0))

(def rpcs
  "A map of message IDs to Futures which should be delivered with replies."
  (atom {}))

(defn log
  "Logs a message to stderr"
  [& args]
  (locking *err*
    (binding [*out* *err*]
      (apply println args))))

(defn send!
  "Sends a message on stdout."
  [dest body]
  (locking *out*
    (println (json/generate-string {:src @node-id, :dest dest, :body body}))))

(defn reply!
  "Replies to a request message with the given body."
  [req body]
  (send! (:src req) (assoc body :in_reply_to (:msg_id (:body req)))))

(defn rpc!
  "Sends an RPC request body to the given node, and returns a CompletableFuture
  of a response body."
  [dest body]
  (let [fut (CompletableFuture.)
        id  (swap! next-message-id inc)]
    (swap! rpcs assoc id fut)
    (send! dest (assoc body :msg_id id))
    fut))

(defn handle-reply!
  "Handles a reply to an RPC we issued."
  [{:keys [body] :as reply}]
  (when-let [fut (get @rpcs (:in_reply_to body))]
    (if (= "error" (:type body))
      (.completeExceptionally fut (ex-info (:text body)
                                           (dissoc body :type :text)))
      (.complete fut body)))
  (swap! rpcs dissoc (:in_reply_to body)))

(defn handle-init!
  "Handles an init message by saving the node ID and node IDS to our local
  state."
  [{:keys [body] :as req}]
  (deliver node-id (:node_id body))
  (deliver node-ids (:node_ids body))
  (log "I am node" @node-id)
  (reply! req {:type :init_ok}))

(declare handle-txn!)

(defn handle-req!
  "Handles incoming request messages"
  [{:keys [body] :as req}]
  (case (:type body)
    "init" (handle-init! req)
    "txn"  (handle-txn! req)
    (throw (ex-info (str "Unknown request type " (:type body))
                    {:code 10}))))

(defn process-stdin!
  "Mainloop which handles messages from stdin"
  []
  (doseq [line (line-seq (BufferedReader. *in*))]
    (future
      (let [req (json/parse-string line true)]
        (try
          (if (:in_reply_to (:body req))
            (handle-reply! req)
            (handle-req! req))
          (catch ExceptionInfo e
            ; Send these back to the client as error messages
            (reply! req (assoc (ex-data e)
                               :type :error
                               :text (ex-message e))))
          (catch Exception e
            (locking *err*
              (log "Error processing request" req)
              (log e))
            ; And send a general-purpose error message back to the client
            (reply! req {:type :error
                         :code 13
                         :text (ex-message e)})))))))

;; Txn workload stuff

(defn apply-txn
  "Takes a state map and a transaction, and returns a resulting state and
  completed transaction as a pair."
  [state txn]
  (reduce (fn [[state txn] [f k v :as op]]
            (case f
              "r"       [state (conj txn [f k (get state k)])]
              "append"  (let [vs (get state k [])]
                          [(assoc state k (conj vs v))
                           (conj txn op)])))
          [state []]
          txn))

(defn map->pairs
  "Convert a map to a flat list of [k v k v ...] pairs. Helpful for JSON
  serialization of maps."
  [m]
  (mapcat identity m))

(defn pairs->map
  "Converts a flat vector of [k v k v ...] pairs to a map. Helpful for JSON
  deserialization of maps."
  [pairs]
  (->> pairs (partition 2) (map vec) (into {})))

(def root-service
  "What service stores the root?"
  "lin-kv")

(def root-key
  "Where do we store the root in that service?"
  "root")

(defn read-service
  "Performs a blocking read RPC of the given service, returning the value for a
  key."
  [service k]
  (try+ (:value @(rpc! service {:type "read", :key k}))
       (catch [:code 20] _
         ; Not found
         nil)))

(defn cas-service!
  "Performs a blocking compare-and-set RPC against the given service, altering
  the value of `key` from `from` to `to`. Creates key if it doesn't exist."
  [service k from to]
  @(rpc! root-service
         {:type                 "cas"
          :key                  k
          :from                 from
          :to                   to
          :create_if_not_exists true}))

(defn handle-txn!
  "Handles an incoming txn request."
  [{:keys [body] :as req}]
  ; Fetch state
  (let [state         (read-service root-service root-key)
        ; Apply txn
        [state' txn'] (apply-txn (pairs->map state) (:txn body))]
    ; Save new state
    (try+ (cas-service! root-service root-key state (map->pairs state'))
          (catch [:code 22] _
            (throw+ {:code 30} nil "root altered")))
    (reply! req {:type "txn_ok", :txn txn'})))


; Go!
(try
  (process-stdin!)
  (catch Throwable t
    (locking *err*
      (log (str "Fatal error: " t))
      (.printStackTrace t *err*))))
