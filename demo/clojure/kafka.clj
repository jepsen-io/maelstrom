#!/usr/bin/env bb
(deps/add-deps
  '{:deps {slingshot/slingshot {:mvn/version "0.12.2"}}})

(ns maelstrom.demo.kafka
  "A kafka-like stream processing system which stores data entirely in a single
  node's memory."
  (:require [cheshire.core :as json]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (clojure.lang ExceptionInfo)
           (java.io BufferedReader)
           (java.util.concurrent CompletableFuture
                                 ExecutionException)
           (java.util.function Function)))

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

(defmacro then
  "Takes a CompletableFuture, a binding vector with a symbol for the value of
  that future and a body. Returns a CompletableFuture which evaluates body with
  the value bound."
  [fut [sym] & body]
  `(.thenApply ^CompletableFuture ~fut
               (reify Function
                 (apply [this# ~sym]
                   ~@body))))

(defmacro exceptionally
  "Takes a CompletableFuture, a binding vector with a symbol for an exception
  thrown by that future, and a body. Returns a CompletableFuture which
  evaluates body with that exception bound, if one is thrown."
  [fut [sym] & body]
  `(.exceptionally ^CompletableFuture ~fut
                   (reify Function
                     (apply [this# ~sym]
                       ~@body))))

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

(declare handle-assign!)
(declare handle-commit-offsets!)
(declare handle-poll!)
(declare handle-send!)

(defn handle-req!
  "Handles incoming request messages"
  [{:keys [body] :as req}]
  (case (:type body)
    "init"           (handle-init! req)
    "assign"         (handle-assign! req)
    "commit_offsets" (handle-commit-offsets! req)
    "poll"           (handle-poll! req)
    "send"           (handle-send! req)
    (throw (ex-info (str "Unknown request type " (:type body))
                    {:code 10}))))

(defn keywordize-keys
  "Converts a map's keys to keywords."
  [m]
  (update-keys m keyword))

(defn process-stdin!
  "Mainloop which handles messages from stdin"
  []
  (doseq [line (line-seq (BufferedReader. *in*))]
    (future
      (let [req (-> (json/parse-string line)
                    keywordize-keys
                    (update :body keywordize-keys))]
        (log (pr-str req))
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

;; Queues!

(def queues
  "An atom storing a map of keys to queues. Each queue is a vector of
  messages."
  (atom {}))

(def client-offsets
  "An atom storing a map of client-node-id -> key -> offset, where offset is
  the next offset in that key which that client will receive. The keys for a
  client tell us which queues the client is interested in."
  (atom {}))

(defn handle-assign!
  "Handles an assign request, binding a client to a set of keys."
  [{:keys [src body] :as req}]
  (let [client src]
    (swap! client-offsets
           (fn [client-offsets]
             (let [offsets (get client-offsets src)
                   offsets' (->> (:keys body)
                                 (map (fn [k]
                                        [k (get offsets k 0)]))
                                 (into (sorted-map)))]
               (assoc client-offsets client offsets'))))
    (log "New offsets" @client-offsets)
    (reply! req {:type "assign_ok"})))

(defn handle-commit-offsets!
  "Handles a commit_offsets request by advancing the offsets for a client to
  just past the given offsets."
  [{:keys [src body] :as req}]
  (let [client      src
        new-offsets (-> body :offsets (update-vals inc))]
    (swap! client-offsets
           (fn [client-offsets]
             (let [offsets  (get client-offsets client)
                   offsets' (merge-with max offsets new-offsets)]
               (assoc client-offsets client offsets'))))
    (log "New offsets" (pr-str @client-offsets))
    (reply! req {:type "commit_offsets_ok"})))

(defn handle-poll!
  "Handles a poll RPC request by finding some messages the client is assigned
  to but hasn't seen yet, and sending those back. Advances offsets."
  [{:keys [src body] :as req}]
  (let [client  src
        ; Get the offsets this client currently has
        offsets (get @client-offsets client)
        ; And the current state of the queues
        queues @queues
        ; What messages can we deliver?
        _ (log "Client offsets are" (pr-str offsets))
        msgs   (->> offsets
                    (map (fn [[k offset]]
                           ; What's unconsumed for this key?
                           (let [queue   (get queues k [])
                                 offset  (min offset (count queue))
                                 msgs    (subvec queue offset)
                                 offsets (iterate inc offset)]
                             (log "key" k "queue" queue "offset" offset "msgs" msgs)
                             [k (map vector offsets msgs)])))
                    (into (sorted-map)))]
    (reply! req {:type "poll_ok"
                 :msgs msgs})))

(defn handle-send!
  "Handles a send RPC request by adding a message to the appropriate queue."
  [{:keys [body] :as req}]
  (let [k      (:key body)
        msg    (:msg body)
        queues (swap! queues (fn [queues]
                               (let [queue  (get queues k [])
                                     queue' (conj queue msg)]
                                 (assoc queues k queue'))))]
    (log "New queues:" (pr-str queues))
    (reply! req {:type "send_ok", :offset (dec (count (get queues k)))})))

; Go!
(try
  (process-stdin!)
  (catch Throwable t
    (locking *err*
      (log (str "Fatal error: " t))
      (.printStackTrace t *err*))))
