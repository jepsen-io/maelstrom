#!/usr/bin/env bb

(deps/add-deps
  '{:deps {slingshot/slingshot {:mvn/version "0.12.2"}}})

(ns maelstrom.demo.node
  "General-purpose node operations: registering RPC handlers, sending
  messages, making our own RPC calls, and so on."
  (:require [cheshire.core :as json]
            [clojure.pprint :refer [pprint]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (clojure.lang ExceptionInfo)
           (java.io BufferedReader)
           (java.util.concurrent CompletableFuture
                                 ExecutionException)
           (java.util.function Function)))

;; Node state

(def node-id
  "Our own node ID"
  (promise))

(def node-ids
  "All node IDs in the cluster."
  (promise))

(def next-message-id
  "What's the next message ID we'll emit?"
  (atom 0))

(def handlers
  "A map of message body types to functions which can handle those messages."
  (atom {; Ignore errors we receive when they're not in reply to anything;
         ; otherwise we'll ping-pong errors back and forth forever.
         "error" (constantly nil)}))

(def rpcs
  "A map of message IDs to Futures which should be delivered with replies."
  (atom {}))

(defn log
  "Logs a message to stderr"
  [& args]
  (locking *err*
    (binding [*out* *err*]
      (apply println args))))

(defn log-ex
  "Logs an exception (actually, any throwable), printing its stacktrace. Prints
  args first, then exception, then stacktrace."
  [^Throwable ex & args]
  (locking *err*
    (apply log args)
    (log ex)
    (.printStackTrace ex *err*)))

(defn pp-str
  "Pretty-print to a string."
  [x]
  (with-out-str (pprint x)))

;; A few utilities for futures

(defmacro then
  "Takes a CompletableFuture, a binding vector with a symbol for the value of
  that future and a body. Returns a CompletableFuture which evaluates body with
  the value bound."
  [fut [sym] & body]
  `(.thenApply ^CompletableFuture ~fut
               (reify Function
                 (apply [this# ~sym]
                   ~@body))))

(defmacro exceptionally
  "Takes a CompletableFuture, a binding vector with a symbol for an exception
  thrown by that future, and a body. Returns a CompletableFuture which
  evaluates body with that exception bound, if one is thrown."
  [fut [sym] & body]
  `(.exceptionally ^CompletableFuture ~fut
                   (reify Function
                     (apply [this# ~sym]
                       ~@body))))

;; Working with node IDs

(defn other-node-ids
  "A set of other node IDs."
  []
  (disj (set @node-ids) @node-id))

;; Core node functions

(defn add-handler!
  "Registers a handler. Takes a message body type (e.g. :echo) and a
  function of a request, which should be invoked whenever an RPC request of
  that type arrives."
  [type handler]
  (swap! handlers assoc (name type) handler))

(defmacro defhandler
  "Sugar for add-handler!. Takes an RPC type, a docstring, a binding
  form of one argument (the request), and a body to evaluate. Defines a
  function to handle that RPC and calls it whenever a request arrives."
  [type docstring bindings & body]
  (let [fn-name (gensym (str "handle-" type))]
    `(do (defn ~fn-name ~docstring ~bindings ~@body)
         (add-handler! '~type ~fn-name))))

(defn send!
  "Sends a message on stdout."
  [dest body]
  (locking *out*
    (println (json/generate-string {:src @node-id, :dest dest, :body body}))))

(defn reply!
  "Replies to a request message with the given body."
  [req body]
  (send! (:src req) (assoc body :in_reply_to (:msg_id (:body req)))))

(defn rpc!
  "Sends an RPC request body to the given node, and returns a CompletableFuture
  of a response body."
  [dest body]
  (let [fut (CompletableFuture.)
        id  (swap! next-message-id inc)]
    (swap! rpcs assoc id fut)
    (send! dest (assoc body :msg_id id))
    fut))

(defn handle-reply!
  "Handles a reply to an RPC we issued."
  [{:keys [body] :as reply}]
  (when-let [fut (get @rpcs (:in_reply_to body))]
    (if (= "error" (:type body))
      (.completeExceptionally fut (ex-info (:text body)
                                           (dissoc body :type :text)))
      (.complete fut body)))
  (swap! rpcs dissoc (:in_reply_to body)))

(defn handle-init!
  "Handles an init message by saving the node ID and node IDS to our local
  state."
  [{:keys [body] :as req}]
  (deliver node-id (:node_id body))
  (deliver node-ids (:node_ids body))
  (log "I am node" @node-id)
  ; Also try a custom handler if one's been registered.
  (when-let [handler (get @handlers "init")]
    (handler req))
  (reply! req {:type :init_ok}))

(declare handle-txn!)

(defn handle-req!
  "Handles incoming request messages"
  [{:keys [body] :as req}]
  (let [type (:type body)]
    (if (= type "init")
      (handle-init! req)
      (if-let [handler (get @handlers type)]
        (handler req)
        (throw (ex-info (str "Unknown request type " type) {:code 10}))))))

(defn process-stdin!
  "Mainloop which handles messages from stdin"
  []
  (doseq [line (line-seq (BufferedReader. *in*))]
    (future
      (let [req (json/parse-string line true)]
        (try
          (if (:in_reply_to (:body req))
            (handle-reply! req)
            (handle-req! req))
          (catch ExceptionInfo e
            ; Send these back to the client as error messages
            (reply! req (assoc (ex-data e)
                               :type :error
                               :text (ex-message e))))
          (catch Exception e
            (locking *err*
              (log "Error processing request" req)
              (log e))
            ; And send a general-purpose error message back to the client
            (reply! req {:type :error
                         :code 13
                         :text (ex-message e)})))))))

(defn start!
  "Starts running the node."
  []
  (try
    (process-stdin!)
    (catch Throwable t
      (log-ex t "Fatal error in mainloop!"))))
