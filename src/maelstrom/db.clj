(ns maelstrom.db
  "Shared functionality for starting database 'nodes'"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [db :as db]
                    [store :as store]]
            [maelstrom [client :as client]
                       [net :as net]
                       [process :as process]]
            [slingshot.slingshot :refer [try+ throw+]]))

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

        (let [client (client/open! net)]
          (try+
            (let [res (client/rpc!
                        client
                        node-id
                        {:type "init"
                         :node_id node-id
                         :node_ids (:nodes test)}
                        10000)]
              (when (not= "init_ok" (:type res))
                (throw+ {:type      :init-failed
                         :node      node-id
                         :response  res}
                        nil
                        (str "Expected an init_ok message, but node responded with "
                             (pr-str res)))))
            (catch [:type :maelstrom.client/timeout] e
              (throw+ {:type :init-failed
                       :node node-id}
                      (:throwable &throw-context)
                      (str "Expected node " node-id
                           " to respond to an init message, but node did not respond.")))
            (finally
              (client/close! client)))))

      (teardown! [_ test node]
        (when-let [p (get @processes node)]
          (info "Tearing down" node)
          (process/stop-node! p)
          (swap! processes dissoc node))))))
