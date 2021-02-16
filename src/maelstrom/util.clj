(ns maelstrom.util
  "Kitchen sink"
  (:require [schema.core :as s]
            [clojure.string :as str]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn client?
  "Is a given node id a client?"
  [node-id]
  (re-find #"^c" node-id))

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
                  (let [[_ type num] (re-find #"(\w+?)(\d+)" client)]
                    [type (Long/parseLong num)])))))
