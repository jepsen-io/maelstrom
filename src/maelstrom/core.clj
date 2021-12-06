(ns maelstrom.core
  (:gen-class)
  (:refer-clojure :exclude [run! test])
  (:require [clojure.string :as str]
            [clojure.tools.logging :refer [info warn]]
            [elle.consistency-model :as cm]
            [maelstrom [client :as c]
                       [db :as db]
                       [doc :as doc]
                       [net :as net]
                       [nemesis :as nemesis]
                       [process :as process]]
            [maelstrom.net.checker :as net.checker]
            [maelstrom.workload [broadcast :as broadcast]
                                [echo :as echo]
                                [g-set :as g-set]
                                [g-counter :as g-counter]
                                [pn-counter :as pn-counter]
                                [lin-kv :as lin-kv]
                                [txn-list-append :as txn-list-append]]
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
  {:broadcast       broadcast/workload
   :echo            echo/workload
   :g-set           g-set/workload
   :g-counter       g-counter/workload
   :pn-counter      pn-counter/workload
   :lin-kv          lin-kv/workload
   :txn-list-append txn-list-append/workload})

(def nemeses
  "A set of valid nemeses you can pass at the CLI."
  #{:partition})

(defn maelstrom-test
  "Construct a Jepsen test from parsed CLI options"
  [{:keys [bin args nodes rate] :as opts}]
  (let [nodes (:nodes opts)
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
        generator (->> (if (pos? rate)
                         (gen/stagger (/ rate) (:generator workload))
                         (gen/sleep (:time-limit opts)))
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
            :os      (net/jepsen-os net)
            :net     (net/jepsen-net net)
            :db      db
            :nemesis (:nemesis nemesis-package)
            :checker (checker/compose
                       {:perf       (checker/perf
                                      {:nemeses (:perf nemesis-package)})
                        :timeline   (timeline/html)
                        :exceptions (checker/unhandled-exceptions)
                        :stats      (checker/stats)
                        :net        (net.checker/checker)
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
   {:workload :lin-kv,      :bin "demo/ruby/raft.rb", :concurrency 10}
   {:workload :lin-kv       :bin "demo/ruby/lin_kv_proxy.rb", :concurrency 10}
   {:workload :txn-list-append
    :bin      "demo/ruby/datomic_list_append.rb"}])

(defn demo-tests
  "Takes CLI options and constructs a sequence of tests to run which
  demonstrate various demo programs on assorted workloads. Useful for
  self-tests."
  [opts]
  (for [demo demos]
    (maelstrom-test (merge opts demo))))

(def test-opt-spec
  "Options for single tests."
  [[nil "--bin FILE"        "Path to binary which runs a node"
    :missing "Expected a --bin PATH_TO_BINARY to test"]

   ["-w" "--workload NAME" "What workload to run."
    :default "lin-kv"
    :parse-fn keyword
    :validate [workloads (cli/one-of workloads)]]
   ])

(def opt-spec
  "Extra options for the CLI"
  [[nil "--consistency-models MODELS" "A comma-separated list of consistency models to check."
    :default [:strict-serializable]
    :parse-fn (fn [s]
                (map keyword (str/split s #"\s+,\s+")))
    :validate [(partial every? cm/friendly-model-name)
               (cli/one-of (sort (map cm/friendly-model-name cm/all-models)))]]

   [nil "--key-count INT" "For the append test, how many keys should we test at once?"
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--latency MILLIS"  "Mean network latency under normal operation, in ms"
    :default 0
    :parse-fn parse-long
    :validate [(complement neg?) "Must be non-negative"]]

   [nil "--latency-dist TYPE" "Kind of latency distribution to use."
    :default :constant
    :parse-fn keyword
    :validate [#{:constant :uniform :exponential}
               "Must be constant, uniform, or exponential"]]

   [nil "--log-net-send"    "Log packets as they're sent"
    :default false]

   [nil "--log-net-recv"    "Log packets as they're received"
    :default false]

   [nil "--log-stderr"      "Whether to log debugging output from nodes"
    :default false]

   [nil "--max-txn-length INT" "What's the most operations we can execute per transaction?"
    :default  4
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--max-writes-per-key INT" "How many writes can we perform to any single key, for append tests?"
    :default  16
    :parse-fn parse-long
    :validate [pos? "must be positive"]]

   [nil "--node-count NUM" "How many nodes to run. Overrides --nodes, if given."
    :default nil
    :parse-fn parse-long
    :validate [pos? "Must be positive."]]

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

   [nil "--rate RATE" "Approximate number of request/sec"
    :default  5
    :parse-fn #(Double/parseDouble %)
    :validate [(complement neg?) "Can't be negative"]]

   [nil "--topology SPEC" "What kind of network topology to offer to nodes, for those workloads (e.g. broadcast) which use one."
    :parse-fn keyword
    :default :grid
    :validate [broadcast/topologies (cli/one-of broadcast/topologies)]]

   ])

(defn parse-node-count
  "Takes the node-count option and generates a :nodes list in the parsed option
  map, overriding whatever nodes are already there."
  [parsed]
  (if-let [n (:node-count (:options parsed))]
    (assoc-in parsed [:options :nodes]
              (mapv (partial str "n") (range n)))
    parsed))

(defn parse-latency
  "Moves latency and latency-dist into their own :latency map."
  [parsed]
  (let [o (:options parsed)]
    (assoc parsed :options
           (-> o
               (assoc :latency {:mean (:latency o)
                                :dist (:latency-dist o)})
               (dissoc :latency-dist)))))

(defn add-args
  "Adds non-option arguments as :args into parsed options map. :args value is
  used as list of arguments for the binary which runs a node."
  [parsed]
  (let [a (:arguments parsed)
        o (:options parsed)]
    (assoc parsed :options (assoc o :args a))))

(defn opt-fn
  "Post-processes the parsed CLI options structure."
  [parsed]
  (-> parsed
      parse-latency
      parse-node-count
      add-args
      cli/test-opt-fn))

(defn -main
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn   maelstrom-test
                                         :opt-spec  (concat test-opt-spec
                                                            opt-spec)
                                         :opt-fn*   opt-fn})
                   (cli/serve-cmd)
                   ; This is basically a modified test-all command
                   {"demo" (get (cli/test-all-cmd
                                  {:opt-spec opt-spec
                                   :opt-fn*  opt-fn
                                   :tests-fn demo-tests})
                                "test-all")}
                   {"doc" {:opt-spec []
                           :run (fn [opts]
                                  (doc/write-docs!))}})

            args))
