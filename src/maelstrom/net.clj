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
