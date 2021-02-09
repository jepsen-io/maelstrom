(ns maelstrom.workload.lin-kv
  "A workload for a linearizable key-value store."
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.tests.linearizable-register :as lin-reg]))

(def errors
  "KV-specific error codes"
  {20 {:definite? true    :name :key-does-not-exist}
   21 {:definite? true    :name :key-already-exists}
   22 {:definite? true    :name :precondition-failed}})

(defn client
  "Construct a linearizable key-value client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net {:errors errors}) node))

     (setup! [this test])

     (invoke! [_ test op]
       (let [[k v]   (:value op)
             timeout (max (* 10 (:latency test)) 1000)]
           (case (:f op)
             :read (let [res (c/rpc! conn node {:type "read", :key k}
                                     timeout)
                         v (:value res)]
                     (assoc op
                            :type :ok
                            :value (independent/tuple k v)))

             :write (let [res (c/rpc! conn node
                                      {:type "write", :key k, :value v}
                                      timeout)]
                      (assoc op :type :ok))

             :cas (let [[v v'] v
                        res (c/rpc! conn node
                                    {:type "cas", :key k, :from v, :to v'}
                                    timeout)]
                    (assoc op :type :ok)))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn workload
  "Constructs a workload for linearizable registers, given option from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  (-> (lin-reg/test {:nodes (:nodes opts)})
      (assoc :client (client (:net opts)))))
