(ns maelstrom.net.checker
  "This checker uses net.journal and net.viz to generate statistics and
  visualizations from the Fressian journal files in a store dir."
  (:require [clojure.tools.logging :refer [info warn]]
            [fipp.edn :refer [pprint]]
            [jepsen [checker :as checker]
                    [store :as store]
                    [util :as util :refer [linear-time-nanos
                                           nanos->ms
                                           ms->nanos]]]
            [maelstrom.util :as u]
            [maelstrom.net [journal :as j]
                           [message :as msg]
                           [viz :as viz]]
            [tesser [core :as t]
                    [math :as tm]
                    [utils :as tu]])
  (:import (java.io Closeable
                    File
                    EOFException)
           (java.util BitSet)
           (maelstrom.net.message Message)
           (io.lacuna.bifurcan ISet
                               LinearSet
                               Maps
                               Set)))

(defn basic-stats
  "A fold for aggregate statistics over a journal."
  [journal]
  (->> journal
       (t/fuse {:send-count (t/count j/sends)
                :recv-count (t/count j/recvs)
                :msg-count  (->> (t/map (comp :id :message))
                                 ; (fast-cardinality))})))
                                 (j/dense-int-cardinality))})))

(def stats
  (t/fuse {:all     (->> (t/map identity) basic-stats)
           :clients (->> j/clients          basic-stats)
           :servers (->> j/servers          basic-stats)}))

(defn checker
  "A Jepsen checker which extracts the journal and analyzes its statistics."
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [; Fire off the plotter immediately; it can run without us
            plot (future (viz/plot-analemma! test))
            ; Compute stats
            stats   (->> stats
                         (j/tesser-journal test))
            ; Add msgs-per-op stats, so we can tell roughly how many messages
            ; exchanged per logical operation
            op-count (->> history
                          (remove (comp #{:nemesis} :process))
                          (filter (comp #{:invoke} :type))
                          count)
            stats (if (zero? op-count)
                    stats
                    (-> stats
                        (assoc-in [:all :msgs-per-op]
                                  (float (/ (:msg-count (:all stats))
                                            op-count)))
                        (assoc-in [:servers :msgs-per-op]
                                  (float (/ (:msg-count (:servers stats))
                                            op-count)))))]
        ; Block on plot
        @plot
        (assoc stats :valid? true)))))
