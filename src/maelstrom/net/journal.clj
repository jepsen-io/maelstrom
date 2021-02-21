(ns maelstrom.net.journal
  "A network's journal is a mutable store for keeping track of the history of
  network operations. We're not aiming for precision here--there's lag time
  between what the network sees and what nodes actually do--so we use
  jepsen.util/linear-time as our order for events.

  A journal is an atom to a vector containing a list of events, which are
  appended linearly to the end of the journal. Events are maps of this form:

  {:type      :send, :recv
   :message   The message exchanged}

  Most of this namespace is dedicated to processing statistics around journals:
  lining up what happened to a message, how many times it was delivered,
  latency distributions, etc."
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [checker :as checker]
                    [store :as store]
                    [util :as util :refer [linear-time-nanos]]]
            [maelstrom.util :as u]
            [maelstrom.net.viz :as viz]
            [tesser [core :as t]
                    [math :as tm]
                    [utils :as tu]]))

(defn journal
  "Constructs a new journal."
  []
  (atom []))

(defn log-send!
  "Logs a send operation"
  [journal message]
  (swap! journal conj {:type    :send
                       :time    (linear-time-nanos)
                       :message message}))

(defn log-recv!
  "Logs a receive operation"
  [journal message]
  (swap! journal conj {:type    :recv
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
      (let [journal (-> test :net-journal deref)
            stats   (t/tesser (tu/chunk-vec 65536 journal) stats)]
        ; Generate a plot
        (viz/plot-analemma! (without-init journal)
                            (store/path! test "messages.svg"))
        {:stats  stats
         :valid? true}))))
