(ns maelstrom.net
  "A simulated, mutable unordered network, supporting randomized delivery,
  selective packet loss, and long-lasting partitions."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen.net :as net])
  (:import (java.util.concurrent PriorityBlockingQueue
                                 TimeUnit)))

(defn latency-compare [a b]
  (compare (:deadline a) (:deadline b)))

(defn net
  "Construct a new network. Takes a characteristic latency in ms, which is
  the longest packets will ordinarily be delayed.

      :queues      A map of receiver node ids to PriorityQueues
      :p-loss      The probability of any given message being lost
      :partitions  A map of receivers to collections of sources. If a
                   source/receiver pair exists, receiver will drop packets
                   from source."
  [latency log-send? log-recv?]
  (atom {:queues {}
         :log-send? log-send?
         :log-recv? log-recv?
         :latency latency
         :p-loss 0
         :partitions {}
         :next-client-id 0}))

(defn jepsen-adapter
  "A jepsen.net/Net which controls this network."
  [net]
  (reify net/Net
    (drop! [_ test src dest]
      (swap! net update-in [:partitions dest] conj src))

    (heal! [_ test]
      (swap! net assoc :partitions {}))

    (slow! [_ test]
      (swap! net update :latency * 10))

    (fast! [_ test]
      (swap! net update :latency / 10))

    (flaky! [_ test]
      (swap! net update :p-loss 0.5))))

(defn add-node!
  "Adds a node to the network."
  [net node-id]
  (assert (string? node-id) (str "Node id " (pr-str node-id)
                                 " must be a string"))
  (swap! net assoc-in [:queues node-id]
         (PriorityBlockingQueue. 11 latency-compare))
  net)

(defn remove-node!
  "Removes a node from the network."
  [net node-id]
  (swap! net update :queues dissoc node-id)
  net)

(defn ^PriorityBlockingQueue queue-for
  "Returns the queue for a particular recipient node."
  [net node]
  (if-let [q (-> net deref :queues (get node))]
    q
    (throw (RuntimeException.
             (str "No such node in network: " (pr-str node))))))

(defn validate-msg
  "Checks to make sure a message is well-formed and deliverable on the given
  network. Returns msg if legal, otherwise throws."
  [net m]
  (assert (map? m) (str "Expected message " (pr-str m) " to be a map"))
  (assert (:src m) (str "No source for message " (pr-str m)))
  (assert (:dest m) (str "No destination for message " (pr-str m)))
  (let [queues (get @net :queues)]
    (assert (get queues (:src m))
            (str "Invalid source for message " (pr-str m)))
    (assert (get queues (:dest m))
            (str "Invalid dest for message " (pr-str m))))
  m)

(defn send!
  "Sends a message into the network. Message must contain :src and :dest keys,
  both node IDs. Mutates and returns the network."
  [net message]
  (validate-msg net message)
  (let [{:keys [log-send? p-loss latency]} @net]
    (when log-send? (info :send (pr-str message)))
    (if (< (rand) p-loss)
      net ; whoops, lost ur packet
      (let [src  (:src message)
            dest (:dest message)
            q    (queue-for net dest)]
        (.put q {:deadline (+ (System/nanoTime) (long (rand (* latency 1e6))))
                 :message message})
        net))))

(defn recv!
  "Receive a message for the given node. Returns the message, and mutates the
  network. Returns `nil` if no message available in timeout-ms milliseconds."
  [net node timeout-ms]
  (when-let [envelope (.poll (queue-for net node)
                             timeout-ms TimeUnit/MILLISECONDS)]
    (let [{:keys [deadline message]} envelope
          dt (/ (- deadline (System/nanoTime)) 1e6)
          {:keys [log-recv? partitions]} @net]
      (when-not (some #{(:src message)} (get partitions node))
        ; No partition
        (do (when (pos? dt) (Thread/sleep dt))
            (when log-recv? (info :recv (pr-str message)))
            message)))))

;; Client ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sync-client!
  "Creates a new synchronous network client, which can only do one thing at a
  time: send a message, or wait for a response. Mutates network to register the
  client node."
  [net]
  (let [id (str "c" (:next-client-id (swap! net update :next-client-id inc)))]
    (add-node! net id)
    {:net     net
     :node-id id
     :next-msg-id (atom 0)
     :waiting-for (atom nil)}))

(defn sync-client-close!
  "Closes a sync client."
  [client]
  (reset! (:waiting-for client) :closed)
  (remove-node! (:net client) (:node-id client)))

(defn sync-client-send!
  "Sends a message over the given client. Fills in the message's :src and
  [:body :msg_id]"
  [client msg]
  (let [msg-id (swap! (:next-msg-id client) inc)
        net    (:net client)
        ok?    (compare-and-set! (:waiting-for client) nil msg-id)
        msg    (-> msg
                   (assoc :src (:node-id client))
                   (assoc-in [:body :msg_id] msg-id))]
    (when-not ok?
      (throw (IllegalStateException.
               "Can't send more than one message at a time!")))
    (send! net msg)))

(defn sync-client-recv!
  "Receives a message for the given client. Times out after timeout ms."
  [client timeout-ms]
  (let [target-msg-id @(:waiting-for client)
        net           (:net client)
        node-id       (:node-id client)
        deadline      (+ (System/nanoTime) (* timeout-ms 1e6))]
    (assert target-msg-id "This client isn't waiting for any response!")
    (try
      (loop []
        ; (info "Waiting for message" (pr-str target-msg-id) "for" node-id)
        (let [timeout (/ (- deadline (System/nanoTime)) 1e6)
              msg     (recv! net node-id timeout)]
          (cond ; Nothing in queue
                (nil? msg)
                (throw (RuntimeException. "timed out"))

                ; Reply to some other message we sent (e.g. that we gave up on)
                (not= target-msg-id (:in_reply_to (:body msg)))
                (recur)

                ; Hey it's for us!
                true
                msg)))
      (finally
        (when-not (compare-and-set! (:waiting-for client)
                                    target-msg-id
                                    nil)
          (throw (IllegalStateException.
                   "two concurrent calls of sync-client-recv!?")))))))

(defn sync-client-send-recv!
  "Sends a request and waits for a response."
  [client req-msg timeout]
  (sync-client-send! client req-msg)
  (sync-client-recv! client timeout))
