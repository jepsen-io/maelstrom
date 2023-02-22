(ns maelstrom.workload.lock
  "A distributed lock workload: sends lock and unlock messages."
  (:require [maelstrom [client :as c]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [schema.core :as s]))

(c/defrpc lock!
  "Clients send `lock` messages to servers. Servers should respond with
  `lock_ok` messages when the lock is granted."
  {:type (s/eq "lock")}
  {:type (s/eq "lock_ok")})

(c/defrpc unlock!
  "Clients send `unlock` messages to servers. Servers should respond with
  `unlock_ok` messages acking that the lock is released"
  {:type (s/eq "unlock")}
  {:type (s/eq "unlock_ok")})

(defn client
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (c/with-errors op #{}
         (let [rpc (case (:f op) :lock lock! :unlock unlock!)
               timeout (* 1000 (:time-limit test))] ; timeout at most last op from each client
           (rpc conn node {} timeout)
           (assoc op :type :ok))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn checker
  "Expects that a lock is only given when released by previous owner."
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [[_ errs]
            (reduce
              (fn [[acquired errors] op]
                (case [(:type op) (:f op)]
                  [:ok :lock]
                  (if acquired
                    [acquired (conj errors
                                   (str "already acquired by process " acquired
                                        " when lock completed at " op))]
                    [(:process op) errors])
                  [:invoke :unlock] ; lock can complete before unlock completes
                  (if (= acquired (:process op))
                    [nil errors]
                    [acquired (conj errors
                                   (str "lock not owned when unlock invoked at "
                                        op))])
                  [acquired errors]))
              [nil []]
              history)]
        {:valid? (empty? errs)
         :errors errs}))))

(defn workload
  "Constructs a workload for a distributed lock, given option from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  {:client          (client (:net opts))
   :generator       (gen/each-thread (cycle [{:f :lock} {:f :unlock}]))
   :checker   (checker)})

