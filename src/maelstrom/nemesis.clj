(ns maelstrom.nemesis
  "Fault injection"
  (:require [clojure.tools.logging :refer [info warn]]
            [jepsen [generator :as gen]
                    [nemesis :as n]
                    [util :refer [pprint-str]]]
            [jepsen.nemesis.combined :as nc]
            [slingshot.slingshot :refer [try+ throw+]]))

(defn package
  "A full nemesis package. Options are those for
  jepsen.nemesis.combined/nemesis-package."
  [opts]
  (info :nemesis-opts (pprint-str opts))
  (nc/compose-packages
    [(nc/partition-package opts)
     (nc/db-package opts)]))
