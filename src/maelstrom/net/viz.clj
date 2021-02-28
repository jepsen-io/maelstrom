(ns maelstrom.net.viz
  "Renders lamport diagrams from the network journal"
  (:require [clojure.string :as str]
            [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [analemma [xml :as xml]
                      [svg :as svg]]
            [maelstrom [util :as u]]))

(def journal-limit
  "SVG rendering is pretty expensive; we stop rendering after this many
  journal events."
  10000)

(defn all-nodes
  "Takes a journal and returns the collection of all nodes involved in it."
  [journal]
  (->> journal
       (map :message)
       (mapcat (juxt :src :dest))
       distinct
       u/sort-clients))

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

;; SVG Rendering

(defn layout
  "Constructs a layout object with general information we need to position
  messages in space."
  [journal]
  (let [width  1200
        y-step 20
        truncated? (< journal-limit (count journal))
        step-count (min journal-limit (count journal))
        ; + 2 because our ys start at 1.5
        ; + 1 for extra node line height at end
        ; + 2 for truncation notice at end
        ; + 2 for whitespace
        height     (* y-step (+ 2 1 2 2 step-count))
        nodes      (all-nodes journal)
        ; A map of nodes to a horizontal index from the left
        node-index (reduce (fn [node-index node]
                             (assoc node-index node (count node-index)))
                           {}
                           nodes)]
    {:width       width
     :height      height
     :step-count  step-count
     :y-step      y-step
     :truncated?  truncated?
     :nodes       nodes
     :node-index  node-index}))

(defn x
  "Computes the x coordinate for a Dali plot."
  [layout node]
  (let [{:keys [width node-index]} layout]
    (-> node
        node-index
        (+ 1/2)
        (/ (count node-index))
        (* width)
        float)))

(defn y
  "Computes the y index for an event."
  [layout step]
  (float (* (:y-step layout) (+ 1.5 step))))

(defn message->color
  "Takes a message event and returns what color to use in drawing it."
  [{:keys [from to message]}]
  (cond (= "error" (:type (:body message)))
        "#FF1E90"

        (u/involves-client? message)
        "#81BFFC"

        :else
        "#000"))

(defn norm
  "Cartesian distance between two points [x0 y0] [x1 y1]"
  [[x0 y0] [x1 y1]]
  (Math/sqrt (+ (Math/pow (- x1 x0) 2)
                (Math/pow (- y1 y0) 2))))

(defn angle
  "Angle of the vector defined by two points."
  [[x0 y0] [x1 y1]]
  (Math/atan2 (- y1 y0) (- x1 x0)))

(defn rad->deg
  "Convert radians to degrees"
  [rad]
  (-> rad (/ 2 Math/PI) (* 360)))

(defn truncate-string
  "Cuts a string to a maximum of n characters."
  [string n]
  (if (<= (.length string) n)
    string
    (str (subs string 0 (- n 1)) "…")))

(defn message-line
  "Converts a message to a line and label."
  [layout message]
  (let [m (:message message)
        body (:body m)
        from (:from message)
        to (:to message)
        x0 (x layout (:node from))
        x1 (x layout (:node to))
        y0 (y layout (:step from))
        y1 (y layout (:step to))
        xmid (/ (+ x0 x1) 2)
        ymid (/ (+ y0 y1) 2)
        ; How long is the line gonna be? We draw this horizontally, then rotate
        ; it into place with the text.
        length (norm [x0 y0] [x1 y1])
        ; How should we rotate it?
        angle  (rad->deg (angle [x0 y0] [x1 y1]))
        ; Are we flipping around to point left?
        left?  (not (< -90 angle 90))

        desc  (str (:type body)
                   " "
                   (case (:type body)
                     "error" (:text body)
                     (-> body
                         (dissoc :type :msg_id :in_reply_to)
                         pr-str)))
        desc  (truncate-string desc 48)
        label [:text {:text-anchor "middle"
                      :x (/ length 2)
                      ; Just above line
                      :y (- (/ (:y-step layout) 5))
                      ; Text will be upside down, so we flip it here
                      :transform (when left?
                                   (str "rotate(180 "(/ length 2) " 0)"))}
               desc]]
    ; Recall that transforms are applied last to first, because they expand to
    ; effectively nested transforms
    [:g {:transform (str
                      "translate(" x0 " " y0 ") "
                      "rotate(" angle ") "
                      )}
     ; Line
     [:rect {:x 0
             :y -2.5
             :height 5
             ; Don't overshoot the arrowhead
             :width  (- length 4)
             :fill   (message->color message)}
      [:title (str (:src m) " → " (:dest m)
                   " " (pr-str (:body m)))]]
     ; Arrowhead
     [:use {"xlink:href" "#ahead", :x length, :y 0}]

     ; Label glow
     (-> label
        (assoc-in [1 :filter] "url(#glow)")
        (assoc-in [1 :style] "fill: #fff"))
     ; Label proper
     label]))

(defn node-labels
  "Takes a layout and renders the node names periodically"
  [layout]
  (cons :g
        (for [node (:nodes layout)
              step (range 0 (:step-count layout) 50)]
          [:text {:x (x layout node)
                  :y (y layout (- step 0.5))
                  :text-anchor        "middle"
                  :alignment-baseline "middle"
                  :fill (if (zero? step) "#000" "#aaa")}
                node])))

(defn node-lines
  "Takes a layout and renders the vertical gray lines for each node."
  [layout journal]
  (cons :g
        (map (fn [node]
               [:line {:stroke "#ccc"
                       :x1 (x layout node)
                       :y1 (y layout 0)
                       :x2 (x layout node)
                       :y2 (y layout (inc (:step-count layout)))}])
             (:nodes layout))))

(defn message-lines
  "Takes a layout and a journal, and produces a set of lines for each message."
  [layout journal]
  (->> journal
       (take journal-limit)
       messages
       (map (partial message-line layout))
       (cons :g)))

(defn truncated-notice
  "Takes a layout and a journal, and renders a warning if we had to truncate
  the journal in this rendering."
  [layout journal]
  (when (:truncated? layout)
    (let [step (+ 2 (:step-count layout))
          color "#D96918"]
      [:g
       [:line {:x1 30
               :y1 (y layout step)
               :x2 (- (:width layout) 30)
               :y2 (y layout step)
               :stroke-width 1
               :stroke color}]
       [:text {:x (/ (:width layout) 2)
               :y (y layout (inc step))
               :fill color
               :text-anchor "middle"}
        (str (- (count journal) journal-limit)
             " later network events not shown")]])))

(defn glow-filter
  "A filter to make text stand out better by adding a white glow"
  []
  [:filter {:id "glow" :x "0" :y "0"}
   [:feGaussianBlur {:in "SourceAlpha"
                     :stdDeviation "2"
                     :result "blurred"}]
   [:feFlood {:flood-color "#fff"}]
   [:feComposite {:operator "in" :in2 "blurred"}]
   [:feComponentTransfer
    [:feFuncA {:type "linear" :slope "10" :intercept 0}]]
   [:feMerge
    [:feMergeNode]
    [:feMergeNode {:in "SourceGraphic"}]]])

(defn arrowhead
  "A little arrowhead def"
  []
  [:polygon {:id "ahead" :points "0 0, -12 4, -12 -4"}])

(defn plot-analemma!
  "Renders an SVG plot using Analemma. Hopefully faster than Dali, which uses
  reflection EVERYWHERE."
  [journal filename]
  (let [layout (layout journal)
        svg (svg/svg {"version" "2.0"
                      "width"  (+ (:width layout 50))
                      "height" (:height layout)}
              [:style "
svg {
  font-family: Helvetica, Arial, sans-serif;
  font-size: 11px;
}
rect {
  stroke-linecap: butt;
  stroke-width: 3;
  stroke: #fff;
/*  stroke-opacity: 1; */
}
rect:hover {
  stroke: #fff;
/*  stroke-opacity: 1; /*
}
polygon { fill: #000; }"
               ]
              [:defs
               (arrowhead)
               (glow-filter)]
              (node-labels      layout)
              (node-lines       layout journal)
              (message-lines    layout journal)
              (truncated-notice layout journal)
              )]
    (spit filename (xml/emit svg))))
