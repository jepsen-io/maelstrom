(ns maelstrom.cli
  (:gen-class)
  (:require [clojure.tools.logging :refer [info warn error]]
            [maelstrom.core :as m]))

(defn -main [bin & args]
  (try
    (m/run! bin args)
    (System/exit 0)
    (catch Throwable t
      (error t "Arrrgh! Maelstrom broke!")
      (System/exit 1))))
