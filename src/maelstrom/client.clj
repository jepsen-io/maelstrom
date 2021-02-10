(ns maelstrom.client
  "A synchronous client for the Maelstrom network. Handles sending and
  receiving messages, performing RPC calls, and throwing exceptions from
  errors."
  (:require [clojure.tools.logging :refer [info warn]]
            [maelstrom.net :as net]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.util.concurrent PriorityBlockingQueue
                                 TimeUnit)))

(def default-timeout
  "The default timeout for receiving messages, in millis."
  5000)

(def common-errors
  "Errors which all Maelstrom tests support. A map of error codes to keyword
  names for those errors, and whether or not that error is definite."
  {0  {:definite? false   :name :timeout}
   1  {:definite? true    :name :node-not-found}
   10 {:definite? true    :name :not-supported}
   11 {:definite? true    :name :temporarily-unavailable}})

(defn open!
  "Creates a new synchronous network client, which can only do one thing at a
  time: send a message, or wait for a response. Mutates network to register the
  client node. Options are:

      :errors   A structure defining additional error types. See common-errors."
  ([net]
   (open! net {}))
  ([net opts]
   (let [id (str "c" (:next-client-id (swap! net update :next-client-id inc)))]
     (net/add-node! net id)
     {:net         net
      :node-id     id
      :next-msg-id (atom 0)
      :waiting-for (atom nil)
      :errors      (merge common-errors (:errors opts))})))

(defn close!
  "Closes a sync client."
  [client]
  (reset! (:waiting-for client) :closed)
  (net/remove-node! (:net client) (:node-id client)))

(defn send!
  "Sends a message over the given client. Fills in the message's :src and
  [:body :msg_id]"
  [client msg]
  (let [msg-id (swap! (:next-msg-id client) inc)
        net    (:net client)
        ok?    (compare-and-set! (:waiting-for client) nil msg-id)
        msg    (-> msg
                   (assoc :src (:node-id client))
                   (assoc-in [:body :msg_id] msg-id))]
    (when-not ok?
      (throw (IllegalStateException.
               "Can't send more than one message at a time!")))
    (net/send! net msg)))

(defn recv!
  "Receives a message for the given client. Times out after timeout ms."
  ([client]
   (recv! client default-timeout))
  ([client timeout-ms]
   (let [target-msg-id @(:waiting-for client)
         net           (:net client)
         node-id       (:node-id client)
         deadline      (+ (System/nanoTime) (* timeout-ms 1e6))]
     (assert target-msg-id "This client isn't waiting for any response!")
     (try
       (loop []
         ; (info "Waiting for message" (pr-str target-msg-id) "for" node-id)
         (let [timeout (/ (- deadline (System/nanoTime)) 1e6)
               msg     (net/recv! net node-id timeout)]
           (cond ; Nothing in queue
                 (nil? msg)
                 (throw+ {:type       ::timeout
                          :name       :timeout
                          :definite?  false
                          :code       0}
                         nil
                         "Client read timeout")

                 ; Reply to some other message we sent (e.g. that we gave up on)
                 (not= target-msg-id (:in_reply_to (:body msg)))
                 (recur)

                 ; Hey it's for us!
                 true
                 msg)))
       (finally
         (when-not (compare-and-set! (:waiting-for client)
                                     target-msg-id
                                     nil)
           (throw (IllegalStateException.
                    "two concurrent calls of sync-client-recv!?"))))))))

(defn send+recv!
  "Sends a request and waits for a response."
  [client req-msg timeout]
  (send! client req-msg)
  (recv! client timeout))

(defn throw-errors!
  "Takes a client and a message m, and throws if m's body is of :type \"error\".
  Returns m otherwise."
  [client m]
  (let [body (:body m)]
    (when (= "error" (:type body))
      (let [code      (:code body)
            error     (get (:errors client) code)]
        (throw+ {:type      :rpc-error
                 :code      code
                 :name      (:name error :unknown)
                 :definite? (:definite? error false)
                 :body      body}))))
  m)

(defn rpc!
  "Takes a client, a destination node, and a message body. Sends a message to
  that node, and waits for a response. Returns response body, interpreting
  error codes, if any, as exceptions."
  ([client dest body]
   (rpc! client dest body default-timeout))
  ([client dest body timeout]
     (->> (send+recv! client {:dest dest, :body body} timeout)
          (throw-errors! client)
          :body)))
