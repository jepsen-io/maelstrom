(ns maelstrom.workload.g-set
  "A grow-only set workload: clients add elements to a set, and read the
  current value of the set."
  (:refer-clojure :exclude [read])
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]]))

(c/defrpc add!
  "Requests that a server add a single element to the set. Acknowledged by an
  `add_ok` message."
  {:type    (s/eq "add")
   :element s/Any}
  {:type    (s/eq "add_ok")})

(c/defrpc read
  "Requests the current set of all elements. Servers respond with a message
  containing an `elements` key, whose `value` is a JSON array of added
  elements."
  {:type (s/eq "read")}
  {:type (s/eq "read_ok")
   :value [s/Any]})

(defn client
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (case (:f op)
         :add (do (add! conn node {:element (:value op)})
                  (assoc op :type :ok))

         :read (assoc op
                      :type :ok
                      :value (:value (read conn node {})))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn workload
  "Constructs a workload for a grow-only set, given options from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  {:client    (client (:net opts))
   :generator (gen/mix [(->> (range) (map (fn [x] {:f :add, :value x})))
                        (repeat {:f :read})])
   :final-generator (gen/each-thread {:f :read})
   :checker   (checker/set-full)})
