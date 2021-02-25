(ns maelstrom.workload.g-counter
  "An eventually-consistent grow-only counter, which supports increments.
  Validates that the final read on each node has a value which is the sum of
  all known (or possible) increments.

  See also: pn-counter, which is identical, but allows decrements."
  (:require [jepsen.generator :as gen]
            [schema.core :as s]
            [maelstrom [client :as c]]
            [maelstrom.workload.pn-counter :as pn-counter]))

; These are only here for docs, and actually don't get used--see their
; counterparts in pn-counter.
(c/defrpc add!
  "Adds a non-negative integer, called `delta`, to the counter. Servers should
  respond with an `add_ok` message."
  {:type  (s/eq "add")
   :delta s/Int}
  {:type  (s/eq "add_ok")})

(c/defrpc read
  "Reads the current value of the counter. Servers respond with a `read_ok`
  message containing a `value`, which should be the sum of all (known) added
  deltas."
  {:type (s/eq "read")}
  {:type (s/eq "read_ok")
   :value s/Int})

(defn workload
  "Constructs a workload for a grow-only counter, given options from the CLI
  test constructor:

  {:net     A Maelstrom network}"
  [opts]
  (update (pn-counter/workload opts)
          :generator
          (partial gen/filter (fn [op]
                                (not (and (= :add (:f op))
                                          (neg? (:value op))))))))
