(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [maelstrom [client :as c]
                       [db :as db]
                       [net :as net]
                       [nemesis :as nemesis]
                       [process :as process]]
            [maelstrom.net.journal :as net.journal]
            [maelstrom.workload [broadcast :as broadcast]
                                [echo :as echo]
                                [g-set :as g-set]
                                [pn-counter :as pn-counter]
                                [lin-kv :as lin-kv]]
            [jepsen [checker :as checker]
                    [cli :as cli]
                    [core :as core]
                    [generator :as gen]
                    [store :as store]
                    [tests :as tests]
                    [util :as util :refer [timeout parse-long]]]
            [jepsen.checker.timeline :as timeline]))

(def workloads
  "A map of workload names to functions which construct workload maps."
  {:broadcast   broadcast/workload
   :echo        echo/workload
   :g-set       g-set/workload
   :pn-counter  pn-counter/workload
   :lin-kv      lin-kv/workload})

(def nemeses
  "A set of valid nemeses you can pass at the CLI."
  #{:partition})

(defn maelstrom-test
  "Construct a Jepsen test from parsed CLI options"
  [{:keys [bin args nodes rate] :as opts}]
  (let [nodes (if-let [nc (:node-count opts)]
                (mapv (partial str "n") (map inc (range nc)))
                (:nodes opts))
        net   (net/net (:latency opts)
                       (:log-net-send opts)
                       (:log-net-recv opts))
        db            (db/db {:net net, :bin bin, :args args})
        workload-name (:workload opts)
        workload      ((workloads workload-name)
                       (assoc opts :nodes nodes, :net net))
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
            :nodes   nodes
            :ssh     {:dummy? true}
            :db      db
            :nemesis (:nemesis nemesis-package)
            :net     (net/jepsen-adapter net)
            :net-journal (:journal @net)
            :checker (checker/compose
                       {:perf       (checker/perf)
                        :timeline   (timeline/html)
                        :exceptions (checker/unhandled-exceptions)
                        :stats      (checker/stats)
                        :net        (net.journal/checker)
                        :workload   (:checker workload)})
            :generator generator
            :pure-generators true})))

(def demos
  "A series of partial test options used to run demonstrations."
  [{:workload :echo,        :bin "demo/ruby/echo.rb"}
   {:workload :echo,        :bin "demo/ruby/echo_full.rb"}
   {:workload :broadcast,   :bin "demo/ruby/broadcast.rb"}
   {:workload :g-set,       :bin "demo/ruby/g_set.rb"}
   {:workload :pn-counter,  :bin "demo/ruby/pn_counter.rb"}
   {:workload :lin-kv,      :bin "demo/ruby/raft.rb", :concurrency 10}])

(defn demo-tests
  "Takes CLI options and constructs a sequence of tests to run which
  demonstrate various demo programs on assorted workloads. Useful for
  self-tests."
  [opts]
  (for [demo demos]
    (maelstrom-test (merge opts demo))))

(def opt-spec
  "Extra options for the CLI"
  [[nil "--bin FILE"        "Path to binary which runs a node"]

   [nil "--latency MILLIS"  "Maximum (normal) network latency, in ms"
    :default 0
    :parse-fn parse-long
    :validate [(complement neg?) "Must be non-negative"]]

   [nil "--log-net-send"    "Log packets as they're sent"
    :default false]

   [nil "--log-net-recv"    "Log packets as they're received"
    :default false]

   [nil "--log-stderr"      "Whether to log debugging output from nodes"
    :default false]

   [nil "--node-count NUM" "How many nodes to run. Overrides --nodes, if given."
    :default nil
    :parse-fn parse-long
    :validate? [pos? "Must be positive."]]

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
                   (cli/serve-cmd)
                   ; This is basically a modified test-all command
                   {"demo" (get (cli/test-all-cmd
                                  {:opt-spec opt-spec
                                   :tests-fn demo-tests})
                                "test-all")}
                   {"doc" {:opt-spec []
                           :run (fn [opts]
                                  (c/print-registry))}})

            args))
