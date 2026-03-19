(ns maelstrom.workload.lin-kv-reconfig
  "A workload for a linearizable key-value store with dynamic cluster
  membership reconfiguration. Extends lin-kv by sending add_member and
  remove_member RPCs to change the cluster membership during the test.

  All nodes are spawned at startup, but only a subset are initial members.
  The init message includes an `initial_member_ids` field so nodes know
  whether they should participate in consensus from the start. Non-member
  nodes wait for an `add_member` command before joining.

  Maelstrom randomly picks nodes to add or remove without tracking actual
  membership state. The SUT is responsible for:
    - Treating add/remove of already-added/removed nodes as a no-op or error
    - Rejecting removes that would reduce the cluster below a viable size
    - Rejecting concurrent reconfigurations (e.g. Raft single-change rule)

  If a node is not a member, it returns error code 40 (:not-a-member) for
  KV operations, which is a definite failure. The linearizability checker
  treats these as operations that did not happen."
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [maelstrom.workload.lin-kv :as lin-kv]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [history :as history]
                    [independent :as independent]]
            [jepsen.tests.linearizable-register :as lin-reg]
            [schema.core :as s]))

;; New RPCs for membership changes

(c/defrpc add-member!
  "Adds a node to the cluster. Sent to any node, which should propose the
  membership change to the cluster (or forward to the leader). The target
  node should begin participating in consensus once the membership change
  commits. If the node is already a member, the server may treat this as a
  no-op success or return an error."
  {:type    (s/eq "add_member")
   :node_id s/Str}
  {:type    (s/eq "add_member_ok")})

(c/defrpc remove-member!
  "Removes a node from the cluster. Sent to any node, which should propose
  the membership change to the cluster (or forward to the leader). The
  target node should stop participating in consensus once the membership
  change commits. If the node is already not a member, the server may treat
  this as a no-op success or return an error."
  {:type    (s/eq "remove_member")
   :node_id s/Str}
  {:type    (s/eq "remove_member_ok")})

;; Client

(defn client
  "Constructs a client for the lin-kv-reconfig workload."
  ([net all-nodes]
   (client net all-nodes nil nil))
  ([net all-nodes conn node]
   (reify client/Client
     (open! [this test node]
       (client net all-nodes (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (let [timeout (max (* 10 (:mean (:latency test))) 1000)]
         (case (:f op)
           ;; KV operations - same as lin-kv
           (:read :write :cas)
           (c/with-errors op #{:read}
             (let [[k v] (:value op)]
               (case (:f op)
                 :read  (let [v (:value (lin-kv/read conn node {:key k} timeout))]
                          (assoc op
                                 :type  :ok
                                 :value (independent/tuple k v)))
                 :write (do (lin-kv/write! conn node {:key k, :value v} timeout)
                            (assoc op :type :ok))
                 :cas   (let [[v v'] v]
                          (lin-kv/cas! conn node {:key k, :from v, :to v'} timeout)
                          (assoc op :type :ok)))))

           ;; Membership changes - send to a random node
           :add-member
           (c/with-errors op #{}
             (let [target (rand-nth all-nodes)]
               (add-member! conn target {:node_id (:value op)} timeout)
               (assoc op :type :ok)))

           :remove-member
           (c/with-errors op #{}
             (let [target (rand-nth all-nodes)]
               (remove-member! conn target {:node_id (:value op)} timeout)
               (assoc op :type :ok))))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn))

     client/Reusable
     (reusable? [this test]
       true))))

;; Generator

(defn reconfig-op
  "Generates a random reconfiguration operation for one of the given nodes."
  [all-nodes]
  (let [target (rand-nth all-nodes)]
    (if (< (rand) 0.5)
      {:type :invoke, :f :add-member,    :value target}
      {:type :invoke, :f :remove-member, :value target})))

(defn mixed-generator
  "Wraps a KV operation generator, occasionally producing membership change
  operations with the given probability (reconfig-fraction, e.g. 0.05 = 5%)."
  [kv-gen all-nodes reconfig-fraction]
  (reify gen/Generator
    (op [this test ctx]
      (if (< (rand) reconfig-fraction)
        ;; Produce a reconfig op
        (let [op (gen/fill-in-op (reconfig-op all-nodes) ctx)]
          (if (= op :pending)
            :pending
            [op this]))
        ;; Produce a KV op
        (when-let [[op gen'] (gen/op kv-gen test ctx)]
          [op (mixed-generator gen' all-nodes reconfig-fraction)])))

    (update [this test ctx event]
      (if (#{:add-member :remove-member} (:f event))
        ;; Don't forward reconfig events to the KV generator
        this
        (mixed-generator (gen/update kv-gen test ctx event)
                         all-nodes reconfig-fraction)))))

;; Checker

(defn reconfig-checker
  "Wraps a checker, filtering out membership change operations from the
  history before delegating to the base checker."
  [base-checker]
  (reify checker/Checker
    (check [this test history opts]
      (let [kv-history (->> history
                            (remove #(#{:add-member :remove-member} (:f %)))
                            vec
                            history/history)]
        (checker/check base-checker test kv-history opts)))))

(defn reconfig-stats-checker
  "Wraps a stats checker so that :add-member and :remove-member ops don't
  cause the overall stats to be invalid when they have no successes."
  ([]
   (reconfig-stats-checker (checker/stats)))
  ([c]
   (reify checker/Checker
     (check [this test history opts]
       (let [res (checker/check c test history opts)]
         (if (every? :valid? (vals (dissoc (:by-f res)
                                           :add-member :remove-member)))
           (assoc res :valid? true)
           res))))))

;; Workload

(defn workload
  "Constructs a workload for a linearizable key-value store with dynamic
  membership reconfiguration.

      {:net     A Maelstrom network
       :nodes   All node IDs}

  The initial cluster starts with initial-member-count (default 3) nodes.
  Remaining nodes are non-members until they receive an add_member command."
  [opts]
  (let [all-nodes       (:nodes opts)
        node-count      (count all-nodes)
        initial-count   (min (get opts :initial-member-count 3)
                             node-count)
        initial-members (vec (take initial-count all-nodes))
        base            (lin-reg/test {:nodes all-nodes})]
    (assoc base
           :client        (client (:net opts) all-nodes)
           :generator     (mixed-generator (:generator base) all-nodes 0.05)
           :checker       (reconfig-checker (:checker base))
           :stats-checker (-> (checker/stats)
                              reconfig-stats-checker)
           :init-extra    {:initial_member_ids initial-members})))
