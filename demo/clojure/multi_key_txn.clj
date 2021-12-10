#!/usr/bin/env bb

(deps/add-deps
  '{:deps {slingshot/slingshot {:mvn/version "0.12.2"}}})

(ns maelstrom.demo.txn
  "A little more complex list-append transactional service backed by a single
  record in lin-kv, which points to immutable thunks stored in lww-kv."
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

(def root-service
  "What service stores the root?"
  "lin-kv")

(def root-key
  "Where do we store the root in that service?"
  "root")

(def thunk-service
  "Where do we store thunks?"
  "lww-kv")

(def next-thunk-number
  "What's the number of the next thunk we'll construct?"
  (atom 0))

(def thunk-cache
  "An atom containing a map of thunk IDs to values."
  (atom {}))

(defn new-thunk-id!
  "Generates a new globally unique thunk ID."
  []
  (str @node-id "-" (swap! next-thunk-number inc)))

(defn read-set
  "Returns the set of all keys we need to read in order to execute a
  transaction."
  [txn]
  (->> txn (map second) set))

(defn write-set
  "The set of all keys we need to write in order to commit a transaction."
  [txn]
  (->> txn (filter (comp #{"append"} first)) (map second) set))

(defn apply-txn
  "Takes a state map and a transaction, and returns a resulting state and
  completed transaction as a pair."
  [state txn]
  (reduce (fn [[state txn] [f k v :as op]]
            (case f
              "r"       [state (conj txn [f k (get state k)])]
              "append"  (let [vs (or (get state k) [])]
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

(defn read-service
  "Performs an async read RPC of the given service, returning the value for a
  key. Returns nil if not found."
  [service k]
  (let [res (rpc! service {:type "read", :key k})]
    (delay
      (try+ (:value @res)
            (catch [:code 20] _
              ; Not found
              nil)))))

(defn read-service-until-found
  "Like read-service, but retries not-found errors."
  [service k]
  (-> (rpc! service {:type "read", :key k})
      (then [res] (:value res))
      (exceptionally [err]
                     (if (= 20 (:code (ex-data (ex-cause err))))
                       @(read-service-until-found service k)
                       (throw err)))))

(defn write-service!
  "Performs an async write RPC to the given service, setting key to value."
  [service k v]
  (rpc! service {:type "write", :key k, :value v}))

(defn cas-service!
  "Performs an async compare-and-set RPC against the given service, altering
  the value of `key` from `from` to `to`. Creates key if it doesn't exist."
  [service k from to]
  (rpc! service
        {:type                 "cas"
         :key                  k
         :from                 from
         :to                   to
         :create_if_not_exists true}))

(defn load-thunks
  "Takes a root (a map of keys to thunk IDs) and a collection of keys. Returns
  a map of keys to actual values, looked up from thunks."
  [root ks]
  (->> ks
       (map root)
       (mapv (fn [thunk-id]
               (or (when (nil? thunk-id)
                     ; Key doesn't have a value; no need to load
                     (delay nil))
                   (when-let [v (get @thunk-cache thunk-id)]
                     ; Ah, good, we have this cached
                     (delay v))
                   ; Fall back to reading from thunk store
                   (-> (read-service-until-found thunk-service thunk-id)
                       ; When it arrives, cache it
                       (then [value]
                             (swap! thunk-cache assoc thunk-id value)
                             value)))))
       (map deref)
       (zipmap ks)))

(defn save-thunks!
  "Takes a state (a map of keys to values) and a collection of keys. Generates
  a fresh thunk ID for each of those keys, and writes the corresponding value
  to the thunk store. Returns a (partial) root: a map of keys to thunk IDs."
  [state ks]
  (->> ks
       ; Fire off saves in parallel
       (mapv (fn [k]
               (let [v        (get state k)
                     thunk-id (new-thunk-id!)]
                 ; Cache as a side effect
                 (swap! thunk-cache assoc thunk-id v)
                 [thunk-id
                  (write-service! thunk-service thunk-id v)])))
       ; Block for those saves, cache, and collect thunk IDs
       (map (fn [[thunk-id write-future]]
              @write-future
              thunk-id))
       ; Into a map of keys to thunk IDs
       (zipmap ks)))

(defn handle-txn!
  "Handles an incoming txn request."
  [{:keys [body] :as req}]
  (let [txn (:txn body)
        ; Fetch root
        root-pairs @(read-service root-service root-key)
        root (pairs->map root-pairs)
        ; And thunks
        state (load-thunks root (read-set txn))
        ; Apply txn to that state, generating a new state
        [state' txn'] (apply-txn state txn)
        ; Now write back new values.
        root' (merge root (save-thunks! state' (write-set txn)))]
    ; Save new root
    (try+ @(cas-service! root-service
                         root-key
                         root-pairs
                         (map->pairs root'))
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
