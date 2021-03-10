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
            [jepsen [store :as store]
                    [util :as util :refer [linear-time-nanos
                                           nanos->ms
                                           ms->nanos]]]
            [maelstrom.util :as u]
            [maelstrom.net [message :as msg]]
            [tesser [core :as t]
                    [math :as tm]
                    [utils :as tu]])
  (:import (java.io Closeable
                    File
                    EOFException)
           (java.util BitSet)
           (java.util.concurrent ArrayBlockingQueue
                                 BlockingQueue
                                 LinkedBlockingQueue)
           (java.util.concurrent.locks LockSupport)
           (maelstrom.net.message Message)
           (org.fressian FressianWriter FressianReader)
           (org.fressian.handlers WriteHandler ReadHandler)
           (io.lacuna.bifurcan ISet
                               LinearSet
                               Maps
                               Set)))

; We're going to doing a LOT of event manipulation, so speed matters.
(defrecord Event [^long id ^long time type message])

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
                 (.writeTag     w "ev" 4)
                 (.writeInt     w (:id e))
                 (.writeInt     w (:time e))
                 (.writeObject  w (:type e) true)
                 (.writeObject  w (:message e))))}

       maelstrom.net.message.Message
       {"msg" (reify WriteHandler
               (write [_ w m]
                 (.writeTag     w "msg" 4)
                 (.writeInt     w (:id m))
                 (.writeObject  w (:src m) true)
                 (.writeObject  w (:dest m) true)
                 (write-body!   w (:body m))))}}

      (merge fress/clojure-write-handlers)
      fress/associative-lookup
      fress/inheritance-lookup))

(def read-handlers
  "How should Fressian read different tags?"
  (-> {"ev" (reify ReadHandler
              (read [_ r tag component-count]
                (assert (= 4 component-count))
                (Event. (.readInt r)
                        (.readInt r)
                        (.readObject r)
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

(def journal-dir-name
  "net-journal")

(defn journal-dir
  "Where do we store journal chunks for this test?"
  [test]
  (store/path test journal-dir-name))

(defn file!
  "What file do we use for storing this stripe of a journal?"
  [test stripe]
  (store/path! test journal-dir-name (str stripe ".fressian")))

(defn ^FressianWriter disk-writer
  "Constructs a new Fressian Writer for a test's journal."
  [test stripe]
  (-> (file! test stripe)
      io/output-stream
      (fress/create-writer :handlers write-handlers)))

(defn ^FressianReader disk-reader
  "Constructs a new Fressian Reader for a test's journal."
  [test stripe]
  (-> (file! test stripe)
      io/input-stream
      (fress/create-reader :handlers read-handlers)))

(defn disk-readers
  "Constructs a vector of readers, one per stripe in this test's journal."
  [test]
  (->> (journal-dir test)
       file-seq
       (filter #(re-find #"\.fressian$" (.getName ^File %)))
       (mapv (fn [file]
               (-> (io/input-stream file)
                   (fress/create-reader :handlers read-handlers))))))

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
  "Constructs a new journal: a map of:

    :test         The test
    :next-stripe  An atom used to assign stripe numbers
    :local-writer The local thread's writer
    :next-index   An atom long used to establish the order of journal events
    :writers      An atom vector of writers, use to close down the journal"
  [test]
  {:test          test
   :next-stripe   (atom -1)
   :local-writer  (ThreadLocal.)
   :next-id       (atom -1)
   :writers       (atom [])})

(defn close!
  "Closes a journal."
  [journal]
  ; Close each writer
  (doseq [^FressianWriter w @(:writers journal)]
    (.close w)))

(defn ^FressianWriter local-writer
  "Returns the writer for the current thread, possibly opening a new one."
  [journal]
  (let [^ThreadLocal local-writer (:local-writer journal)]
    ; Return local writer if we already have it
    (or (.get local-writer)
        ; Ah, we need to create a new writer. Pick a new stripe ID...
        (let [stripe (swap! (:next-stripe journal) inc)
              ; Create a writer
              writer (disk-writer (:test journal) stripe)]
          ; And record it
          (swap! (:writers journal) conj writer)
          (.set local-writer writer)
          writer))))

(defn log-event!
  "Logs an arbitrary event to a journal."
  [journal event]
  (fress/write-object (local-writer journal) event))

(defn log-send!
  "Logs a send operation"
  [journal message]
  (log-event! journal (Event. (swap! (:next-id journal) inc)
                              (linear-time-nanos)
                              :send
                              message)))

(defn log-recv!
  "Logs a receive operation"
  [journal message]
  (log-event! journal (Event. (swap! (:next-id journal) inc)
                              (linear-time-nanos)
                              :recv
                              message)))

(defn involves-client?
  "Takes an event and returns true iff it was sent to or received from a
  client."
  [{:keys [message]}]
  (u/involves-client? message))

(defn without-init
  "A fold which strips out initialization messages."
  [& [f]]
  (t/remove (fn [^Event event]
              (let [t (:type (.body ^maelstrom.net.message.Message
                                    (.message event)))]
                (or (= t "init")
                    (= t "init_ok"))))
            f))

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

#_
(defn linear-set
  "Constructs a new mutable Bifurcan set"
  []
  (LinearSet.
    ; For dense sets like our message IDs, Clojure's hash function gives better
    ; (like, multiple orders of mag faster) dispersion than the Bifurcan
    ; default hash.
    (reify
      java.util.function.ToLongFunction
      (applyAsLong [_ x] (hash x))
      java.util.function.ToIntFunction
      (applyAsInt [_ x] (hash x)))
    Maps/DEFAULT_EQUALS))

#_
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
  (t/filter (fn send? [^Event e] (identical? :send (.type e)))))

(def recvs
  "Fold which filters a journal to just receives."
  (t/filter (fn recv? [^Event e] (identical? :recv (.type e)))))

(def clients
  "Fold which filters a journal to just messages to/from clients"
  (t/filter involves-client?))

(def servers
  "Fold which filters a journal to just messages between servers."
  (t/remove involves-client?))

(t/deftransform up-to-event
  "A fold which selects all contiguous events, in event order, up to but not
  including id n. Relies on the fact that event IDs are allocated contiguously
  0, 1, 2... and unique across all chunks."
  [n]
  (assert (nil? downstream))
  {:reducer-identity (partial transient [])
   :reducer          (fn reducer [taken ^Event e]
                       (if (< (.id e) n)
                         ; Still in bounds
                         (conj! taken e)
                         ; Done
                         (reduced taken)))
   :post-reducer      persistent!
   :combiner-identity (constantly [])
   :combiner          into
   :post-combiner     (partial sort-by :id)})

(defn tesser-journal
  "Runs a Tesser fold over a test's journal: each stripe used as a single
  chunk."
  [test fold]
  (let [readers (disk-readers test)]
    (try
      (t/tesser (mapv reader-seq readers) fold)
      (finally (doseq [^Closeable r readers]
                 (.close r))))))
