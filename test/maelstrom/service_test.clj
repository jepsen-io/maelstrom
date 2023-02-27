(ns maelstrom.service-test
  (:require [clojure [pprint :refer [pprint]]
                     [test :refer :all]]
            [maelstrom [service :as s]]))

(deftest seq-kv-test
  (let [buf         16
        write-count (/ buf 2)
        ; Prepares a mutable kv by writing 1, 2, 3, ..., write-count - 1
        prep (fn prep []
               (let [kv (s/sequential buf (s/persistent-kv))]
                 ; Set x to 1, then 2, leaving some states unfilled
                 (dotimes [i write-count]
                   (let [r (s/handle! kv {:src "c0" :body {:type  "write"
                                                           :key   :x
                                                  :value i}})]
                     (is (= "write_ok" (:type r)))))
                 kv))]
    (testing "fresh client reads return old state"
          (let [kv    (prep)
                reads (->> (range 64)
                           (map (fn [i]
                                  (-> (s/handle! kv {:src (str "old-read-" i)
                                                     :body {:type "read", :key :x}})
                                      :value)))
                           set)]
            (is (< 1 (count reads)))))

    (testing "ensuring recent read by writing something unique"
      (dotimes [i 8]
        (let [kv     (prep)
              client (str "ensure-" i)]
          (s/handle! kv {:src  client
                         :body {:type "write" :key :ensure :value i}})
          (let [r (s/handle! kv {:src client
                                 :body {:type "read", :key :x}})]
            (is (= (dec write-count) (:value r)))))))

    (testing "ensuring recent reads by reading a ton"
      (dotimes [i 8]
        (let [kv     (prep)
              target (dec write-count)
              tries  (* buf 10)]
          (loop [trial 1]
            (if (= trial tries)
              (is false) ; Never converged
              (let [r (s/handle! kv {:src "r", :body {:type "read", :key :x}})]
                ;(prn (:value r) target)
                (if (= target (:value r))
                  (do ;(println "Converged after" trial "attempts")
                      (is true))
                  (recur (inc trial)))))))))
    ))
