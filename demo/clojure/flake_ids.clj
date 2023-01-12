#!/usr/bin/env bb

(load-file (clojure.string/replace *file* #"/[^/]+$" "/node.clj"))

(ns maelstrom.demo.flake-ids
  "A totally-available Flake ID generator for the unique-ids workload. Uses the
  local node clock, a counter, and the node ID."
  (:require [maelstrom.demo.node :as node]))

(def flake-state
  "An atom with both a last-generated time and a counter for IDs generated at
  that timestamp."
  (atom {:time  0
         :count 0}))

(node/defhandler generate
  [{:keys [body] :as req}]
  (let [; Intentionally use low precision here so that we stress the counter
        ; system
        time (long (/ (System/currentTimeMillis) 1000))
        {:keys [time count]} (swap! flake-state
                                    (fn [fs]
                                      (let [time  (max time (:time fs))
                                            count (if (= time (:time fs))
                                                    (inc (:count fs))
                                                    0)]
                                        {:time  time
                                         :count count})))]
    (node/reply! req
                 {:type :generate_ok
                  :id   [time count @node/node-id]})))

(node/start!)
