(ns maelstrom.workload.txn-rw-register
  "A transactional workload over a map of keys to values. Clients
  submit a single transaction via a `txn` request, and expect a
  completed version of that transaction in a `txn_ok` response.

  A transaction is an array of micro-operations, which should be executed in
  order:

  ```edn
  [op1, op2, ...]
  ```

  Each micro-op is a 3-element array comprising a function, key, and value:

  ```edn
  [f, k, v]
  ```

  There are two functions. A *read* observes the current value of a specific
  key. `[\"r\", 5, [1, 2]]` denotes that a read of key 5 observed the list `[1,
  2]`. When clients submit reads, they leave their values `null`:  `[\"r\", 5,
  null]`. The server processing the transaction should replace that value with
  whatever the observed value is for that key: `[\"r\", 5, [1, 2]]`.

  A *write* replaces the value for a key. For instance, `[\"w\", 5, 3]` means
  \"set key 5's value to 3\". Write values are provided by the client, and are
  returned unchanged.

  Unlike lin-kv, nonexistent keys should be returned as `null`. Keys are
  implicitly created on first write.

  This workload can check many kinds of consistency models. See the
  `--consistency-models` CLI option for details. Right now it only uses
  writes-follow-reads within individual transactions to infer version
  orders--this severely limits the transaction dependencies it can infer. We
  can make this configurable later."
  (:refer-clojure :exclude [read])
  (:require [dom-top.core :refer [loopr]]
            [elle.core :as elle]
            [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [history :as h]
                    [independent :as independent]]
            ; wow I cannot standardize wr vs rw to save my life
            [jepsen.tests.cycle.wr :as wr]
            [schema.core :as s]))

(def Key   s/Any)
(def Value s/Any)

(def ReadReq [(s/one (s/eq "r") "f") (s/one Key "k") (s/one (s/eq nil) "v")])
(def ReadRes [(s/one (s/eq "r") "f") (s/one Key "k") (s/one Value "v")])
(def Write   [(s/one (s/eq "w") "f") (s/one Key "k") (s/one Value "v")])

(c/defrpc txn!
  "Requests that the node execute a single transaction. Servers respond with a
  `txn_ok` message, and a completed version of the requested transaction--e.g.
  with read values filled in. Keys and values may be of any type, but if you
  need types for your language, it's probably safe to assume both are
  integers."
  {:type  (s/eq "txn")
   :txn   [(s/either ReadReq Write)]}
  {:type  (s/eq "txn_ok")
   :txn   [(s/either ReadRes Write)]})

(defn kw->str
  "We use keywords for our :f's. Converts keywords to strings in a txn."
  [txn]
  (mapv (fn [[f k v]]
          [(name f) k v])
        txn))

(defn str->kw
  "We use keywords for our :f's. Converts strings to keywords in a txn."
  [txn]
  (mapv (fn [[f k v]]
          [(keyword f) k v])
        txn))

(defn client
  "Construct a transactional write-read register client for the given network"
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [this test node]
       (client net (c/open! net) node))

     (setup! [this test])

     (invoke! [_ test op]
       (c/with-errors op #{}
         (->> (:value op)
              kw->str
              (array-map :txn)
              (txn! conn node)
              :txn
              str->kw
              (assoc op :type :ok, :value))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn))

     client/Reusable
     (reusable? [this test]
       true))))

(defn checker
  "Bit of a hack: we're probably gonna hit a zillion SCCs causing cycle search
  timeouts, but none of them are relevant to us--they're more for G2. We ignore
  those here. Wraps a checker from the Jepsen rw workload."
  [rw-checker]
  (reify checker/Checker
    (check [this test history opts]
      (let [res (checker/check rw-checker test history opts)
            types' (remove #{:cycle-search-timeout} (:anomaly-types res))]
        (assoc res
               :anomaly-types types'
               :anomalies (dissoc (:anomalies res) :cycle-search-timeout)
               :valid? (not (seq types')))))))

(defn workload
  "Constructs a workload for linearizable registers, given option from the CLI
  test constructor. Options are:

    :net                  A Maelstrom network
    :key-count            Number of keys to work on at any single time
    :min-txn-length       Minimum number of ops per transaction
    :max-txn-length       Maximum number of ops per transaction
    :max-writes-per-key   How many values to try writing to a single key.
    :consistency-models   What kinds of consistency models to check for."
  [opts]
  (-> (wr/test (assoc opts
                      ;:sequential-keys? true
                      :wfr-keys? true
                      ))
      (assoc :client (client (:net opts)))
      (update :checker checker)))
