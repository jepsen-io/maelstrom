;;#!/usr/bin/env bb

(ns maelstrom.echo
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
  (json/generate-string input))


(defn- printerr
  "Print the received input to stderr"
  [input]
  (binding [*out* *err*]
    (println input)))


(defn- printout
  "Print the received input to stdout"
  [input]
  (println input))


(defn reply
  ([src dest body]
   {:src src
    :dest dest
    :body body}))


(def node-id (atom ""))


(defn- process-request
  [input]
  (let [body (:body input)
        r-body {:msg_id (rand-int 100)
                :in_reply_to (:msg_id body)}]
    (case (:type body)
      "init"
      (do
        (reset! node-id (:node_id body))
        (reply @node-id
               (:src input)
               (assoc r-body :type "init_ok")))
      "echo"
      (reply @node-id
             (:src input)
             (assoc r-body
                    :type "echo_ok"
                    :echo (:echo body))))))


(defn -main
  "Read transactions from stdin and send output to stdout"
  []
  (process-stdin (comp printout
                       generate-json
                       process-request
                       parse-json)))


(-main)


#_(doseq [s (read-file "operations.json")]
    (-> s
        parse-json
        process-request
        generate-json
        printout))
