(ns maelstrom.service
  "Services are Maelstrom-provided nodes which offer things like 'a
  linearizable key-value store', 'a source of sequentially-assigned
  timestamps', 'an eventually-consistent immutable key-value store', 'a
  sequentially consistent FIFO queue', and so on. Your nodes can use these as
  primitives for building more sophisticated systems.

  For instance, if you're trying to build a transactional, serializable
  database, you might build it as a layer on top of an existing linearizable
  per-key kv store--say, several distinct Raft groups, one per shard. In
  Maelstrom, you'd write your nodes to accept transaction requests, then (in
  accordance with your chosen transaction protocol) make your own key-value
  requests to the `lin-kv` service."
  (:require [amalloy.ring-buffer :as ring-buffer]
            [clojure.tools.logging :refer [info warn]]
            [maelstrom [net :as net]]
            [jepsen [util :as util]]))

(defprotocol PersistentService
  (handle [this message]
          "Handles an RPC request, returning a vector of
          [new-service-state, response-body]. The response body is sent
          back to the requesting client."))

(defprotocol Merge
  (merge-services [this other]
                  "Merges two instances of a persistent service together, using
                  last-write-wins semantics. For our purposes, last-write-wins
                  means *any* write wins, so get creative."))

(defrecord PersistentKV [m]
  PersistentService
  (handle [this message]
    (let [body (:body message)
          k    (:key body)]
      (case (:type body)
        "read" [this
                (if (contains? m k)
                  {:type "read_ok", :value (m k)}
                  {:type "error", :code 20, :text "key does not exist"})]
        "write" [(assoc-in this [:m k] (:value body))
                 {:type "write_ok"}]
        "cas"   (if (contains? m k)
                  (if (= (:from body) (m k))
                    [(assoc-in this [:m k] (:to body))
                     {:type "cas_ok"}]
                    [this
                     {:type "error"
                      :code 22
                      :text (str "current value " (pr-str (m k))
                                 " is not " (pr-str (:from body)))}])
                  (if (:create_if_not_exists body)
                    [(assoc-in this [:m k] (:to body))
                     {:type "cas_ok"}]
                    [this
                     {:type "error", :code 20, :text "key does not exist"}]))))))

(defn persistent-kv
  "A persistent key-value store. Work just like Maelstrom's `lin-kv` workload."
  []
  (PersistentKV. {}))

(defprotocol AppendLog
  (append-log [this item]
              "Appends decided item to the log.
               Returns [new-service-state] with item appended into the log."))

; m is cols of proposed values. reset each round.
; logger is something that implements AppendLog.
(defrecord Consensus [m]
  PersistentService
  (handle [this message]
    (let [body (:body message)]
    (case (:type body)
      ; proposes a value for decision.
      ; it is needed to simulate concurrency in consensus
      ; that in can select only 1 proposal per log slot.
      "propose" [(assoc-in this [:m] (cons (:value body) m))
               {:type "propose_ok"}]
      ; decides on 1 entry from the set of proposed values,
      ; appends it to the log and cleans up the proposed set.
      "decide" (if (not (empty? m))
                  (let [v (first (shuffle m))
                        ; reset m
                        this (assoc-in this [:m] (lazy-seq nil))]
                    [this {:type "decide_ok", :value v}])
                  [this {:type "error", :code 22, :text "no proposed values"}])
      ; resets the proposed set.
      "reset" (let [this (assoc-in this [:m] (lazy-seq nil))]
                  [this {:type "reset_ok"}])
      ; log of decided values.
      "log"    [this {:type "error", :code 10, :text "not supported yet"}]))))

; test: (use 'maelstrom.service :reload)(handle (first (handle (first (handle (consensus) {:body {:type "propose" :key 1  :value 1}})) {:body {:type "propose" :value 2}})) {:body {:type "decide"}})
; test: (linearizable(consensus))
;   (use 'maelstrom.service :reload)
;   (def a (linearizable(consensus)))
;   (handle! a {:body {:type "propose" :value 1}})
;   (handle! a {:body {:type "propose" :value 2}})
;   (handle! a {:body {:type "propose" :value 3}})
;   a
; => #maelstrom.service.Linearizable{:state #object[... {:status :ready, :val #maelstrom.service.Consensus{:m (3 2 1)}}]}
;   (handle! a {:body {:type "decide"}})
; => {:type "decide_ok", :value 2}
;   a
; => ... Consensus{:m ()}}
(defn consensus
  "A simple consensus for implementing Maelstrom's `lin-kv` service."
  []
  (Consensus. (lazy-seq nil)))

; SDPaxos is an anonymous single-decree Paxos implementation.
; It decides only for a single register.
; Log is the last decided value.
; Con is Consensus.
; Resets only resets Log so that is can be reused without losing concurrently
; proposed values.
(defrecord SDPaxos [log con]
  PersistentService
  (handle [this message]
    (let [body (:body message)]
    (case (:type body)
      "propose" (if (empty? log)
                  (let [[con res] (handle con message)]
                    [(assoc-in this [:con] con) res])
                  [this {:type "error", :code 22, :text "already decided"}])
      "decide"  (if (empty? log)
                  (let [[con res] (handle con message)
                      this (assoc-in this [:con] con)]
                    (if (= (:type res) "error")
                      [this res]
                      [(append-log this (get-in res [:value])) res]))
                  [this {:type "error", :code 22, :text "already decided"}])
      "reset"   [(assoc-in this [:log] (lazy-seq nil)) {:type "reset_ok"}]
      "log"     [this
                 (if (empty? log)
                   {:type "log_ok", :log []}
                   {:type "log_ok", :log [(first log)]})
                   ])))
  AppendLog
  (append-log [this item]
      (assoc-in this [:log] (cons item nil))))

; test: (use 'maelstrom.service :reload)(handle (first (handle (first (handle (sd-paxos) {:body {:type "propose" :value 1}})) {:body {:type "propose" :value 2}})) {:body {:type "decide"}})
; test: (linearizable(sd-paxos))
;   (use 'maelstrom.service :reload)
;   (def a (linearizable(sd-paxos)))
;   (handle! a {:body {:type "propose" :value 1}})
;   (handle! a {:body {:type "propose" :value 2}})
;   (handle! a {:body {:type "propose" :value 3}})
;   a
; => #maelstrom.service.Linearizable{:state #object[... {:status :ready, :val #maelstrom.service.SDPaxos{:m (3 2 1)}}]}
;   (handle! a {:body {:type "decide"}})
; => {:type "decide_ok", :value 2}
;   a
; => ... SDPaxos{:log [2] :m ()}}
(defn sd-paxos 
  "A simple consensus for implementing Maelstrom's `lin-kv` service.
   Single-decree paxos decides only only a single-value."
  []
  (SDPaxos. (lazy-seq nil) (Consensus. (lazy-seq nil))))

; MSDPaxos - multi-instance log-style single-decree paxos.
; Single-decree paxos instance <i> decides for a register <i>.
; If a value was decided for a register <i>, no new value will be decided for
; any other register <j> where j <= i.
; Log is the last decided value for register <i>: (i, val).
; Con is Consensus.
; <i> is the current register number.
(defrecord MSDPaxos [log con i]
  PersistentService
  (handle [this message]
    (let [body (:body message)
          not_decided? (fn [i] (let [last (first log) ; [i val]
                                    last_i (if (nil? last) -1 (first last))] ; i or -1
                                    (< last_i i)))] ; if last decided val < current.
    (case (:type body)
      ; propose a value for register i.
      ; {:value v, :i i}
      "propose" (let [reqi (:i body)]
                  (if (nil? reqi)
                    [this {:type "error", :code 12, :text "missing register number <i>"}]
                  (if (> reqi i)
                    (let [
                          ; for new epoch / register we reset all previous promises.
                          [con _res] (handle con {:body {:type "reset"}})
                          ; save our proposal.
                          [con res] (handle con message)]
                        [(assoc-in (assoc-in this [:con] con) [:i] reqi) res])
                    ; for current epoch / register we can propose a value.
                    (if (and (= reqi i) (not_decided? reqi))
                      (let [[con res] (handle con message)]
                        [(assoc-in this [:con] con) res])
                    [this {:type "error", :code 22, :text "already decided", :i i}]))))
      ; decide on a value for register i.
      "decide"  (if (not_decided? i)
                  (let [[con res] (handle con message)
                      this (assoc-in this [:con] con)]
                    (if (= (:type res) "error")
                      [this res]
                      [(append-log this [i, (get-in res [:value])]) (assoc-in res [:i] i)]))
                  [this {:type "error", :code 22, :text "already decided", :i i}])
      ; not supported yet.
      "reset"   [(assoc-in this [:log] (lazy-seq nil)) {:type "error", :code 10, :text "not supported"}]
      ; log of decided values: (i, val).
      "log"     [this
                 (if (empty? log)
                   {:type "log_ok", :log []}
                   {:type "log_ok", :log [(first log)]})
                   ])))
  AppendLog
  (append-log [this item]
      (assoc-in this [:log] (cons item nil))))

; test: (use 'maelstrom.service :reload)(handle (first (handle (first (handle (msd-paxos) {:body {:type "propose" :value 1 :i 1}})) {:body {:type "propose" :value 2 :i 1}})) {:body {:type "decide"}})
; test: (use 'maelstrom.service :reload)(def a (msd-paxos))
;       (def a (handle (a) {:body {:type "propose" :value 1 :i 1}}))
;       (def a (handle (first a) {:body {:type "propose" :value 2 :i 1}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log (), :con #maelstrom.service.Consensus{:m (2 1)}, :i 1} {:type "propose_ok"}]
;       (def a (handle (first a) {:body {:type "decide"}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m ()}, :i 1} {:type "decide_ok", :value 1, :i 1}]
;
;       (def a (handle (first a) {:body {:type "decide"}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m ()}, :i 1} {:type "error", :code 22, :text "already decided", :i 1}]
;       (def a (handle (first a) {:body {:type "propose" :value 2 :i 1}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m ()}, :i 1} {:type "error", :code 22, :text "already decided", :i 1}]
;
;       (def a (handle (first a) {:body {:type "propose" :value 3 :i 2}}))
;       (def a (handle (first a) {:body {:type "propose" :value 4 :i 2}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m (4 3)}, :i 2} {:type "propose_ok"}]
;       (def a (handle (first a) {:body {:type "propose" :value 4 :i 3}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m (4)}, :i 3} {:type "propose_ok"}]
;       (def a (handle (first a) {:body {:type "propose" :value 3 :i 3}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([1 1]), :con #maelstrom.service.Consensus{:m (3 4)}, :i 3} {:type "propose_ok"}]
;       (def a (handle (first a) {:body {:type "decide"}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([3 3]), :con #maelstrom.service.Consensus{:m ()}, :i 3} {:type "decide_ok", :value 3, :i 3}]
;       (def a (handle (first a) {:body {:type "decide"}}))
;       a
;       [#maelstrom.service.MSDPaxos{:log ([3 3]), :con #maelstrom.service.Consensus{:m ()}, :i 3} {:type "error", :code 22, :text "already decided", :i 3}]
; test: (use 'maelstrom.service :reload)(linearizable(msd-paxos))
;   (use 'maelstrom.service :reload)
;   (def a (linearizable(msd-paxos)))
;   (handle! a {:body {:type "propose" :value 1 :i 1}})
;   (handle! a {:body {:type "propose" :value 2 :i 1}})
;   (handle! a {:body {:type "decide" :i 1}})
;   (handle! a {:body {:type "propose" :value 3 :i 2}})
;   (handle! a {:body {:type "propose" :value 4 :i 2}})
;   (handle! a {:body {:type "decide" :i 2}})
(defn msd-paxos
  "A simple log-style consensus for implementing Maelstrom's `lin-kv` service.
   Multi single-decree paxos decides on a register <i> if there is no decided <j> | j >= i."
  []
  (MSDPaxos. (lazy-seq nil) (Consensus. (lazy-seq nil)) 0))

; clock is our local timestamp, which increments on every update.
; m is a map of keys to {:ts n, :value whatever}.
(defrecord LWWKV [clock m]
  PersistentService
  (handle [this message]
    (let [body (:body message)
          k    (:key body)]
      (case (:type body)
        "read" [this
                (if (contains? m k)
                  {:type "read_ok", :value (:value (m k))}
                  {:type "error", :code 20, :text "key does not exist"})]
        "write" [(LWWKV. (inc clock)
                         (assoc m k {:ts    clock
                                     :value (:value body)}))
                 {:type "write_ok"}]
        "cas"   (if (contains? m k)
                  (if (= (:from body) (:value (m k)))
                    [(LWWKV. (inc clock)
                             (assoc m k {:ts    clock
                                         :value (:to body)}))
                     {:type "cas_ok"}]
                    [this
                     {:type "error"
                      :code 22
                      :text (str "current value " (pr-str (:value (m k)))
                                 " is not " (:from body))}])
                  [this
                   {:type "error", :code 20, :text "key does not exist"}]))))

  Merge
  (merge-services [this other]
    ; Clocks are a Lamport timestamp
    (LWWKV. (max clock (:clock other))
            ; Values are merged by timestamp, then natural order
            (merge-with (fn [v1 v2]
                          (let [t1 (:ts v1)
                                t2 (:ts v2)
                                x1 (:value v1)
                                x2 (:value v2)]
                            (cond (< t1 t2)               v2
                                  (< t2 t1)               v1
                                  :else                   v1)))
                        m (:m other)))))

(defn lww-kv
  "A last-write-wins key-value store. Works just like Maelstrom's `lin-kv`
  workload, but eventually consistent. Each instance maintains a Lamport clock,
  and assigns timestamps to values based on that clock. Merges clocks and
  values together with last-write-wins."
  []
  (LWWKV. 0 {}))

(defrecord PersistentTSO [ts]
  PersistentService
  (handle [this message]
    (let [body (:body message)]
      (case (:type body)
        "ts" [(PersistentTSO. (inc ts))
              {:type "ts_ok", :ts ts}]))))

(defn persistent-tso
  "A TimeStamp Oracle service which provides a monotonically increasing stream
  of integers, starting at 0. Responds to `{:type \"ts\"}` requests by
  providing a unique timestamp, like so:

    {:type \"ts_ok\"
     :ts 123}"
  []
  (PersistentTSO. 0))

(defprotocol MutableService
  (handle! [this message]
           "Handles a message, possibly mutating this service and returning a
           response body for the client."))

; A linearizable service wraps a PersistentService in an atom. Updates are
; performed by swapping the atom value for a new one.
(defrecord Linearizable [state]
  MutableService
  (handle! [this message]
    (let [response (atom nil)]
      (swap! state (fn [state]
                     (let [[state' res] (handle state message)]
                       (reset! response res)
                       state')))
      @response)))

(defn linearizable
  "Takes a persistent service, and wraps it in a linearizable MutableService
  wrapper."
  [persistent-service]
  (Linearizable. (atom persistent-service)))

; State is an atom containing:
;   :clients     a map of clients-node-id -> last-observed-state-index
;   :last-index  the index of the most recently added element in the buffer
;   :buffer      a ring buffer of service states
(defrecord Sequential [state]
  MutableService
  (handle! [this message]
    (let [client   (:src message)
          response (atom nil)]
      (swap! state
             (fn [{:keys [clients last-index buffer] :as state}]
               (let [client-index (get clients client 0)
                     ; Pick some index to interact with
                     index        (-> last-index
                                      (- client-index)
                                      inc
                                      rand-int
                                      (+ client-index))
                     _ (assert (<= client-index index last-index))
                     ; We compute a negative offset into the buffer: -1 is
                     ; last-index, -2 is the previous, and so on:
                     service (nth buffer (dec (- index last-index)))
                     ; Speculatively execute on that index
                     [service' res] (handle service message)]
                 ; Did we alter the service state? If so, we might violate
                 ; our sequential timeline.
                 (if (= service service')
                   ; No! We can safely execute on this state without fucking up
                   ; the timeline.
                   (do (reset! response res)
                       (assoc-in state [:clients client] index))
                   ; Shoot, we might violate the sequential total order. We
                   ; could probe more possible states, but let's just jump
                   ; ahead to the most recent.
                   (let [[service' res] (handle (nth buffer -1) message)
                         last-index'    (inc last-index)]
                     (reset! response res)
                     {:clients    (assoc clients client last-index')
                      :last-index last-index'
                      :buffer     (conj buffer service')})))))
      @response)))

(defn sequential
  "A sequential service wraps a PersistentService in a buffer. Operations
  which do not change the value of the service may observe any of the past n
  states; other operations are forced to take place on the most recent state.
  Each client always observes a monotonic sequence of events."
  ([persistent-service]
   (sequential 32 persistent-service))
  ([buffer-size persistent-service]
   (Sequential. (atom {:buffer     (conj (ring-buffer/ring-buffer buffer-size)
                                         persistent-service)
                       :last-index 0
                       :clients    {}}))))

; Replicas is an atom to a vector of states, simulating several independent
; replicas. States are updated and merged at random.
(defrecord Eventual [replicas]
  MutableService
  (handle! [this message]
    (let [response (atom nil)]
      (swap! replicas
             (fn [replicas]
               ; Merge one random replica into another
               (let [n            (count replicas)
                     merge-source (rand-int n)
                     merge-dest   (rand-int n)
                     merged       (merge-services (nth replicas merge-source)
                                                  (nth replicas merge-dest))
                     replicas'    (assoc replicas merge-dest merged)

                     ; Apply message to yet another random replica
                     i              (rand-int n)
                     [replica' res] (handle (nth replicas i) message)
                     replicas'      (assoc replicas i replica')]
                 (reset! response res)
                 replicas')))
      @response)))

(defn eventual
  "An eventual service wraps a PersistentService, simulating n distinct
  replicas of that service. Replicas periodically gossip state between them,
  using `merge` to compute new states."
  ([persistent-service]
   (eventual 2 persistent-service))
  ([n persistent-service]
   (Eventual. (atom (vec (repeat n persistent-service))))))

(defn service-thread
  "Spawns a thread which handles service requests from the network. Takes a
  network, a running atom, a node ID, and a MutableService."
  [net node-id service running?]
  (future
    (util/with-thread-name (str "maelstrom " node-id)
      (while @running?
        (try
          (when-let [message (net/recv! net node-id 1000)]
            (let [body (assoc (handle! service message)
                              :in_reply_to (:msg_id (:body message)))]
              (net/send! net {:src  node-id
                              :dest (:src message)
                              :body body})))
          (catch InterruptedException e
            ; We're aborting
            )
          (catch Exception e
            (warn e "Error in service worker!")))))))

(defn start-services!
  "Takes a network and a map of node ids to MutableServices. Spawns threads
  for each mutable service, and constructs a map used to shut down these
  services later."
  [net services]
  (info "Starting services:" (sort (keys services)))
  (let [running? (atom true)
        workers (mapv (fn [[node-id service]]
                        (net/add-node! net node-id)
                        (service-thread net node-id service running?))
                      services)]
    {:net      net
     :running? running?
     :services services
     :workers  workers}))

(defn stop-services!
  "Shuts down all services started via start-services!"
  [services]
  (reset! (:running? services) false)
  (mapv deref (:workers services))
  (mapv (partial net/remove-node! (:net services))
        (keys (:services services)))
  (:services services))

(defn default-services
  "Constructs some default services you might find useful."
  [test]
  {"lww-kv"  (eventual     (lww-kv))
   "seq-kv"  (sequential   (persistent-kv))
   "lin-kv"  (linearizable (persistent-kv))
   "lin-tso" (linearizable (persistent-tso))
   "raw-con" (linearizable (consensus))
   "sdp-con" (linearizable (sd-paxos))
   "msdp-con" (linearizable (msd-paxos))})
