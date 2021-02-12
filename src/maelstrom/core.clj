(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [maelstrom [db :as db]
                       [net :as net]
                       [nemesis :as nemesis]
                       [process :as process]]
            [maelstrom.workload [echo :as echo]
                                [g-set :as g-set]
                                [pn-counter :as pn-counter]
                                [lin-kv :as lin-kv]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [core :as core]
                    [generator :as gen]
                    [store :as store]
                    [tests :as tests]
                    [util :as util :refer [timeout]]]
            [jepsen.checker.timeline :as timeline]))

(def workloads
  "A map of workload names to functions which construct workload maps."
  {:echo        echo/workload
   :g-set       g-set/workload
   :pn-counter  pn-counter/workload
   :lin-kv      lin-kv/workload})

(def nemeses
  "A set of valid nemeses you can pass at the CLI."
  #{:partition})

(defn maelstrom-test
  "Construct a Jepsen test from parsed CLI options"
  [{:keys [bin args nodes rate] :as opts}]
  (let [net   (net/net (:latency opts)
                       (:log-net-send opts)
                       (:log-net-recv opts))
        db            (db/db {:net net, :bin bin, :args args})
        workload-name (:workload opts)
        workload      ((workloads workload-name) (assoc opts :net net))
        nemesis-package (nemesis/package {:db       db
                                          :interval (:nemesis-interval opts)
                                          :faults   (:nemesis opts)})
        generator (->> (when (pos? rate)
                         (gen/stagger (/ rate) (:generator workload)))
                       (gen/nemesis (:generator nemesis-package))
                       (gen/time-limit (:time-limit opts)))
        ; If this workload has a final generator, end the nemesis, wait for
        ; recovery, and perform final ops.
        generator (if-let [final (:final-generator workload)]
                    (gen/phases generator
                                (gen/nemesis (:final-generator nemesis-package))
                                (gen/log "Waiting for recovery...")
                                (gen/sleep 10)
                                (gen/clients final))
                    generator)]
    (merge tests/noop-test
           opts
           workload
           {:name    (str (name workload-name))
            :ssh     {:dummy? true}
            :db      db
            :nemesis (:nemesis nemesis-package)
            :net     (net/jepsen-adapter net)
            :checker (checker/compose
                       {:perf       (checker/perf)
                        :timeline   (timeline/html)
                        :exceptions (checker/unhandled-exceptions)
                        :stats      (checker/stats)
                        :workload   (:checker workload)})
            :generator generator
            :pure-generators true})))

(def opt-spec
  "Extra options for the CLI"
  [[nil "--bin FILE"        "Path to binary which runs a node"]

   [nil "--latency MILLIS"  "Maximum (normal) network latency, in ms"
    :default 0
    :parse-fn #(Long/parseLong %)
    :validate [(complement neg?) "Must be non-negative"]]


   [nil "--log-net-send"    "Log packets as they're sent"
    :default false]

   [nil "--log-net-recv"    "Log packets as they're received"
    :default false]

   [nil "--log-stderr"      "Whether to log debugging output from nodes"
    :default false]

   [nil "--nemesis FAULTS" "A comma-separated list of faults to inject."
    :default #{}
    :parse-fn (fn [string]
                (->> (str/split string #"\s*,\s*")
                     (map keyword)
                     set))
    :validate [(partial every? nemeses) (cli/one-of nemeses)]]

   [nil "--nemesis-interval SECONDS" "How many seconds between nemesis operations, on average?"
    :default  10
    :parse-fn read-string
    :validate [pos? "Must be positive"]]

   [nil "--rate RATE" "Approximate number of request/sec/client"
    :default  1
    :parse-fn #(Double/parseDouble %)
    :validate [(complement neg?) "Can't be negative"]]

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
