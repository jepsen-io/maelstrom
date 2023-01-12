#!/usr/bin/env bb

(load-file (clojure.string/replace *file* #"/[^/]+$" "/node.clj"))

(ns maelstrom.demo.txn-rw-register-hat
  "A highly available, weakly consistent transaction system over read-write
  registers. Adapted from Bailis et al's Highly Available Transactions.

  Throughout this server we use the concept of a 'txn+': a transaction, a
  timestamp to execute it at, and a set of nodes that it still needs to be
  replicated to.

  Timestamps uniquely identify transactions."
  (:require [maelstrom.demo.node :as node]))

(defn lamport->timestamp
  "Takes a lamport clock and returns a globally unique timestamp: [lamport
  node-id]."
  [lamport]
  [lamport @node/node-id])

(def state
  "An atom of a map with:

    :lamport The current lamport clock
    :kv      A map of keys to {:ts timestamp, :value value} maps."
  (atom {:lamport 0, :kv {}}))

(def unreplicated-txns
  "A map of timestamps to txn+ maps that still need to be replicated to other
  nodes."
  (atom {}))

(defn apply-txn+
  "Takes a state and a txn+. If the txn+ does not currently have a timestamp,
  assigns one from the state and advances the state's Lamport clock. If the
  txn+ does have a timestamp, advances the state's Lamport clock to be higher.
  Applies the txn to the state at that timestamp. Returns a pair of the
  resulting state, and a completed txn+ with a timestamp assigned."
  [{:keys [kv lamport] :as state} {:keys [ts txn] :as txn+}]
  ;(node/log :apply-txn :state (node/pp-str state) :txn+ txn+)
  (let [[ts lamport'] (if ts
                        ; Use txn+ timestamp and advance our Lamport clock
                        [ts (max lamport (inc (first ts)))]
                        ; Intentionally bad version: don't advance lamport
                        ;[ts lamport]
                        ; Assign one
                        [(lamport->timestamp lamport) (inc lamport)])
        ; Zip through the txn, applying each mop
        [kv' txn'] (reduce (fn [[kv txn'] [f k v :as mop]]
                             (let [current-value (get kv k)]
                               (case f
                                 "r" [kv
                                      (conj txn' [f k (:value current-value)])]
                                 "w" [(if (and current-value
                                               (pos? (compare
                                                       (:ts current-value)
                                                       ts)))
                                        ; Leave in place
                                        kv
                                        ; Overwrite
                                        (assoc kv k {:ts ts, :value v}))
                                      (conj txn' mop)])))
                           [kv []]
                           txn)
        ; Construct new state
        state' (assoc state :kv kv' :lamport lamport')
        ; Construct txn+
        txn+' (assoc txn+ :ts ts, :txn txn')]
    ;(node/log :apply-txn' (node/pp-str state'))
    [state' txn+']))

(defn apply-txn+!
  "Applies a txn+ to the current state, mutating it. Returns a completed txn+'"
  [txn+]
  (let [txn-result (atom nil)]
    (swap! state (fn [state]
                   (let [[state' txn+'] (apply-txn+ state txn+)]
                     (reset! txn-result txn+')
                     state')))
    @txn-result))

(defn later-replicate!
  "Takes a txn+ (a map with a :ts and a :txn field), and enqueues it to be
  replicated to all other nodes."
  [txn+]
  (swap! unreplicated-txns assoc (:ts txn+)
         (assoc txn+ :nodes (node/other-node-ids))))

(defn replicate-step!
  "Finds unreplicated txns and sends them to another, randomly selected node."
  []
  (let [uts @unreplicated-txns]
    (when (seq uts)
      (let [[_ txn+] (first uts)
            node (first (:nodes txn+))
            ; Pick all txns for that node
            txn+s (filter (fn [txn+]
                            (contains? (:nodes txn+) node))
                          (vals uts))]
        (node/send! node {:type :replicate
                          :txns txn+s})))))

(defn start-replicate-thread!
  "Starts replicating txns in a new thread."
  []
  (future
    (try
      (while true
        (Thread/sleep 100)
        (replicate-step!))
      (catch Throwable t
        (node/log-ex t "Fatal error in replication thread!")
        (System/exit 2)))))

(node/defhandler txn
  "When a transaction arrives, apply it to the local state."
  [{:keys [body] :as req}]
  (let [txn  (:txn body)
        ; Apply txn locally, assigning a timestamp and performing reads.
        txn+ (apply-txn+! {:txn txn})]
    (later-replicate! txn+)
    ; Reply to client
    (node/reply! req {:type "txn_ok"
                      :txn (:txn txn+)})))

(node/defhandler replicate
  "When we get a replication message, apply each transaction locally, add it to
  our own unreplicated set if necessary, and reply with a replication ack."
  [{:keys [src body] :as req}]
  ;(node/log :replicate (pr-str body))
  (doseq [txn+ (:txns body)]
    ; Apply txn locally, for side effects
    (apply-txn+! txn+)
    ; Add it to our unreplicated set
    (let [nodes' (disj (set (:nodes txn+)) @node/node-id)]
      (when (seq nodes')
        (swap! unreplicated-txns assoc (:ts txn+)
               (assoc txn+ :nodes nodes')))))
  ; Broadcast an ack
  ;(node/log "send replicate ack to" (pr-str (node/other-node-ids)))
  (doseq [node (node/other-node-ids)]
    (node/send! node {:type :replicate_ack
                      :node @node/node-id
                      :tss  (map :ts (:txns body))})))

(node/defhandler replicate_ack
  "When a node acknowledges replication of timestamps, we can filter it out of
  the unreplicated message set."
  [{:keys [body] :as req}]
  ;(node/log :replicate_ack (pr-str body))
  (let [{:keys [node tss]} body]
    (swap! unreplicated-txns
           (fn [uts]
             ; For each txn timestamp, remove the node from its pending set
             (reduce (fn [uts ts]
                       (if-let [txn+ (get uts ts)]
                         (let [nodes' (disj (:nodes txn+) node)]
                           (if (seq nodes')
                             (assoc uts ts (assoc txn+ :nodes nodes'))
                             ; No one left to replicate to
                             (dissoc uts ts)))
                         ; txn already fully replicated
                         uts))
                     uts
                     tss))))
  ;(node/log :unreplicated (node/pp-str @unreplicated-txns))
  )

(start-replicate-thread!)
(node/start!)
