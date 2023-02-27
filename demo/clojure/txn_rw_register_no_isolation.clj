#!/usr/bin/env bb

(load-file (clojure.string/replace *file* #"/[^/]+$" "/node.clj"))

(ns maelstrom.demo.txn-rw-register-no-isolation
  "A single-node, completely un-isolated rw-register transaction system. Useful
  for demonstrating safety violations."
  (:require [maelstrom.demo.node :as node]))

(def state
  "An atom of a map of keys to values."
  (atom {}))

(defn apply-txn!
  "Takes a state and a txn. Applies the txn to the state without any isolation,
  mutating the state and returning txn'. Deliberately introduces sleep
  statements to increase the chances of interleaving with other txns."
  [txn]
  ; Zip through the txn, applying each mop
  (reduce (fn [txn' [f k v :as mop]]
            (Thread/sleep 1)
            (case f
              "r" (conj txn' [f k (get @state k)])
              "w" (do (swap! state assoc k v)
                      (conj txn' mop))))
          []
          txn))

(node/defhandler txn
  "When a transaction arrives, apply it to the local state."
  [{:keys [body] :as req}]
  (node/reply! req {:type "txn_ok"
                    :txn (apply-txn! (:txn body))}))

(node/start!)
