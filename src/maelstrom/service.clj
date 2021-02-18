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
                                 " is not " (:from body))}])
                  [this
                   {:type "error", :code 20, :text "key does not exist"}])))))

(defn persistent-kv
  "A persistent key-value store."
  []
  (PersistentKV. {}))

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
                                      rand
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
          (catch Exception e
            (warn e "Error in service worker!")))))))

(defn start-services!
  "Takes a network and a map of node ids to MutableServices. Spawns threads
  for each mutable service, and constructs a map used to shut down these
  services later."
  [net services]
  (info "Starting services: " services)
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
  {"seq-kv" (sequential   (persistent-kv))
   "lin-kv" (linearizable (persistent-kv))})
