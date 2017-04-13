(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.tools.logging :refer [info warn]]
            [maelstrom [net :as net]
                       [process :as process]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [core :as core]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]))

(defn db
  "Options:

      :bin - a binary to run
      :args - args to that binary
      :net - a network"
  [opts]
  (let [net (:net opts)
        processes (atom {})]
    (reify db/DB
      (setup! [_ test node-id]
        (info "Setting up" node-id)
        (swap! processes assoc node-id
               (process/start-node! {:node-id  node-id
                                     :bin      (:bin opts)
                                     :args     (:args opts)
                                     :net      net
                                     :dir      "/tmp"
                                     :log-file (str node-id ".log")}))

        (let [client (net/sync-client! net)]
          (try
            (let [res (net/sync-client-send-recv!
                        client
                        {:dest node-id
                         :body {:type "raft_init"
                                :node_id node-id
                                :node_ids (:nodes test)}}
                        10000)]
              (when (not= "raft_init_ok" (:type (:body res)))
                (throw (RuntimeException.
                         (str "Expected a raft_init_ok message, but node "
                              node-id " returned "
                              (pr-str res))))))
            (finally (net/sync-client-close! client)))))


      (teardown! [_ test node]
        (when-let [p (get @processes node)]
          (info "Tearing down" node)
          (process/stop-node! p)
          (swap! processes dissoc node))))))

(defn test
  "Construct a Jepsen test. Options:

      :bin      Path to a binary to run
      :args     Arguments to that binary"
  [opts]
  (let [net (net/net)]
    (merge tests/noop-test
           opts
           {:name "maelstrom"
            :ssh  {:dummy? true}
            :db   (db {:net net
                       :bin "demo.rb"
                       :args ["hi"]})
            :nodes ["a" "b" "c"]})))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn test})
                   (cli/serve-cmd))
            args))
