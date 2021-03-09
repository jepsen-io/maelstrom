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
                    [util :as util :refer [linear-time-nanos
                                           nanos->ms
                                           ms->nanos]]]
            [maelstrom.util :as u]
            [maelstrom.net [message :as msg]
                           [viz :as viz]]
            [tesser [core :as t]
                    [math :as tm]
                    [utils :as tu]])
  (:import (java.io EOFException)
           (java.util BitSet)
           (java.util.concurrent ArrayBlockingQueue
                                 BlockingQueue
                                 LinkedBlockingQueue)
           (java.util.concurrent.locks LockSupport)
           (org.fressian FressianWriter FressianReader)
           (org.fressian.handlers WriteHandler ReadHandler)
           (io.lacuna.bifurcan Set LinearSet ISet)))

; We're going to doing a LOT of event manipulation, so speed matters.
(defrecord Event [type ^long time message])

(defn write-body!
  "We burn a huge amount of time in interning keywords during body
  deserialization. To avoid this, we define a custom Fressian writer which
  caches all keys in bodies, as well as message :types, which we know recur
  constantly."
  [^FressianWriter w m]
  (.writeTag w "map" 1)
  (.beginClosedList w)
  (reduce-kv (fn [^FressianWriter w k v]
               (.writeObject w k true)
               (.writeObject w v (= k :type)))
             w
             m)
  (.endList w))

(def write-handlers
  "How should Fressian write different classes?"
  (-> {maelstrom.net.journal.Event
       {"ev" (reify WriteHandler
               (write [_ w e]
                 (.writeTag w "ev" 3)
                 (.writeObject  w (:type e))
                 (.writeInt     w (:time e))
                 (.writeObject  w (:message e))))}

       maelstrom.net.message.Message
       {"msg" (reify WriteHandler
               (write [_ w m]
                 (.writeTag     w "msg" 4)
                 (.writeInt     w (:id m))
                 (.writeString  w (:src m))
                 (.writeString  w (:dest m))
                 (write-body!   w (:body m))
                 ; (.writeObject w nil))
                 ))}}

      (merge fress/clojure-write-handlers)
      fress/associative-lookup
      fress/inheritance-lookup))

(def read-handlers
  "How should Fressian read different tags?"
  (-> {"ev" (reify ReadHandler
              (read [_ r tag component-count]
                (assert (= 3 component-count))
                (Event. (.readObject r)
                        (.readInt r)
                        (.readObject r))))

       "msg" (reify ReadHandler
               (read [_ r tag component-count]
                 (assert (= 4 component-count))
                 (maelstrom.net.message.Message.
                   (.readInt r)
                   (.readObject r)
                   (.readObject r)
                   (.readObject r))))}

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
  [^BlockingQueue queue writer]
  (future
    (util/with-thread-name "maelstrom net journal"
      (try
        (loop []
          (let [event (.take queue)]
            (if (= event :done)
              (do (.close ^FressianWriter writer)
                  :done)
              (do (fress/write-object writer event)
                  (recur)))))
        (catch InterruptedException e
          ; Normal shutdown
          :interrupted)
        (catch Throwable t
          ; Log so we know what's going on, because this is going to stall the
          ; rest of the test
          (warn t "Error in net journal worker")
          (throw t))))))

(defn journal
  "Constructs a new journal, including a worker thread to journal network
  events to disk."
  [test]
  (let [writer     (disk-writer test)
        queue-size 32
        queue      (ArrayBlockingQueue. queue-size)]
    {:queue-size queue-size
     :queue      queue
     :worker     (worker queue writer)}))

(defn close!
  "Closes a journal."
  [journal]
  (.put (:queue journal) :done)
  ; Wait for worker to complete queue
  @(:worker journal))

(defn log-event!
  "Logs an arbitrary event to a journal."
  [journal event]
  (let [; t0                    (System/nanoTime)
        queue ^BlockingQueue  (:queue journal)
        queue-size            (:queue-size journal)
        used-frac             (/ (double (.size queue)) queue-size)
        ; Compute an exponential backoff so we don't slam into the top of the
        ; queue; we want to smoothly stabilize at the rate the writer can keep
        ; up with. This is a totally eyeballed, hardcoded curve which is
        ; probably bad, but I'm hoping it's better than nothing.
        ;
        ; (->> (range 0 1.1 1/10)
        ;      (map (fn [f] (-> f (- 1/2) (->> (Math/pow 100 )) (- 1) (* 10) int (max 0))))
        ;      pprint)
        ; (0 0 0 0 0 0 5 15 29 53 90)
        backoff (-> used-frac
                    (- 0.5)
                    (->> (Math/pow 100))
                    (- 1)
                    (* 10)
                    (max 0))
        ; t1 (System/nanoTime)
        _ (when (pos? backoff)
            (LockSupport/parkNanos (util/ms->nanos backoff)))
        ; t2 (System/nanoTime)
        _ (.put ^BlockingQueue (:queue journal) event)
        ; t3 (System/nanoTime)
        ]
;    (when (< 100 (nanos->ms (- t3 t0)))
;      (info (float (nanos->ms (- t1 t0))) "ms to figure out backoff"
;            (float (nanos->ms (- t2 t1))) "ms sleeping"
;            (float (nanos->ms (- t3 t2))) "ms enqueuing")))
      ))

(defn log-send!
  "Logs a send operation"
  [journal message]
  (log-event! journal (Event. :send (linear-time-nanos) message)))

(defn log-recv!
  "Logs a receive operation"
  [journal message]
  (log-event! journal (Event. :recv (linear-time-nanos) message)))

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

(defn bitset
  "Constructs a new BitSet"
  []
  (BitSet.))

(t/deftransform dense-int-cardinality
  "A cardinality fold which assumes values are densely packed integers, and
  uses a bitset to store them."
  []
  (assert (nil? downstream))
  {:reducer-identity    bitset
   :reducer             (fn reducer [^BitSet s i] (doto s (.set i)))
   :post-reducer        identity
   :combiner-identity   bitset
   :combiner            (fn combiner [^BitSet a, ^BitSet b] (doto a (.or b)))
   :post-combiner       (fn post-combiner [^BitSet s] (.cardinality s))})

(defn linear-set
  "Constructs a new mutable Bifurcan set"
  []
  (LinearSet.))

(t/deftransform fast-cardinality
  "A faster cardinality fold which uses a Bifurcan set"
  []
  (assert (nil? downstream))
  {:reducer-identity  linear-set
   :reducer           (fn reducer [^LinearSet s x] (.add s x))
   :post-reducer      identity
   :combiner-identity linear-set
   :combiner          (fn combiner [^LinearSet a ^LinearSet b]
                        (.union a b))
   :post-combiner     (fn post-combiner [^LinearSet s]
                        (.size s))})

(def sends
  "Fold which filters a journal to just sends."
  (t/filter (fn send? [event] (= :send (:type event)))))

(def recvs
  "Fold which filters a journal to just receives."
  (t/filter (fn recv? [event] (= :recv (:type event)))))

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
                                 (dense-int-cardinality))})))

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
            ;_ (info :journal (with-out-str (pprint (take 10 journal))))
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
