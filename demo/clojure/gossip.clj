#!/usr/bin/env bb

(ns maelstrom.gossip
  (:gen-class)
  (:require
    [cheshire.core :as json]))


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


(def node-id (atom ""))
(def node-nbrs (atom []))
(def messages (atom #{}))
(def gossips (atom {}))
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


(defn- gossip-loop
  []
  (future
    (try
      (loop []
        (doseq [g @gossips]
          (send! (second g)))
        (Thread/sleep 1000)
        (recur))
      (catch Exception e
        (printerr e)))))


(defn- process-request
  [input]
  (let [body (:body input)
        r-body {:msg_id (swap! next-message-id inc)
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
        (when-not (@messages (:message body))
          (doseq [n @node-nbrs
                  ;; also don't broadcast to sender
                  :when (not= n (:src input))]
            ;; here we need to distinguish between msg-id received
            ;; directly from a broadcast message which will be :msg_id
            ;; and msg-id received from a peer as part of gossip.
            ;; We can receive gossip with msg-id 1 and message 1
            ;; and later receive broadcast with msg-id 1 and message 3
            ;; Also, the msg-id we send here has to be used to
            ;; record the message for retries which is then removed
            ;; when we receive gossip_ok message
            (let [msg-id (or (:msg_id body)
                             (str "g:" (:g_id body)))
                  msg {:src @node-id
                       :dest n
                       :body {:type "broadcast"
                              :message (:message body)
                              :g_id msg-id}}]
              (swap! gossips assoc (str n "::" msg-id) msg)
              ;; we have to explicitly send this out
              ;; because just calling reply only generates a
              ;; map which the outer loop prints out
              (send! msg))))
        (swap! messages conj (:message body))
        (if (:msg_id body)
          (reply @node-id
                 (:src input)
                 (assoc r-body
                        :type "broadcast_ok"))
          (when (:g_id body)
            (reply @node-id
                   (:src input)
                   {:type "gossip_ok"
                    :g_id (:g_id body)}))))

      "gossip_ok"
      (do
        (swap! gossips dissoc (str (:src input) "::" (:g_id body)))
        nil)

      "read"
      (reply @node-id
             (:src input)
             (assoc r-body
                    :type "read_ok"
                    :messages @messages)))))


(defn -main
  "Read transactions from stdin and send output to stdout"
  []
  (gossip-loop)
  (process-stdin (comp printout
                       generate-json
                       process-request
                       parse-json)))


(-main)
