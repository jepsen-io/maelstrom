(ns maelstrom.workload.kafka
  "A simplified version of a Kafka-style stream processing system. Servers
  provide a set of append-only logs identified by string *keys*. Each integer
  *offset* in a log has one *message*. Offsets may be sparse: not every offset
  must contain a message.

  A client appends a message to a log by making a `send` RPC request with the
  keyn and value they want to append; the server responds with the offset it
  assigned for that particular message.

  To read a log, a client issues an `assign` RPC request with a set of keys
  it wants to receive messages from; the server is responsible for tracking
  that this particular client has assigned those keys. Note that this is a
  hybrid of Kafka's `assign` and `subscribe`: it makes the server responsible
  for tracking polling offsets, but does not have any concept of consumer
  groups.

  After having assigned, the client can issue a `poll` request. In response,
  the server sends a `poll_ok` RPC response, containing a map of keys to
  sequences of messages. We'll talk about exactly *which* messages in a moment.

  Since messages delivered in response to a poll might not actually be
  *processed* if a client crashes, the client needs to inform the server that
  messages have been successfully processed. To do this, it periodically sends
  a *commit_offsets* RPC, containing a map of keys to the offsets it has
  processed. Servers acknowledge this with a `commit_ok` response. The servers
  should collectively keep track of this committed offset, and use it to
  constrain which messages are delivered.

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

(def SendReq
  "When we send a message to a log, we write [\"send\" key value]."
  [(s/one (s/eq "send") "f") (s/one Key "k") (s/one Message "msg")])

(def SendRes
  "When we receive a response to a send operation, it should include the offset
  that that message was assigned, as an offset-message pair."
  [(s/one (s/eq "send") "f")
   (s/one Key "k")
   (s/one OffsetMessage "offset-msg")])

(def PollReq
  "When we make a txn request containing a poll operation, it's just
  [\"poll\"]."
  [(s/one (s/eq "poll") "f")])

(def PollRes
  "The response to a poll operation has a (logical) map of keys to vectors of
  [offset message] pairs."
  [(s/one (s/eq "poll") "f")
   (s/one {Key [OffsetMessage]} "msgs")])

(c/defrpc send!
  "Sends a single message to a specific key. The server should assign a unique
  offset in the key for this message, and return a `send_ok` response with that
  offest."
  {:type   (s/eq "send")
   :key    Key
   :msg    Message}
  {:type   (s/eq "send_ok")
   :offset Offset})

(c/defrpc assign!
  "Informs the server that this client wishes to receive messages from the
  given set of keys. The server should remember this mapping, and on subsequent
  polls, return messages from those keys."
  {:type (s/eq "assign")
   :keys [Key]}
  {:type (s/eq "assign_ok")})

(c/defrpc poll!
  "Requests some messages from whatever topics the client has currently
  assigned. The server should respond with a `poll_ok` response,
  containing a map of keys to vectors of [offest, message] pairs."
  {:type (s/eq "poll")}
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

(defn apply-mop!
  "Takes a micro-op from a transaction--e.g. [:poll] or [:send k msg]--and
  applies it to the given connection, returning a completed micro-op."
  [conn node [f :as mop]]
  (case f
    :poll (let [msgs (-> (poll! conn node {})
                         :msgs)]
                [:poll msgs])

    :send (let [[_ k msg] mop
                offset    (:offset (send! conn node {:key k, :msg msg}))]
            [:send k [offset msg]])))

(defn commit-txn-offsets!
  "Takes a completed transaction and commits any polled offsets in it."
  [conn node txn]
  (loopr [offsets {}]
         [[_ keys->msgs]  (filter (comp #{:poll} first) txn)
          [k pairs]       keys->msgs
          [offset msg]    pairs]
         (recur (assoc offsets k (max (get offsets k 0) offset)))
         (when (seq offsets)
           (info "Committing offsets" offsets)
           (commit_offsets! conn node {:offsets offsets}))))

(defrecord Client [net conn node]
  client/Client
  (open! [this test node]
    (assoc this
           :conn (c/open! net)
           :node node))

  (setup! [_ test])

  (invoke! [_ test {:keys [f value] :as op}]
    (case f
      :assign (do (assign! conn node {:keys value})
                  (assoc op :type :ok))

      :crash (assoc op :type :info)

      (:poll, :send) (do (assert (= 1 (count value)))
                         (let [mop' (apply-mop! conn node (first value))
                               txn' [mop']]
                           (commit-txn-offsets! conn node txn')
                           (assoc op :type :ok, :value txn')))))

  (teardown! [_ test])

  (close! [_ test]
    (c/close! conn))

  client/Reusable
  (reusable? [this test]
    ; Tear down clients when we crash
    false))

(defn gen-string-keys
  "Wraps a Kafka generator, rewriting keys to strings. We do this to keep
  users' lives simple--there's a nice JSON map type for string keys, and this
  way we don't force them to encode/decode kv array pairs."
  [gen]
  (gen/map (fn [{:keys [f value :as] :as op}]
             (case f
               (:assign, :subscribe)
               (assoc op :value (mapv str value))

               :crash op

               (:poll, :send, :txn)
               (assoc op :value (mapv (fn [[f :as mop]]
                                        (case f
                                          :poll mop
                                          :send (let [[f k v] mop]
                                                  [f (str k) v])))
                                      value))))
           gen))

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
           :client          (map->Client {:net (:net opts)})
           :generator       gen
           :final-generator final-gen)))
