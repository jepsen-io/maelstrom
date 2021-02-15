(ns maelstrom.net.viz
  "Renders lamport diagrams from the network journal"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [rhizome [dot :as rd]
                     [viz :as rv]]))

(defn all-nodes
  "Takes a journal and returns the set of all nodes involved in it."
  [journal]
  (->> journal
       (map :message)
       (mapcat (juxt :src :dest))
       (into (sorted-set))))

(defn init-nodes
  "Takes a set of node ids and generates initial dot nodes for them."
  [nodes]
  (map (fn [id]
         {:node id
          :step 0})
       nodes))

(defn step-nodes
  "Takes a journal and a set of node ids, and generates a collection of dot
  nodes for each node at each step of the journal."
  [journal node-ids]
  (for [step (range (count journal)), node-id node-ids]
    {:node node-id
     :step (inc step)}))

(defn messages
  "Takes a journal and constructs a sequence of messages: each a map with

  {:from      A dot node
   :to        A dot node
   :message   The message exchanged}"
  ([journal]
   (messages {} 1 (seq journal)))
  ; froms is a map of message IDs to the dot node of their origin.
  ; step is the timestep we're at now--index in the journal, plus one.
  ([froms step journal]
   (when journal
     (lazy-seq
       (let [event   (first journal)
             message (:message event)
             id      (:id message)]
         (case (:type event)
           ; We're sending a message; remember this in our froms map
           :send (messages (assoc froms id {:node (:src message)
                                            :step step})
                           (inc step)
                           (next journal))
           ; We're receiving a message; emit an edge.
           :recv (let [from (get froms id)]
                   (assert from)
                   (cons {:from     from
                          :to       {:node (:dest message)
                                     :step step}
                          :message  message}
                         (messages froms (inc step) (next journal))))))))))

(defn message-edges
  "Takes a journal and constructs a map of dot nodes to dot edges,
  corresponding to each message in the journal."
  [journal]
  (->> (messages journal)
       (group-by :from)))

(defn plot!
  "Renders a journal to a file."
  [journal filename]
  (let [nodes         (all-nodes journal)
        init-nodes    (init-nodes nodes)
        step-nodes    (step-nodes journal nodes)
        message-edges (message-edges journal)
        max-step      (count journal)
        ; Our node set is the initial states plus each step.
        dot-nodes     (concat init-nodes step-nodes)
        edges (fn edges [from]
                ; Take each node, look up the messages outbound from that
                ; node, and return their :to nodes.
                (let [msg-edges (->> (get message-edges from)
                                     (map :to))
                      ; Do we have an edge to the next step, too?
                      step-edge (when (< (:step from) max-step)
                                  (-> from
                                      (update :step inc)))]
                  (cond-> msg-edges
                    step-edge (conj step-edge))))
        dot (rd/graph->dot dot-nodes
                           edges

                           :options {:rankdir "LR"
                                     :splines false
                                     :outputorder "nodesfirst"}

                           :node->cluster
                           (fn [node]
;                             (if (zero? (:step node))
;                               "process_names")
                             (:node node))

                           :cluster->descriptor
                           (fn [cluster]
                             {:rank     "same"
                              :style    "invis"})

                           :node->descriptor
                           (fn [node]
                             (if (zero? (:step node))
                               {:group (:node node)
                                :label (:node node)}
                               {:group (:node node)
                                :shape "point"
                                :color "gray75"}))

                           :edge->descriptor
                           (fn [from to]
                             ; Look up that edge, if one exists
                             (let [edge (->> (get message-edges from)
                                             (filter (comp #{to} :to))
                                             first)]
                               (if edge
                                 ; A message
                                 {:constraint false
                                  ; :labelfloat true
                                  ;:weight 3
                                  :tooltip (pr-str (:body (:message edge)))
                                  :label   (:type (:body (:message edge)))}
                                 ; A step
                                 {:weight 2
                                  :arrowhead "none"
                                  :color "gray75"}))))]
    (println dot)
    (-> dot rv/dot->image (rv/save-image filename))))
