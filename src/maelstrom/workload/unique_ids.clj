(ns maelstrom.workload.unique-ids
  "A simple workload for ID generation systems. Clients ask servers to generate
  an ID, and the server should respond with an ID. The test verifies that those
  IDs are globally unique.

  Your node will receive a request body like:

  ```json
  {\"type\": \"generate\",
   \"msg_id\": 2}
  ```

  And should respond with something like:

  ```json
  {\"type\": \"generate_ok\",
   \"in_reply_to\": 2,
   \"id\": 123}
  ```

  IDs may be of any type--strings, booleans, integers, floats, compound JSON
  values, etc."
  (:require [maelstrom [client :as c]
                       [net :as net]]
            [jepsen [checker :as checker]
                    [client :as client]
                    [generator :as gen]
                    [tests :as tests]]
            [schema.core :as s]))

(c/defrpc generate!
  "Asks a node to generate a new ID. Servers respond with a generate_ok message
  containing an `id` field, which should be a globally unique value. IDs may be
  of any type."
  {:type (s/eq "generate")}
  {:type (s/eq "generate_ok")
   :id   s/Any})

(defn client
  "Constructs a client for ID generation."
  ([net]
   (client net nil nil))
  ([net conn node]
   (reify client/Client
     (open! [_ test node]
       (client net (c/open! net) node))

     (setup! [_ test])

     (invoke! [_ test op]
       (c/with-errors op #{}
         (assoc op :type :ok, :value (:id (generate! conn node {})))))

     (teardown! [_ test])

     (close! [_ test]
       (c/close! conn))

     client/Reusable
     (reusable? [this test]
       true))))

(defn workload
  "Constructs a workload for unique ID generation, given options from the CLI
  test constructor. Options are:

    :net    A Maelstrom network"
  [opts]
  (assoc tests/noop-test
         :client    (client (:net opts))
         :generator (gen/repeat {:f :generate})
         :checker   (checker/unique-ids)))
