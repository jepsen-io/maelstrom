(ns maelstrom.workload.g-set
  "A grow-only set workload: clients add elements to a set, and read the
  current value of the set."
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [slingshot.slingshot :refer [try+ throw+]]))

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
         :add (do (c/rpc! conn node {:type    :add
                                     :element (:value op)})
                  (assoc op :type :ok))

         :read (assoc op :type :ok
                      :value (:value (c/rpc! conn node {:type :read})))))

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
   :checker   (checker/set-full)})
