(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.tools.logging :refer [info warn]]
            [maelstrom [db :as db]
                       [net :as net]
                       [process :as process]]
            [maelstrom.workload [echo :as echo]
                                [g-set :as g-set]
                                [lin-kv :as lin-kv]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [core :as core]
                    [generator :as gen]
                    [nemesis :as nemesis]
                    [store :as store]
                    [tests :as tests]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]))

(def workloads
  "A map of workload names to functions which construct workload maps."
  {:echo    echo/workload
   :g-set   g-set/workload
   :lin-kv  lin-kv/workload})

(defn maelstrom-test
  "Construct a Jepsen test from parsed CLI options"
  [{:keys [bin args nodes rate] :as opts}]
  (let [net   (net/net (:latency opts)
                       (:log-net-send opts)
                       (:log-net-recv opts))
        db            (db/db {:net net, :bin bin, :args args})
        workload-name (:workload opts)
        workload      ((workloads workload-name) (assoc opts :net net))
        ]
    (merge tests/noop-test
           opts
           workload
           {:name    (str (name workload-name))
            :ssh     {:dummy? true}
            :db      db
            :nemesis (nemesis/partition-random-halves)
            :net     (net/jepsen-adapter net)
            :checker (checker/compose
                       {:perf       (checker/perf)
                        :exceptions (checker/unhandled-exceptions)
                        :stats      (checker/stats)
                        :workload   (:checker workload)})
            :pure-generators true
            :generator (->> (when (pos? rate)
                              (->> (:generator workload)
                                   (gen/stagger (/ rate))))
                            (gen/nemesis
                              (when-not (:no-partitions opts)
                                (cycle [(gen/sleep 10)
                                        {:type :info, :f :start}
                                        (gen/sleep 10)
                                        {:type :info, :f :stop}])))
                            (gen/time-limit (:time-limit opts)))})))

(def opt-spec
  "Extra options for the CLI"
  [[nil "--bin FILE"        "Path to binary which runs a node"]

   [nil "--rate RATE" "Approximate number of request/sec/client"
    :default  1
    :parse-fn #(Double/parseDouble %)
    :validate [(complement neg?) "Can't be negative"]]

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
    :validate [(complement neg?) "Must be non-negative"]]

   ["-w" "--workload NAME" "What workload to run."
    :default "lin-kv"
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]
   ])

(defn opt-fn
  "Options validation"
  [parsed]
  (if-not (:bin (:options parsed))
    (update parsed :errors conj "Expected a --bin BINARY to test")
    parsed))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn   maelstrom-test
                                         :opt-spec  opt-spec
                                         :opt-fn    opt-fn})
                   (cli/serve-cmd))
            args))
