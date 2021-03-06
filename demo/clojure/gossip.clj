#!/usr/bin/env bb

(ns maelstrom.gossip
  (:gen-class)
  (:require
    [cheshire.core :as json]
    [clojure.java.io :as io]))


;;;;;;;;;;;;;;;;;;; Util functions ;;;;;;;;;;;;;;;;;;;

;;;;;; Input pre-processing functions ;;;;;;

(defn- read-file
  "Read a file into a vector of strings.
  This is used for local testing in repl"
  [f]
  (with-open [rdr (io/reader (io/input-stream f))]
    (reduce conj [] (line-seq rdr))))


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


(let [l ""]
  (defn- printerr
    "Print the received input to stderr"
    [input]
    (locking l
      (binding [*out* *err*]
        (println input)))))


(let [l ""]
  (defn- printout
    "Print the received input to stdout"
    [input]
    (when input
      (locking l
        (println input)))))


(defn reply
  ([src dest body]
   {:src src
    :dest dest
    :body body}))


(def node-id (atom ""))
(def node-nbrs (atom []))
(def messages (atom #{}))

(defn- process-request
  [input]
  (let [body (:body input)
        r-body {:msg_id (rand-int 100)
                :in_reply_to (:msg_id body)}
        nid (:node_id body)]
    (case (:type body)
      "init"
      (do
        (reset! node-id nid)
        (reply @node-id
               (:src input)
               (assoc r-body :type "init_ok")))

      "topology"
      (do
        (reset! node-nbrs (get-in input [:body
                                         :topology
                                         (keyword @node-id)]))
        (reply @node-id
             (:src input)
             (assoc r-body
                    :type "topology_ok")))

      "broadcast"
      (do
        ;; if we already have the message, it means
        ;; this is a broadcast, dont rebroadcast
        (when-not (@messages (:message body))
          (doseq [n @node-nbrs]
            ;; we have to explicitly print this out
            ;; because just calling reply only generates a
            ;; map which the outer loop prints out
            (printout (generate-json (reply @node-id
                                            n
                                            {:type "broadcast"
                                             :message (:message body)})))))
        (swap! messages conj (:message body))
        (when (:msg_id body)
          (reply @node-id
                 (:src input)
                 (assoc r-body
                        :type "broadcast_ok"))))

      "read"
      (reply @node-id
             (:src input)
             (assoc r-body
                    :type "read_ok"
                    :messages @messages)))))


(defn -main
  "Read transactions from stdin and send output to stdout"
  []
  (process-stdin (comp printout
                       generate-json
                       process-request
                       parse-json)))

(-main)
