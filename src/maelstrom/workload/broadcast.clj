(ns maelstrom.workload.broadcast
  "A broadcast system. Essentially a test of eventually-consistent set
  addition, but also provides an initial `topology` message to the cluster with
  a set of neighbors for each node to use."
  (:refer-clojure :exclude [read])
  (:require [clojure.pprint :refer [pprint]]
            [clojure.tools.logging :refer [info warn]]
            [clojure.zip :as zip]
            [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [history :as h]]
            [knossos.op :as op]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(c/defrpc topology!
  "A topology message is sent at the start of the test, after initialization,
  and informs the node of an optional network topology to use for broadcast.
  The topology consists of a map of node IDs to lists of neighbor node IDs."
  {:type      (s/eq "topology")
   :topology  {net/NodeId [net/NodeId]}}
  {:type      (s/eq "topology_ok")})

(c/defrpc broadcast!
  "Sends a single message into the broadcast system, and requests that it be
  broadcast to everyone. Nodes respond with a simple acknowledgement message."
  {:type     (s/eq "broadcast")
   :message  s/Any}
  {:type     (s/eq "broadcast_ok")})

(c/defrpc read
  "Requests all messages present on a node."
  {:type      (s/eq "read")}
  {:type      (s/eq "read_ok")
   :messages  [s/Any]})

(defn grid-topology
  "Arranges nodes into a roughly-square grid topology, such that each node has
  at most 4 neighbors."
  [test]
  (let [nodes     (vec (:nodes test))
        n         (count nodes)
        side      (Math/ceil (Math/sqrt n))
        ; A function which takes node coordinates i, j and yields a node, or
        ; nil
        node (fn node [i j]
               ; j is the index within a slice of length `side`. i needs to be
               ; positive, but we might not have a full `side` slices, so we
               ; check i's upper bound later.
               (when (and (< -1 i) (< -1 j side))
                 (let [idx (+ (* i side) j)]
                   (when (< idx n)
                     ; Good, still in bounds.
                     (nth nodes idx)))))]
    (->> (for [i (range side), j (range side)]
           (when-let [n (node i j)]
             [n (remove nil? [(node (inc i) j)
                              (node (dec i) j)
                              (node i (inc j))
                              (node i (dec j))])]))
         (remove nil?)
         (into {}))))

(defn line-topology
  "All nodes are arranged in a single line."
  [test]
  (let [nodes (vec (:nodes test))
        n     (count nodes)]
    (if (< n 2)
      {}
      (->> nodes
           (partition 3 1)
           (reduce (fn [m [left this right]]
                     (assoc m this [left right]))
                   ; First, last
                   {(nodes 0)       [(nodes 1)]
                    (nodes (- n 1)) [(nodes (- n 2))]})))))

(defn total-topology
  "Every node is connected to every other node."
  [test]
  (let [nodes (:nodes test)]
    (->> nodes
         (map (fn [node]
                [node (remove #{node} nodes)]))
         (into {}))))

(defn tier-sizes
  "Gives the sizes for each tier of a tree with branch factor b: 1, b, b^2,
  ..."
  [b]
  (iterate (partial * b) 1))

(defn tiers
  "Takes a sequence of elements and chunks it into tiers for a b-wide tree, starting with the root."
  [b xs]
  (loop [xs     xs
         tiers  []
         sizes  (tier-sizes b)]
    (if-not (seq xs)
      ; Done!
      tiers
      (let [[size & sizes'] sizes]
        (recur (drop size xs)
               (conj tiers (take size xs))
               (next sizes))))))

(defn seq->tree
  "Converts a sequence of elements xs to a tree where each node is encoded as
  (x & children). Leaves are [x]. Maximum b children per node."
  [b xs]
  ; Break up xs into tiers.
  (let [tiers (tiers b xs)]
    ; Tiers is a sequence of tiers of nodes, like so:
    ;
    ; [          root        ]
    ; [     a           b    ]
    ; [  c    d      e    f  ]
    ; [ g h  i j    k l  m n ]
    ;
    ; We're going to zip from bottom to top, replacing nodes in the bottom tier
    ; with [parent & children] lists. Leaf nodes should be encoded as (node),
    ; so we tack on a nil tier at the very bottom.
    (loop [tiers (cons nil (reverse tiers))]
      (if (<= (count tiers) 1)
        ; We've reduced this to the root.
        (first (first tiers))
        ; Look at the bottom two tiers: children and their parents
        (let [[children parents & above] tiers
              ; Chunk the children up so they correspond to parents, and tack
              ; on an infinite series of nils if the tier isn't full.
              children (concat (partition-all b children)
                               (repeat nil))
              ; Zip parents and children together into a new tier
              tier' (map (fn [parent children]
                           (cons parent children))
                         parents children)]
          ; Replace these two bottom tiers with our new tier, and recur
          (recur (cons tier' above)))))))

(defn tree-topology
  "Arranges nodes into a tree with branch factor b."
  [b test]
  (let [nodes (:nodes test)
        tree  (seq->tree b nodes)]
    (when tree
      ; Run through the tree, using a zipper to grab children and parents.
      (loop [loc       (zip/zipper (fn [node] (< 1 (count node)))
                                   rest
                                   cons
                                   tree)
             neighbors {}]
        (if (or (nil? loc) (zip/end? loc))
          neighbors
          (let [me       (first (zip/node loc))
                parent   (when-let [u (zip/up loc)] (first (zip/node u)))
                children (when (zip/branch? loc)
                           (->> (zip/children loc)
                                (map first)))
                my-neighbors (vec (if parent
                                    (cons parent children)
                                    children))]
            (recur (zip/next loc)
                   (assoc neighbors me my-neighbors))))))))

(def topologies
  "A map of topology names to functions which generate those topologies, given
  a test."
  {:line   line-topology
   :grid   grid-topology
   :tree   (partial tree-topology 2)
   :tree2  (partial tree-topology 2)
   :tree3  (partial tree-topology 3)
   :tree4  (partial tree-topology 4)
   :total  total-topology})

(defn topology
  "Computes a topology map for the test: a map of nodes to the nodes which are
  their immediate neighbors. Uses the :topology keyword in the test map."
  [test]
  (let [topo-fn (-> test :topology topologies)]
    (topo-fn test)))

(defn client
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test]
       (let [topo (topology test)]
         (topology! conn node {:type :topology, :topology topo})))

     (invoke! [_ test op]
       (c/with-errors op #{:read}
         (case (:f op)
           :broadcast
           (do (broadcast! conn node {:type :broadcast, :message (:value op)})
               (assoc op :type :ok))

           :read
           (->> (read conn node {:type :read})
                :messages
                (assoc op :type :ok, :value)))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn checker
  "This is exactly a set-full checker, but with :add mapped to :broadcast."
  []
  (reify checker/Checker
    (check [this test history opts]
      (checker/check (checker/set-full)
                     test
                     (h/map (fn [op]
                              (if (= :broadcast (:f op))
                                (assoc op :f :add)
                                op))
                            history)
                     opts))))

(defn workload
  "Constructs a workload for a broadcast protocol, given options from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  {:client          (client (:net opts))
   :generator       (gen/mix [(->> (range)
                                   (map (fn [x] {:f :broadcast, :value x})))
                              (repeat {:f :read})])
   :final-generator (gen/each-thread {:f :read, :final? true})
   :checker         (checker)})
