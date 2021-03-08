#!/usr/bin/env bb

(ns maelstrom.gcounter
  (:gen-class)
  (:require
   [cheshire.core :as json]
   [clojure.walk :as walk]))


;;;;;;;;;;;;;;;;;;; Util functions ;;;;;;;;;;;;;;;;;;;

;;;;;; Input pre-processing functions ;;;;;;


(defn- process-stdin
  "Read lines from the stdin and calls the handler"
  [handler]
  (doseq [line (line-seq (java.io.BufferedReader. *in*))]
    (handler line)))


(defn- parse-json
  "Parse the received input as json"
  [input]
  (try
    (json/parse-string input true)
    (catch Exception e
      nil)))


;;;;;; Output Generating functions ;;;;;;

(defn- generate-json
  "Generate json string from input"
  [input]
  (when input
    (json/generate-string input)))


(let [l (Object.)]
  (defn- printerr
    "Print the received input to stderr"
    [input]
    (locking l
      (binding [*out* *err*]
        (println input)))))


(let [l (Object.)]
  (defn- printout
    "Print the received input to stdout"
    [input]
    (when input
      (locking l
        (println input)))))


(defprotocol CRDT
  "A protocol which defines behavior of a
  CRDT type"

  (combine [this other])

  (add [this element])

  (serialize [this])

  (to-val [this]))


(defrecord GCounter
  [data]

  CRDT

  (combine
    [this other]
    (assoc this :data (merge-with max data other)))


  (add
    [this element]
    (update-in this
               [:data (:node_id element)]
               (fnil + 0)
               (:value element)))


  (to-val
    [this]
    (reduce + 0 (vals data)))


  (serialize
    [this]
    data))


(defrecord PNCounter
  [inc dec]

  CRDT

  (combine
    [this other]
    (assoc this
           :inc (combine inc (get other "inc"))
           :dec (combine dec (get other "dec"))))


  (add
    [this element]
    (if (< 0 (:value element))
      (assoc this :inc (add inc element))
      (assoc this :dec (add dec (update element
                                       :value
                                       #(Math/abs %))))))


  (to-val
    [this]
    (- (to-val inc) (to-val dec)))


  (serialize
    [this]
    {:inc (serialize inc)
     :dec (serialize dec)}))


(def node-id (atom ""))
(def node-nbrs (atom []))
(def gset (atom nil))
(def next-message-id (atom 0))


(defn- reply
  ([src dest body]
   {:src src
    :dest dest
    :body body}))


(defn- send!
  ([input]
   (-> input
       generate-json
       printout))
  ([src dest body]
   (send! (reply src dest body))))


(defn- replicate-loop
  []
  (future
    (try
      (loop []
        (doseq [n @node-nbrs]
          (send! (reply @node-id n {:value (serialize @gset)
                                    :type "replicate"})))
        (Thread/sleep 5000)
        (recur))
      (catch Exception e
        (printerr e)))))


(defn- process-request
  [input]
  (let [body (:body input)
        r-body {:msg_id (swap! next-message-id inc)
                :in_reply_to (:msg_id body)}
        nid (:node_id body)
        nids (:node_ids body)]
    (case (:type body)
      "init"
      (do
        (reset! node-id nid)
        (reset! node-nbrs nids)
        (reply @node-id
               (:src input)
               (assoc r-body :type "init_ok")))

      "add"
      (do
        (swap! gset add {:node_id @node-id
                         :value (:delta body)})
        (when (:msg_id body)
          (reply @node-id
                 (:src input)
                 (assoc r-body
                        :type "add_ok"))))

      "replicate"
      (do
        ;; stringify keys
        (swap! gset combine (walk/stringify-keys (:value body)))
        nil)

      "read"
      (reply @node-id
             (:src input)
             (assoc r-body
                    :type "read_ok"
                    :value (to-val @gset))))))


(defn- gcounter []
  (->GCounter {}))


(defn- pncounter []
  (->PNCounter (gcounter) (gcounter)))


(defn -main
  "Read transactions from stdin and send output to stdout"
  []
  (replicate-loop)
  ;; if you want to run the g-counter workload,
  ;; create the gcounter CRDT
  ;;(reset! gset (gcounter))
  ;; if you want to run the pn-counter workload,
  ;; create the pncounter CRDT
  (reset! gset (pncounter))
  (process-stdin (comp printout
                       generate-json
                       process-request
                       parse-json)))


(-main)
