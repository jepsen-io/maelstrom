(ns maelstrom.workload.kafka
  "A simplified version of a Kafka-style stream processing system. Servers
  provide a set of append-only logs identified by string *keys*. Each integer
  *offset* in a log has one *message*. Offsets may be sparse: not every offset
  must contain a message.

  A client appends a message to a log by making a `send` RPC request with the
  key and value they want to append; the server responds with the offset it
  assigned for that particular message.

  To read a log, a client issues a `poll` RPC request with a map of keys to the
  offsets it wishes to read beginning at. The server returns a `poll_ok`
  response containing a map of keys to vectors of `[offset, message]` pairs,
  beginning at the requested offset for that key.

  Servers should maintain a *committed offset* for each key. Clients can
  request that this offset be advanced by making a `commit_offsets` RPC
  request, with a map of keys to the highest offsets which that client has
  processed. Clients can also fetch known-committed offsets for a given set of
  keys through a `fetch_committed_offsets` request.

  The checker for this workload detects a number of anomalies.

  If a client observes (e.g.) offset 10 of key `k1`, but *not* offset 5, and we
  know that offset 5 exists, we call that a *lost write*. If we know offset 11
  exists, but it is never observed in a poll, we call that write *unobserved*.
  There is no recency requirement: servers are free to acknowledge a sent
  message, but not return it in any polls for an arbitrarily long time.

  Ideally, we expect client offsets from both sends and polls to be strictly
  monotonically increasing, and to observe every message. If offsets go
  backwards--e.g. if a client observes offset 4 then 2, or 2 then 2--we call
  that *nonomonotonic*. It's an *internal nonmonotonic* error if offsets fail
  to increase in the course of a single transaction, or single poll. It's an
  *external nonmonotonic* error if offsets fail to increase *between* two
  transactions.

  If we skip over an offset that we know exists, we call that a *skip*. Like
  nonmonotonic errors, a skip error can be internal (in a single transaction or
  poll) or external (between two transactions).

  In order to prevent these anomalies, each server should track each client's
  offsets for each key. When a client issues an `assign` operation which
  assigns a new key, the client's offset for that key can change
  arbitrarily--most likely, it should be set to the committed offset for that
  key. Since we expect the offset to change on assign, external nonmonotonic
  and skip errors are not tracked across `assign` operations."
  (:require [clojure.tools.logging :refer [info warn]]
            [dom-top.core :refer [loopr]]
            [jepsen [client :as client]
                    [generator :as gen]]
            [jepsen.tests.kafka :as kafka]
            [maelstrom [client :as c]
                       [net :as net]]
            [schema.core :as s]))

(def Key     (s/named String "key"))
(def Offset  (s/named s/Int "offset"))
(def Message (s/named s/Any "msg"))

(def OffsetMessage
  "We frequently deal with [offset message] pairs."
  [(s/one Offset "offset") (s/one Message "msg")])

(c/defrpc send!
  "Sends a single message to a specific key. The server should assign a unique
  offset in the key for this message, and return a `send_ok` response with that
  offest."
  {:type   (s/eq "send")
   :key    Key
   :msg    Message}
  {:type   (s/eq "send_ok")
   :offset Offset})

(c/defrpc poll
  "Requests messages from specific keys. The client provides a map `offsets`,
  whose keys are the keys of distinct queues, and whose values are the
  corresponding offsets the server should send back first (if available). For
  instance, a client might request

      {\"type\": \"poll\",
       \"offsets\": {\"a\": 2}}

  This means that the client would like to see messages from key \"a\"
  beginning with offset 2. The server is free to respond with any number of
  contiguous messages from queue \"a\" so long as the first message's offset is
  2. Those messages are returned as a map of keys to arrays of [offset message]
  pairs. For example:

      {\"type\": \"poll_ok\",
       \"msgs\": {\"a\": [[2 9] [3 5] [4 15]]}}

  In queue \"a\", offset 2 has message 9, offset 3 has message 5, and offset 4
  has message 15. If no messages are available for a key, the server can omit
  that key from the response map altogether."
  {:type (s/eq "poll")
   :offsets {Key Offset}}
  {:type (s/eq "poll_ok")
   :msgs {Key [OffsetMessage]}})

(c/defrpc commit_offsets!
  "Informs the server that the client has successfully processed messages up to
  and including the given offset. For instance, if a client sends:

  ```edn
  {:type    \"commit_offsets\"
   :offsets {\"k1\" 2}}
  ```

  This means that on key `k1` offsets 0, 1, and 2 (if they exist; offsets may
  be sparse) have been processed, but offsets 3 and higher have not. To avoid
  lost writes, the servers should ensure that any fresh assign of key `k1`
  starts at offset 3 (or lower)."
  {:type    (s/eq "commit_offsets")
   :offsets {Key Offset}}
  {:type    (s/eq "commit_offsets_ok")})

(c/defrpc list_committed_offsets
  "Requests the latest committed offsets for the given array of keys. The
  server should respond with a map of keys to offsets. If a key does not exist,
  it can be omitted from the response map. Clients use this to figure out where
  to start consuming from a given key."
  {:type (s/eq "list_committed_offsets")
   :keys [Key]}
  {:type (s/eq "list_committed_offsets_ok")
   :offsets {Key Offset}})

(defn txn-offsets
  "Computes the highest polled offsets in a transaction, returning a map of
  keys to offsets."
  [txn]
  (loopr [offsets {}]
         [[_ keys->msgs]  (filter (comp #{:poll} first) txn)
          [k pairs]       keys->msgs
          [offset msg]    pairs]
         (recur (assoc offsets k (max (get offsets k 0) offset)))))

(defn apply-mop!
  "Takes a micro-op from a transaction--e.g. [:poll] or [:send k msg]--and
  applies it to the given connection, returning a completed micro-op. Advances
  local offsets with each poll."
  [conn node offsets [f :as mop]]
  (case f
    :poll (let [_    (info "Polling" @offsets)
                msgs (-> (poll conn node {:offsets @offsets})
                         :msgs)
                ; Advance local poll offsets
                new-offsets (update-vals msgs
                                         (fn [pairs]
                                           (->> pairs
                                                (map first)
                                                (reduce max -1)
                                                inc)))]
            (swap! offsets (partial merge-with max) new-offsets)
            [:poll msgs])

    :send (let [[_ k msg] mop
                offset    (:offset (send! conn node {:key k, :msg msg}))]
            [:send k [offset msg]])))

; Offsets is an atom with a map of assigned keys to the offset we want to read
; next.
(defrecord Client [net conn node offsets]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (c/open! net)
           :node node
           :offsets (atom {})))

  (setup! [_ test])

  (invoke! [_ test {:keys [f value] :as op}]
    (case f
      :assign (do (if (:seek-to-beginning? op)
                    ; We want to rewind to zero on everything
                    (reset! offsets (zipmap value (repeat 0)))
                    ; Fetch committed offsets from storage
                    (let [committed (list_committed_offsets
                                      conn node
                                      {:keys value})]
                      (swap! offsets
                             (fn [offsets]
                               (->> value
                                    (map (fn [k]
                                           [k (or (offsets k)
                                                  (committed k)
                                                  0)]))
                                    (into {}))))))
                  (assoc op :type :ok))

      :crash (assoc op :type :info)

      (:poll, :send) (do (assert (= 1 (count value)))
                         (let [mop' (apply-mop! conn node offsets (first value))
                               txn' [mop']
                               offsets (txn-offsets txn')]
                           (when (seq offsets)
                             ; And commit remotely
                             (info "Committing offsets" offsets)
                             (commit_offsets! conn node {:offsets offsets}))
                           (assoc op :type :ok, :value txn')))))

  (teardown! [_ test])

  (close! [_ test]
    (c/close! conn))

  client/Reusable
  (reusable? [this test]
    ; Tear down clients when we crash
    false))

(defn update-op-keys
  "Takes an operation and rewrites its poll/send/assign/subscribe keys using
  f."
  [op f]
  (case (:f op)
    (:assign, :subscribe)
    (assoc op :value (mapv f (:value op)))

    :crash op

    (:poll, :send, :txn)
    (assoc op :value (mapv (fn [[fun :as mop]]
                             (case fun
                               :poll (if-let [msgs (second mop)]
                                       [:poll (update-keys msgs f)]
                                       mop)
                               :send (let [[_ k v] mop]
                                       [:send (f k) v])))
                           (:value op)))))

(defrecord StringKeys [gen]
  gen/Generator
  (op [this test context]
    (when-let [[op gen'] (gen/op gen test context)]
      (if (= :pending op)
        [:pending this]
        ; Rewrite keys
        [(update-op-keys op str)
         (StringKeys. gen')])))

  ; When we receive updates, we need to rewrite those keys *back* into integers
  ; so that the key tracking does the right thing.
  (update [this test context {:keys [f value] :as op}]
    (let [event' (update-op-keys op parse-long)
          gen'   (gen/update gen test context event')]
    (StringKeys. gen'))))

(defn gen-string-keys
  "Wraps a Kafka generator, rewriting long keys to strings. We do this to keep
  users' lives simple--there's a nice JSON map type for string keys, and this
  way we don't force them to encode/decode kv array pairs."
  [gen]
  (StringKeys. gen))

(defn workload
  "Constructs a workload for Kafka tests, given options from the CLI test
  constructor. Options are:

      :net      A Maelstrom network"
  [opts]
  (let [workload (kafka/workload (assoc opts
                                        :txn?           false
                                        :crash-clients? true
                                        :sub-via        #{:assign}))
        gen       (->> (:generator workload)
                       gen-string-keys)
        final-gen (->> (:final-generator workload)
                       ; Strip out debug ops
                       (gen/filter (comp (complement #{:debug-topic-partitions})
                                         :f))
                       gen-string-keys
                       ; It's likely that users are going to lose data, and we
                       ; don't want to stall forever at the end of the test,
                       ; so we clip this to 10 seconds.
                       (gen/time-limit 10))]
    (assoc workload
           :client          (map->Client {:net     (:net opts)})
           :generator       gen
           :final-generator final-gen)))
