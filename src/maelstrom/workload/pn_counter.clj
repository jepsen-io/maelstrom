(ns maelstrom.workload.pn-counter
  "An eventually-consistent counter which supports increments and decrements.
  Validates that the final read on each node has a value which is the sum of
  all known (or possible) increments and decrements.

  See also: g-counter, which is identical, but does not allow decrements."
  (:refer-clojure :exclude [read])
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]]
            [knossos.op :as op]
            [schema.core :as s]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (com.google.common.collect Range
                                      RangeSet
                                      TreeRangeSet)))

(c/defrpc add!
  "Adds a (potentially negative) integer, called `delta`, to the counter.
  Servers should respond with an `add_ok` message."
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

(defn client
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (case (:f op)
         :add (do (add! conn node {:delta (:value op)})
                  (assoc op :type :ok))

         :read (->> (read conn node {})
                    :value
                    long
                    (assoc op :type :ok, :value))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn)))))

(defn range->vec
  "Converts an open range into a closed integer [lower upper] pair."
  [^Range r]
  [(inc (.lowerEndpoint r))
   (dec (.upperEndpoint r))])

(defn acceptable->vecs
  "Turns an acceptable TreeRangeSet into a vector of [lower upper] inclusive
  ranges."
  [^TreeRangeSet s]
  (map range->vec (.asRanges s)))

(defn acceptable-range
  "Takes a lower and upper bound for a range and constructs a Range for an
  acceptable TreeRangeSet. The constructed range will be an *open* range from
  lower - 1 to upper + 1, which ensures that merges work correctly."
  [lower upper]
  (Range/open (dec lower) (inc upper)))

(defn checker
  "This checker verifies that every final read is the sum of all
  known-completed adds plus any number of possibly-completed adds. Returns a
  map with :valid? true if all reads marked :final? are in the acceptable set.
  Returns the acceptable set, encoded as a sequence of [lower upper] closed
  ranges."
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [; First, let's get all the add operations
            adds (filter (comp #{:add} :f) history)
            ; What's the total of the ops we *definitely* know happened?
            definite-sum (->> adds
                              (filter op/ok?)
                              (map :value)
                              (reduce +))
            ; What are all the possible outcomes of indeterminate ops?
            acceptable (TreeRangeSet/create)
            _ (.add acceptable (acceptable-range definite-sum definite-sum))
            ; For each possible add, we want to allow that either to happen or
            ; not.
            _ (doseq [add adds]
                (when (op/info? add)
                  (let [delta (:value add)]
                    ; For each range, add delta, and merge that back in. Note
                    ; we materialize asRanges to avoid iterating during our
                    ; mutation.
                    (doseq [^Range r (vec (.asRanges acceptable))]
                      (.add acceptable
                            (Range/open (+ (.lowerEndpoint r) delta)
                                        (+ (.upperEndpoint r) delta)))))))
            ; Now, extract the final reads for each node
            reads (->> history
                       (filter :final?)
                       (filter op/ok?))
            ; And find any problems
            errors (->> reads
                        (filter (fn [r]
                                  ; If we get a fractional read for some
                                  ; reason, our cute open-range technique is
                                  ; gonna give wrong answers
                                  (assert (integer? (:value r)))
                                  (not (.contains acceptable (:value r))))))]
        {:valid?      (empty? errors)
         :errors      (seq errors)
         :final-reads (map :value reads)
         :acceptable  (acceptable->vecs acceptable)}))))

(defn workload
  "Constructs a workload for a grow-only set, given options from the CLI
  test constructor:

      {:net     A Maelstrom network}"
  [opts]
  {:client          (client (:net opts))
   :generator       (gen/mix [(fn [] {:f :add, :value (- (rand-int 10) 5)})
                              (repeat {:f :read})])
   :final-generator (gen/each-thread {:f :read, :final? true})
   :checker         (checker)})
