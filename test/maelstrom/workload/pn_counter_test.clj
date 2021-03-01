(ns maelstrom.workload.pn-counter-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [jepsen.checker :as checker]
            [maelstrom.workload.pn-counter :refer :all]))

(let [check (fn [history] (checker/check (checker) {} history {}))]
  (deftest checker-test
    (testing "empty"
      (is (= {:valid?     true
              :errors     nil
              :final-reads []
              :acceptable [[0 0]]}
             (check []))))

    (testing "definite"
      (is (= {:valid?     false
              :errors     [{:type :ok, :f :read, :final? true, :value 4}]
              :final-reads [5 4]
              :acceptable [[5 5]]}
             (check [{:type :ok, :f :add, :value 2}
                     {:type :ok, :f :add, :value 3}
                     {:type :ok, :f :read, :final? true, :value 5}
                     {:type :ok, :f :read, :final? true, :value 4}]))))

    (testing "indefinite"
      (is (= {:valid?     false
              :errors     [{:type :ok, :f :read, :final? true, :value 11}]
              :final-reads [11 15]
              :acceptable [[8 10] [13 15]]}
             (check [{:type :ok,   :f :add, :value 10}
                     {:type :info, :f :add, :value 5}
                     {:type :info, :f :add, :value -1}
                     {:type :info, :f :add, :value -1}
                     {:type :ok, :f :read, :final? true, :value 11}
                     {:type :ok, :f :read, :final? true, :value 15}]))))))

