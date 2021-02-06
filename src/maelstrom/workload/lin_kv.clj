(ns maelstrom.workload.lin-kv
  "A workload for a linearizable key-value store."
  (:require [maelstrom [net :as net]]
            [jepsen [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.tests.linearizable-register :as lin-reg]))

(def known-failure-codes
  #{1 10 11 20 21 22})

(defn error
  "Takes an invocation operation and a response message for a client request.
  If the response is an error, constructs an appropriate error operation.
  Otherwise, returns nil."
  [op msg]
  (let [body (:body msg)]
    (when (= "error" (:type body))
      (let [type (if (known-failure-codes (:code body))
                   :fail
                   :info)]
        (assoc op :type type, :error [(:code body) (:text body)])))))

(defn client
  "Construct a linearizable key-value client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (net/sync-client! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (let [[k v]          (:value op)
             client-timeout (max (* 10 (:latency test)) 1000)]
         (case (:f op)
           :read (let [res (net/sync-client-send-recv!
                             conn
                             {:dest node
                              :body {:type "read", :key  k}}
                             client-timeout)
                       v (:value (:body res))]
                   (or (error op res)
                       (assoc op
                              :type :ok
                              :value (independent/tuple k v))))

           :write (let [res (net/sync-client-send-recv!
                              conn
                              {:dest node
                               :body {:type "write", :key k, :value v}}
                              client-timeout)]
                    (or (error op res)
                        (assoc op :type :ok)))

           :cas (let [[v v'] v
                      res (net/sync-client-send-recv!
                            conn
                            {:dest node
                             :body {:type "cas", :key k, :from v, :to v'}}
                            client-timeout)]
                  (or (error op res)
                      (assoc op :type :ok))))))

     (teardown! [_ test])

     (close! [_ test]
       (net/sync-client-close! conn)))))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn workload
  "Constructs a workload for linearizable registers, given option from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  (-> (lin-reg/test {:nodes (:nodes opts)})
      (assoc :client (client (:net opts)))))

