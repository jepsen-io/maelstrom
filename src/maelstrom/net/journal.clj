(ns maelstrom.net.journal
  "A network's journal is a mutable store for keeping track of the history of
  network operations. We're not aiming for precision here--there's lag time
  between what the network sees and what nodes actually do--so we use
  jepsen.util/linear-time as our order for events.

  A journal is logically a sequence of events, each of which is a map like

  {:type      :send, :recv
   :time      An arbitrary linear timestamp in nanoseconds
   :message   The message exchanged}

  Because Maelstrom tests may generate a LOT of messages, these events are
  journaled to disk incrementally, rather than stored entirely in-memory.
  They're written to `net-messages.fressian` as a series of Fressian objects.

  To coordinate writes, we spawn a single worker thread which accepts events
  via a queue, and writes them sequentially to the journal file.

  This namespace also has a checker which can analyze the journal to see how
  many messages were exchanged, generate statistics, produce lamport diagrams,
  etc."
  (:require [clojure.tools.logging :refer [info warn]]
            [clojure.data.fressian :as fress]
            [clojure.java.io :as io]
            [fipp.edn :refer [pprint]]
            [jepsen [checker :as checker]
                    [store :as store]
                    [util :as util :refer [linear-time-nanos]]]
            [maelstrom.util :as u]
            [maelstrom.net.viz :as viz]
            [tesser [core :as t]
                    [math :as tm]
                    [utils :as tu]])
  (:import (java.io EOFException)
           (java.util.concurrent LinkedBlockingQueue)
           (org.fressian FressianWriter FressianReader)
           (org.fressian.handlers WriteHandler ReadHandler)))

; We're going to doing a LOT of event manipulation, so speed matters.
(defrecord Event [type ^long time message])

(def write-handlers
  "How should Fressian write different classes?"
  (-> {}
      (merge fress/clojure-write-handlers)
      fress/associative-lookup
      fress/inheritance-lookup))

(def read-handlers
  "How should Fressian read different tags?"
  (-> {}
      (merge fress/clojure-read-handlers)
      fress/associative-lookup))

(defn file
  "What file do we use for storing the journal?"
  [test]
  (store/path test "net-journal.fressian"))

(defn ^FressianWriter disk-writer
  "Constructs a new Fressian Writer for a test's journal."
  [test]
  (-> (file test)
      io/output-stream
      (fress/create-writer :handlers write-handlers)))

(defn ^FressianReader disk-reader
  "Constructs a new Fressian Reader for a test's journal."
  [test]
  (-> (file test)
      io/input-stream
      (fress/create-reader :handlers read-handlers)))

(defn reader-seq
  "Constructs a lazy sequence of journal operations from a Fressian reader."
  [reader]
  (lazy-seq (try (cons (fress/read-object reader)
                       (reader-seq reader))
                 (catch EOFException e
                   nil))))

(defn worker
  "Spawns a thread to write network ops to disk."
  [^LinkedBlockingQueue queue writer]
  (future
    (util/with-thread-name "maelstrom net journal"
      (loop []
        (let [event (.take queue)]
          (if (= event :done)
            (do (.close ^FressianWriter writer)
                :done)
            (do (fress/write-object writer event)
                (recur))))))))

(defn journal
  "Constructs a new journal, including a worker thread to journal network
  events to disk."
  [test]
  (let [writer   (disk-writer test)
        queue    (LinkedBlockingQueue. 16384)]
    {:queue  queue
     :worker (worker queue writer)}))

(defn close!
  "Closes a journal."
  [journal]
  (.put (:queue journal) :done)
  ; Wait for worker to complete queue
  @(:worker journal))

(defn log-event!
  "Logs an arbitrary event to a journal."
  [journal event]
  (.put ^LinkedBlockingQueue (:queue journal) event))

(defn log-send!
  "Logs a send operation"
  [journal message]
  (log-event! journal {:type   :send
                       :time   (linear-time-nanos)
                       :message message}))

(defn log-recv!
  "Logs a receive operation"
  [journal message]
  (log-event! journal {:type    :recv
                       :time    (linear-time-nanos)
                       :message message}))

(defn involves-client?
  "Takes an event and returns true iff it was sent to or received from a
  client."
  [{:keys [message]}]
  (u/involves-client? message))

(defn without-init
  "Strips out initialization messages from the journal, so we can focus on the
  algorithm itself."
  [journal]
  (->> journal
       (remove (fn [event]
                 (contains? #{"init" "init_ok"}
                            (:type (:body (:message event))))))))

;; Analysis

(def sends
  "Fold which filters a journal to just sends."
  (t/filter (comp #{:send} :type)))

(def recvs
  "Fold which filters a journal to just receives."
  (t/filter (comp #{:recv} :type)))

(def clients
  "Fold which filters a journal to just messages to/from clients"
  (t/filter involves-client?))

(def servers
  "Fold which filters a journal to just messages between servers."
  (t/remove involves-client?))

(defn basic-stats
  "A fold for aggregate statistics over a journal."
  [journal]
  (->> journal
       (t/fuse {:send-count (t/count sends)
                :recv-count (t/count recvs)
                :msg-count  (->> (t/map (comp :id :message))
                                 (t/set)
                                 (t/post-combine count))})))

(def stats
  (t/fuse {:all     (->> (t/map identity) basic-stats)
           :clients (->> clients          basic-stats)
           :servers (->> servers          basic-stats)}))

(defn checker
  "A Jepsen checker which extracts the journal and analyzes its statistics."
  []
  (reify checker/Checker
    (check [this test history opts]
      (let [journal (with-open [r (disk-reader test)]
                      (vec (reader-seq r)))
            ;_ (info :journal (with-out-str (pprint journal)))
            stats   (t/tesser (tu/chunk-vec 65536 journal) stats)
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

        ; Generate a plot
        (viz/plot-analemma! (without-init journal)
                            (store/path! test "messages.svg"))
        {:stats  stats
         :valid? true}))))
