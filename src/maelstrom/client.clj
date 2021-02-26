(ns maelstrom.client
  "A synchronous client for the Maelstrom network. Handles sending and
  receiving messages, performing RPC calls, and throwing exceptions from
  errors."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [maelstrom [net :as net]
                       [util :as u]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.util.concurrent PriorityBlockingQueue
                                 TimeUnit)))

(def default-timeout
  "The default timeout for receiving messages, in millis."
  5000)

(def error-registry
  "A map of error codes to maps describing those errors. Each error map has
  keys:

    :definite?  If true, this error means the requested operation definitely did not happen
    :ns         The namespace where the error was defined
    :name       A keyword, friendly name for the error
    :doc        A docstring describing the meaning of the error"
  (atom {}))

(defmacro deferror
  "Defines a new type of error. Takes an error code, a name (a symbol, which is
  converted to a keyword and used as the `:name` of the error), a docstring,
  and an error spec map, which is merged into the error registry. `defs` the
  name symbol to the error map."
  [code name docstring & [error-spec]]
  (let [name-sym name
        name  (keyword name)
        error (assoc error-spec
                     :ns   *ns*
                     :doc  docstring
                     :code code
                     :name name)]
    (swap! error-registry
           (fn [registry]
             ; Check for dups
             (when (contains? registry code)
               (throw+ {:type :duplicate-error-code
                        :code code
                        :extant (get registry code)}))
             (when-let [extant (first (filter (comp #{name} :name)
                                              (vals registry)))]
               (throw+ {:type   :duplicate-error-name
                        :name   name
                        :extant extant}))
             (assoc registry code error)))
    `(def ~name-sym ~error)))

(deferror 0 timeout
  "Indicates that the requested operation could not be completed within a
  timeout.")

(deferror 1 node-not-found
  "Thrown when a client sends an RPC request to a node which does not exist."
  {:definite? true})

(deferror 10 not-supported
  "Use this error to indicate that a requested operation is not supported by
  the current implementation. Helpful for stubbing out APIs during
  development."
  {:definite? true})

(deferror 11 temporarily-unavailable
  "Indicates that the operation definitely cannot be performed at this
  time--perhaps because the server is in a read-only state, has not yet been
  initialized, believes its peers to be down, and so on. Do *not* use this
  error for indeterminate cases, when the operation may actually have taken
  place."
  {:definite? true})

(deferror 12 malformed-request
  "The client's request did not conform to the server's expectations, and could
  not possibly have been processed."
  {:definite? true})

(deferror 13 crash
  "Indicates that some kind of general, indefinite error occurred. Use
  this as a catch-all for errors you can't otherwise categorize, or as a
  starting point for your error handler: it's safe to return `internal-error`
  for every problem by default, then add special cases for more specific errors
  later."
  {:definite? false})

(deferror 14 abort
  "Indicates that some kind of general, definite error occurred. Use this as a
  catch-all for errors you can't otherwise categorize, when you specifically
  know that the requested operation has not taken place. For instance, you
  might encounter an indefinite failure during the prepare phase of a
  transaction: since you haven't started the commit process yet, the
  transaction can't have taken place. It's therefore safe to return a definite
  `abort` to the client."
  {:definite? true})

(defn open!
  "Creates a new synchronous network client, which can only do one thing at a
  time: send a message, or wait for a response. Mutates network to register the
  client node. Options are currently unused."
  ([net]
   (open! net {}))
  ([net opts]
   (let [id (str "c" (:next-client-id (swap! net update :next-client-id inc)))]
     (net/add-node! net id)
     {:net         net
      :node-id     id
      :next-msg-id (atom 0)
      :waiting-for (atom nil)})))

(defn close!
  "Closes a sync client."
  [client]
  (reset! (:waiting-for client) :closed)
  (net/remove-node! (:net client) (:node-id client)))

(defn msg-id!
  "Generates a new message ID for a client."
  [client]
  (swap! (:next-msg-id client) inc))

(defn send!
  "Sends a message over the given client. Fills in the message's :src and
  [:body :msg_id]"
  [client msg]
  (let [msg-id (or (:msg_id (:body msg)) (msg-id! client))
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
            error     (get @error-registry code)]
        (throw+ {:type      :rpc-error
                 :code      code
                 :name      (:name error :unknown)
                 :definite? (:definite? error false)
                 :body      body}))))
  m)

(defn rpc!
  "Takes a client, a destination node, and a message body. Sends a message to
  that node, and waits for a response. Returns response body, interpreting
  error codes, if any, as exceptions. Options are:

  :timeout - in milliseconds, how long to wait for a response"
  ([client dest body]
   (rpc! client dest body default-timeout))
  ([client dest body timeout]
     (->> (send+recv! client {:dest dest, :body body} timeout)
          (throw-errors! client)
          :body)))

(defmacro with-errors
  "Takes an operation, a set of idempotent `:f`s, and evaluates body. Captures
  RPC errors and converts them to operations with :type :info or :type :fail,
  as appropriate."
  [op idempotent & body]
  `(try+
     ~@body
     (catch [:type ::timeout] e#
       (let [type# (if (~idempotent (:f ~op)) :fail :info)]
         (assoc ~op
                :type type#,
                :error :net-timeout)))
     (catch [:type :rpc-error] e#
       (let [type# (if (or (:definite? e#)
                           (~idempotent (:f ~op)))
                     :fail
                     :info)]
         (assoc ~op
                :type type#
                :error [(:name e#) (:text (:body e#))])))))

;; Defining RPCs

(def rpc-registry
  "A persistent registry of all RPC calls we've defined. Used to automatically
  generate documentation!"
  (atom []))

(defn check-body
  "Uses a schema checker to validate `data`. Throws an exception if validation
  fails, explaining why the message failed validation. Returns message
  otherwise. Type is either :send or :recv, and is used to construct an
  appropriate type and error message."
  [type schema checker dest req body]
  (when-let [errs (checker body)]
    (throw+ {:type     (case type
                         :send :malformed-rpc-request
                         :recv :malformed-rpc-response)
             :body     body
             ; Can't assoc this: it's not serializable.
             ;:error    errs
             }
            nil
            (str "Malformed RPC "
                 (case type
                   :send "request. Maelstrom should have constructed a message body like:"
                   :recv (str "response. Maelstrom sent node " dest
                              " the following request:\n\n"
                              (with-out-str (pprint req))
                              "\nAnd expected a response of the form:"))
                 "\n\n"
                 (with-out-str (pprint schema))
                 "\n... but instead " (case type
                                        :send "sent"
                                        :recv "received")
                 "\n\n"
                 (with-out-str (pprint body))
                 "\nThis is malformed because:\n\n"
                 (with-out-str (pprint errs))
                 "\nSee doc/protocol.md for more guidance."))))

(defn send-schema
  "Takes a partial schema for an RPC request, and enhances it to include a
  :msg_id field."
  [schema]
  (assoc schema :msg_id s/Int))

(defn recv-schema
  "Takes a partial schema for an RPC response, and enhances it to include a
  :msg_id optional field, and an in_reply_to field."
  [schema]
  (assoc schema
         (s/optional-key :msg_id) s/Int
         :in_reply_to s/Int))

(defmacro defrpc
  "Defines a typed RPC call: a function called `fname`, which takes arguments
  as for `rpc!`, and validates that the sent and received bodies conform to the
  given schema. This is a macro because we want to re-use the schema
  checkers--they're expensive to validate ad-hoc."
  [fname docstring send-schema recv-schema]
  `(let [; Enhance schemas to include message ids and reply_to fields.
         send-schema#  (send-schema ~send-schema)
         recv-schema#  (recv-schema ~recv-schema)
         ; Construct persistent checkers
         send-checker# (s/checker send-schema#)
         recv-checker# (s/checker recv-schema#)

         ; Extract the message type string from the request schema
         message-type# (-> ~send-schema s/explain :type second)]
     (assert (string? message-type#))

     ; Record RPC spec in registry
     (swap! rpc-registry conj
            {:ns   *ns*
             :name (quote ~fname)
             :doc  ~docstring
             :send send-schema#
             :recv recv-schema#})

     (defn ~fname
       ([client# dest# body#]
        (~fname client# dest# body# default-timeout))

       ([client# dest# body# timeout#]
        ; Generate a message ID here, so it passes the schema checker. This is
        ; a little duplicated effort, but it means that schemas say *exactly*
        ; what bodies should be, and I think that will help implementers.
        (let [body# (assoc body#
                           :type   message-type#
                           :msg_id (msg-id! client#))]
          ; Validate request
          (check-body :send send-schema# send-checker# dest# body# body#)
          ; Make RPC call
          (let [res# (rpc! client# dest# body# timeout#)]
            ; Validate response
            (check-body :recv recv-schema# recv-checker# dest# body# res#)
            res#))))))
