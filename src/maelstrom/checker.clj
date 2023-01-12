(ns maelstrom.checker
  "Common Maelstrom checkers"
  (:require [jepsen [checker :as checker]
                    [history :as h]]))

(defn availability-checker
  "Checks to see whether the history met the test's target :availability goals.
  There are several possible values for availability:

    0  nil, which imposes no availability checks
    1. :total, which means every request must succeed.
    2. A number between 0 and 1, which is a lower bound on the fraction of
       operations that must succeed."
  []
  (reify checker/Checker
    (check [_ test history opts]
      (let [a            (:availability test)
            ok-count     (count (h/oks history))
            invoke-count (count (h/invokes history))
            res          {:valid?       true
                          ;:invoke-count invoke-count
                          ;:ok-count     ok-count
                          :ok-fraction  (if (zero? invoke-count)
                                          1
                                          (float (/ ok-count invoke-count)))}]
        (cond
          (nil? a)
          res

          (= :total a)
          (assoc res :valid? (== 1 (:ok-fraction res)))

          (number? a)
          (assoc res :valid? (<= a (:ok-fraction res)))

          true
          (throw (IllegalArgumentException.
                   (str "Don't know how to handle :availability "
                        (pr-str a)))))))))
