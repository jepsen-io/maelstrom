(ns maelstrom.workload.lin-kv-only
  "A workload for a linearizable key-value store that consists only of reads and writes."
  (:refer-clojure :exclude [read])
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [client :as client]
                    [checker :as checker]
                    [generator :as gen]
                    [independent :as independent]]
            [jepsen.checker.timeline :as timeline]
            [knossos.model :as model]
            [schema.core :as s]))

(c/defrpc read
  "Reads the current value of a single key. Clients send a `read` request with
  the key they'd like to observe, and expect a response with the current
  `value` of that key."
  {:type  (s/eq "read")
   :key   s/Any}
  {:type  (s/eq "read_ok")
   :value s/Any})

(c/defrpc write!
  "Blindly overwrites the value of a key. Creates keys if they do not presently
  exist. Servers should respond with a `read_ok` response once the write is
  complete."
  {:type (s/eq "write")
   :key  s/Any
   :value s/Any}
  {:type (s/eq "write_ok")})

(defn client
  "Construct a linearizable key-value client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (c/with-errors op #{:read}
         (let [[k v]   (:value op)
               timeout (max (* 10 (:mean (:latency test))) 1000)]
           (case (:f op)
             :read (let [res (read conn node {:key k} timeout)
                         v (:value res)]
                     (assoc op
                            :type  :ok
                            :value (independent/tuple k v)))

             :write (let [res (write! conn node {:key k, :value v} timeout)]
                      (assoc op :type :ok))))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn))

     client/Reusable
     (reusable? [this test]
       true))))

(defn w   [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn r   [_ _] {:type :invoke, :f :read})

(defn test
  "A partial test, including a generator, model, and checker. You'll need to
  provide a client. Options:

    :nodes            A set of nodes you're going to operate on. We only care
                      about the count, so we can figure out how many workers
                      to use per key.
    :model            A model for checking. Default is (model/cas-register).
    :per-key-limit    Maximum number of ops per key.
    :process-limit    Maximum number of processes that can interact with a
                      given key. Default 20."
  [opts]
  {:checker (independent/checker
              (checker/compose
               {:linearizable (checker/linearizable
                                {:model (:model opts (model/cas-register))})
                :timeline     (timeline/html)}))
   :generator (let [n (count (:nodes opts))]
                (independent/concurrent-generator
                  (* 2 n)
                  (range)
                  (fn [k]
                    (cond->> (gen/reserve n r w)
                      ; We randomize the limit a bit so that over time, keys
                      ; become misaligned, which prevents us from lining up
                      ; on Significant Event Boundaries.
                      (:per-key-limit opts)
                      (gen/limit (* (+ (rand 0.1) 0.9)
                                    (:per-key-limit opts 20)))

                      true
                      (gen/process-limit (:process-limit opts 20))))))})

(defn workload
  "Constructs a workload for linearizable registers that consists only of reads
  and writes, given option from the CLI test constructor:

      {:net     A Maelstrom network}"
  [opts]
  (-> (test {:nodes (:nodes opts)})
      (assoc :client (client (:net opts)))))
