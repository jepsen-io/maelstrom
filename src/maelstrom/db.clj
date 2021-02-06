(ns maelstrom.db
  "Shared functionality for starting database 'nodes'"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [db :as db]
                    [store :as store]]
            [maelstrom [net :as net]
                       [process :as process]]))

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
                                     :log-stderr? (:log-stderr test)
                                     :log-file (->> (str node-id ".log")
                                                    (store/path test)
                                                    .getCanonicalPath)}))

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
