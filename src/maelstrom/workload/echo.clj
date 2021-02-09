(ns maelstrom.workload.echo
  "A simple echo workload: sends a message, and expects to get that same
  message back."
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.tests.linearizable-register :as lin-reg]
            [knossos.history :as history]
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
       (try+ (let [res (c/rpc! conn node {:type :echo, :echo (:value op)})]
               (assoc op :type :ok, :value res))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn checker
  "Expects responses to every echo operation to match the invocation's value."
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [pairs (history/pair-index history)
            errs  (keep (fn [[invoke complete]]
                          (cond ; Only take invoke/complete pairs
                                (not= (:type invoke) :invoke)
                                nil

                                (not= "echo" (:type (:value complete)))
                                ["Expected a message with :type \"echo\", but received"
                                 (:value complete)]

                                (not= (:value invoke)
                                      (:echo (:value complete)))
                                ["Expected a message with :echo"
                                 (:value invoke)
                                 "But received"
                                 (:value complete)]))
                          pairs)]
        {:valid? (empty? errs)
         :errors errs}))))

(defn workload
  "Constructs a workload for linearizable registers, given option from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  {:client    (client (:net opts))
   :generator (->> (fn []
                     {:f      :echo
                      :value  (str "Please echo " (rand-int 128))})
                   (gen/each-thread))
   :checker   (checker)})
