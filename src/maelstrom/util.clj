(ns maelstrom.util
  "Kitchen sink"
  (:require [schema.core :as s]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn client?
  "Is a given node id a client?"
  [^String node-id]
  (= \c (.charAt node-id 0)))

(defn involves-client?
  "Does a given network message involve a client?"
  [message]
  (or (client? (:src message))
      (client? (:dest message))))

(defn sort-clients
  "Sorts a collection by client ID. We split up the letter and number parts, to
  give a nice numeric order."
  [clients]
  (->> clients
       (sort-by (fn [client]
                  (if-let [[_ type num] (re-find #"(\w+?)(\d+)" client)]
                    ; Typical 'c1', 'n4', etc
                    [0 type (Long/parseLong num)]
                    ; Sort special (services) nodes last
                    [1 client 0])))))

(defn map->pairs
  "Encodes a map {k v, k2 v2} as KV pairs [[k v] [k2 v2]], for JSON
  serialization."
  [m]
  (seq m))

(defn pairs->map
  "Decodes a sequence of kv pairs [[k v] [k2 v2]] to a map {k v, k2 v2}, for
  JSON deserialization."
  [pairs]
  (into {} pairs))
