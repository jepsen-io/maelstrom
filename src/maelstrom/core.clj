(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.tools.logging :refer [info warn]]
            [maelstrom [net :as net]
                       [process :as process]]
            [knossos.model :as model]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [client :as client]
                    [core :as core]
                    [control :as c]
                    [db :as db]
                    [generator :as gen]
                    [independent :as independent]
                    [nemesis :as nemesis]
                    [store :as store]
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

(def known-failure-codes
  #{1 10 11 20 21 22})

(defn error
  "Takes an invocation operation and a response message for a client request.
  If the response is an error, constructs an appropriate error operation.
  Otherwise, returns nil."
  [op msg]
  (let [body (:body msg)]
    (when (= "error" (:type body))
      (let [type (if (known-failure-codes (:code body))
                   :fail
                   :info)]
        (assoc op :type type, :error [(:code body) (:text body)])))))

(defn client
  "Construct a client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (net/sync-client! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (let [[k v]          (:value op)
             client-timeout (max (* 10 (:latency test)) 1000)]
         (case (:f op)
           :read (let [res (net/sync-client-send-recv!
                             conn
                             {:dest node
                              :body {:type "read", :key  k}}
                             client-timeout)
                       v (:value (:body res))]
                   (or (error op res)
                       (assoc op
                              :type :ok
                              :value (independent/tuple k v))))

           :write (let [res (net/sync-client-send-recv!
                              conn
                              {:dest node
                               :body {:type "write", :key k, :value v}}
                              client-timeout)]
                    (or (error op res)
                        (assoc op :type :ok)))

           :cas (let [[v v'] v
                      res (net/sync-client-send-recv!
                            conn
                            {:dest node
                             :body {:type "cas", :key k, :from v, :to v'}}
                            client-timeout)]
                  (or (error op res)
                      (assoc op :type :ok))))))

     (teardown! [_ test])

     (close! [_ test]
       (net/sync-client-close! conn)))))

(defn r   [_ _] {:type :invoke, :f :read, :value nil})
(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(defn test
  "Construct a Jepsen test. Options:

      :bin      Path to a binary to run
      :args     Arguments to that binary"
  [opts]
  (let [net   (net/net (:latency opts)
                       (:log-net-send opts)
                       (:log-net-recv opts))
        nodes (:nodes opts)]
    (merge tests/noop-test
           opts
           {:name    "maelstrom"
            :ssh     {:dummy? true}
            :db      (db {:net net
                          :bin (:bin opts)
                          :args []})
            :client  (client net)
            :nemesis (nemesis/partition-random-halves)
            :nodes   nodes
            :net     (net/jepsen-adapter net)
            :model   (model/cas-register)
            :checker (checker/compose
                       {:perf     (checker/perf)
                        :timeline (independent/checker (timeline/html))
                        :linear   (independent/checker (checker/linearizable))})
            :generator (->> (independent/concurrent-generator
                              (count nodes)
                              (range)
                              (fn [k]
                                (->> (gen/mix [r w cas])
                                     (gen/stagger (/ (:rate opts)))
                                     (gen/limit 100))))
                            (gen/nemesis
                              (when-not (:no-partitions opts)
                                (gen/seq (cycle [(gen/sleep 10)
                                                 {:type :info, :f :start}
                                                 (gen/sleep 10)
                                                 {:type :info, :f :stop}]))))
                            (gen/time-limit (:time-limit opts)))})))

(def opt-spec
  "Extra options for the CLI"
  [[nil "--bin FILE"        "Path to binary which runs a node"]

   [nil "--rate RATE" "Approximate number of request/sec/client"
    :default  1
    :parse-fn #(Double/parseDouble %)
    :validate [pos? "Must be positive"]]

   [nil "--log-net-send"    "Log packets as they're sent"
    :default false]

   [nil "--log-net-recv"    "Log packets as they're received"
    :default false]

   [nil "--log-stderr"      "Whether to log debugging output from nodes"
    :default false]

   [nil "--no-partitions"   "Let the network run normally"
    :default false]

   [nil "--latency MILLIS"  "Maximum (normal) network latency, in ms"
    :default 0
    :parse-fn #(Long/parseLong %)
    :validate [(complement neg?) "Must be non-negative"]]])

(defn opt-fn
  "Options validation"
  [parsed]
  (if-not (:bin (:options parsed))
    (update parsed :errors conj "Expected a --bin BINARY to test")
    parsed))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn test
                                         :opt-spec opt-spec
                                         :opt-fn opt-fn})
                   (cli/serve-cmd))
            args))
