(ns maelstrom.net.viz
  "Renders lamport diagrams from the network journal"
  (:require [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [dali [prefab :as df]
                  [io :as dio]
                  [syntax :as ds]]
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
                                     :splines "line"
                                     :outputorder "nodesfirst"}

                           :node->cluster
                           (fn [node]
;                             (if (zero? (:step node))
;                               "process_names")
                             (:node node))

                           :cluster->descriptor
                           (fn [cluster]
                             {:rank     "same"
                              :rankdir  "LR"
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
    ; (println dot)
    (-> dot rv/dot->image (rv/save-image filename))))

(defn x
  "Computes the x coordinate for a Dali plot."
  [width node-index node]
  (-> node
      node-index
      (+ 1/2)
      (/ (count node-index))
      (* width)
      float))

(defn y
  "Computes the y index for an event."
  [y-step step]
  (float (* y-step (+ 1.5 step))))

(defn message->dali-line+label
  "Converts a message to a Dali line and label"
  [width y-step node-index message]
  (let [m (:message message)
        from (:from message)
        to (:to message)
        x0 (x width node-index (:node from))
        x1 (x width node-index (:node to))
        y0 (y y-step (:step from))
        y1 (y y-step (:step to))
        xmid (/ (+ x0 x1) 2)
        ymid (/ (+ y0 y1) 2)
        label [:text {:text-anchor "middle"
                      :x xmid
                      :y (- ymid (/ y-step 5))}
               (:type (:body m))]]
    [; Line
     [:polyline {:points (str x0 "," y0 " " x1 "," y1)
                 :stroke-width 3
                 :stroke "#000"
                 :marker-end "url(#arrowhead)"}
      [:title (str (:src m) " → " (:dest m)
                   " " (pr-str (:body m)))]]
     ; Label glow
     (-> label
        (assoc-in [1 :filter] "url(#glow)")
        (assoc-in [1 :style] "fill: #fff"))
     ; Label proper
     label]))

(defn plot-dali!
  "Renders an SVG plot using Dali."
  [journal filename]
  (let [width 800
        y-step 20
        nodes (all-nodes journal)
        ; Build a map of nodes to horizontal tracks
        node-index (reduce (fn [node-index node]
                             (assoc node-index node (count node-index)))
                           {}
                           nodes)
        node-labels (map (fn [node]
                           [:text {:x (x width node-index node)
                                   :y (y y-step -0.5)
                                   :text-anchor "middle"}
                            node])
                         nodes)
        node-lines (map (fn [node]
                          [:line {:stroke "#ccc"}
                           [(x width node-index node)
                            (y y-step 0)]
                           [(x width node-index node)
                            (y y-step (count journal))]])
                        nodes)
        message-lines (->> journal
                           messages
                           (mapcat (partial message->dali-line+label
                                            width
                                            y-step
                                            node-index)))
        doc   [:dali/page
               [:defs
                (ds/css (str "polyline {stroke: black; stroke-width: 1;}"))
                (df/sharp-arrow-marker :sharp {:scale 1})
                [:filter {:id "glow"}
                 [:feGaussianBlur {:stdDeviation "1.5"
                                   :result "glow"}]
                 [:feMerge
                  [:feMergeNode {:in "glow"}]
                  [:feMergeNode {:in "glow"}]
                  [:feMergeNode {:in "glow"}]
                  [:feMergeNode {:in "glow"}]
                  [:feMergeNode {:in "glow"}]
                  [:feMergeNode {:in "glow"}]]]
                [:marker {:id "arrowhead"
                          :markerWidth 10
                          :markerHeight 7
                          :refX 10
                          :refY 3.5
                          :orient "auto"}
                 [:polygon {:points "0 0, 10 3.5, 0 7"}]]]
               node-labels
               node-lines
               message-lines]]
    (pprint doc)
    (dio/render-svg doc filename)))
