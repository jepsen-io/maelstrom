(ns maelstrom.workload.lin-kv
  "A workload for a linearizable key-value store."
  (:refer-clojure :exclude [read])
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.tests.linearizable-register :as lin-reg]
            [schema.core :as s]))

(c/defrpc read
  "Reads the current value of a single key. Clients send a `read` request with
  the key they'd like to observe, and expect a response with the current
  `value` of that key."
  {:type  (s/eq "read")
   :key   s/Any}
  {:type  (s/eq "read_ok")
   :value s/Any})

(c/defrpc write!
  "Blindly overwrites the value of a key. Creates keys if they do not presently
  exist. Servers should respond with a `read_ok` response once the write is
  complete."
  {:type (s/eq "write")
   :key  s/Any
   :value s/Any}
  {:type (s/eq "write_ok")})

(c/defrpc cas!
  "Atomically compare-and-sets a single key: if the value of `key` is currently
  `from`, sets it to `to`. Returns error 20 if the key doesn't exist, and 22 if
  the `from` value doesn't match."
  {:type (s/eq "cas")
   :key  s/Any
   :from s/Any
   :to   s/Any}
  {:type (s/eq "cas_ok")})

(defn client
  "Construct a linearizable key-value client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (c/with-errors op #{:read}
         (let [[k v]   (:value op)
               timeout (max (* 10 (:mean (:latency test))) 1000)]
           (case (:f op)
             :read (let [res (read conn node {:key k} timeout)
                         v (:value res)]
                     (assoc op
                            :type  :ok
                            :value (independent/tuple k v)))

             :write (let [res (write! conn node {:key k, :value v} timeout)]
                      (assoc op :type :ok))

             :cas (let [[v v'] v
                        res (cas! conn node {:key k, :from v, :to v'} timeout)]
                    (assoc op :type :ok))))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn))

     client/Reusable
     (reusable? [this test]
       true))))

(defn workload
  "Constructs a workload for linearizable registers, given option from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  (-> (lin-reg/test {:nodes (:nodes opts)})
      (assoc :client (client (:net opts)))))
