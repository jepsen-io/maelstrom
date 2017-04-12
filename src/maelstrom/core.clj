(ns maelstrom.core
  (:refer-clojure :exclude [run! test])
  (:require [clojure.tools.logging :refer [info warn]]
            [maelstrom [net :as net]
                       [process :as process]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [tests :as tests]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]
            [jepsen.control.util :as cu]

(defn raft-init!
  "Sends initial raft messages to all nodes."
  [net nodes timeout-ms]
  (let [clients (map net/sync-client! (repeat (count nodes) net))]
    (try
      ; Send messages
      (mapv (fn [c n]
              (net/sync-client-send!
                c
                {:dest (:node-id n)
                 :body {:type :raft_init
                        :node_id  (:node-id n)
                        :node_ids (map :node-id nodes)}}))
            clients
            nodes)

      ; Wait for responses
      (mapv (fn [c n]
              (let [msg (net/sync-client-recv! c timeout-ms)]
                (when (not= "raft_init_ok" (:type (:body msg)))
                  (throw (RuntimeException.
                           (str "Expected a raft_init_ok message, but node "
                                (:node-id n) " returned "
                                (pr-str msg)))))))
            clients
            nodes)

      (info "Raft initialization complete:" (map :node-id nodes))

      (finally
        (mapv net/sync-client-close! clients)))))

(defn db
  [net]
  (reify db/DB
    (setup! [_ test node]
      (info "Setting up" node))

    (teardown! [_ test node]
      (info "Tearing down" node))))


(defn test
  "Construct a Jepsen test. Options:

      :bin      Path to a binary to run
      :args     Arguments to that binary"
  [opts]
  (assoc tests/noop-test
         :name "maelstrom"
         :ssh  {:dummy? true}
         :db   (db)))

(defn run!
  [bin args]
  (let [net      (net/net)
        node-ids ["a" "b" "c"]
        nodes    (mapv (fn [node-id]
                         (process/start-node! {:node-id  node-id
                                               :bin      bin
                                               :args     args
                                               :net      net
                                               :dir      "/tmp"
                                               :log-file (str node-id ".log")}))
                       node-ids)]
    (raft-init! net nodes 10000)
    (mapv process/stop-node! nodes)))
