(ns maelstrom.net.message
  "Contains operations specifically related to network messages; used by both
  net and net.journal."
  (:require [schema.core :as s]))



(defrecord Message [^long id src dest body])

(defn message
  "Constructs a new Message. If no ID is provided, uses -1."
  ([src dest body]
   (Message. -1 src dest body))
  ([id src dest body]
   (Message. id src dest body)))

(defn validate
  "Checks to make sure a message is well-formed. Returns msg if legal,
  otherwise throws."
  [m]
  (assert (instance? Message m)
          (str "Expected message " (pr-str m) " to be a Message"))
  (assert (:src m) (str "No source for message " (pr-str m)))
  (assert (:dest m) (str "No destination for message " (pr-str m)))
  m)
