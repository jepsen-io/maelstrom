(ns maelstrom.doc
  "Generates documentation files from Maelstrom's internal registries of RPC
  operations and errors."
  (:require [clojure [pprint :refer [pprint]]
                     [string :as str]]
            [clojure.java.io :as io]
            [maelstrom [client :as c]]))

(def workloads-filename
  "Where should we write workloads.md?"
  "doc/workloads.md")

(def protocol-filename
  "Where should we write protocol.md?"
  "doc/protocol.md")

(defn unindent
  "Strips leading whitespace from all lines in a string."
  [s]
  (str/replace s #"(^|\n)[ \t]+" "$1"))

(def workloads-preamble
  "A *workload* specifies the semantics of a distributed system: what
  operations are performed, how clients submit requests to the system, what
  those requests mean, what kind of responses are expected, which errors can
  occur, and how to check the resulting history for safety.

  For instance, the *broadcast* workload says that clients submit `broadcast`
  messages to arbitrary servers, and can send a `read` request to obtain the
  set of all broadcasted messages. Clients mix reads and broadcast operations
  throughout the history, and at the end of the test, perform a final read
  after allowing a brief period for convergence. To check broadcast histories,
  Maelstrom looks to see how long it took for messages to be broadcast, and
  whether any were lost.

  This is a reference document, automatically generated from Maelstrom's source
  code by running `lein run doc`. For each workload, it describes the general
  semantics of that workload, what errors are allowed, and the structure of RPC
  messages that you'll need to handle.")

(defn print-workloads
  "Prints out all workloads to stdout, based on the client RPC registry."
  ([]
   (print-workloads @c/rpc-registry))
  ([rpcs]
   ; Group RPCs by namespace
   (let [ns->rpcs (->> rpcs
                       (group-by (fn [rpc]
                                   (-> rpc
                                       :ns
                                       ns-name
                                       name
                                       (str/split #"\.")
                                       last)))
                       (sort-by key))]

     (println "# Workloads\n")
     (println (unindent workloads-preamble) "\n")

     (println "## Table of Contents\n")
     (doseq [[ns rpcs] ns->rpcs]
       (println (str "- [" (str/capitalize ns) "](#workload-" ns ")")))
     (println)

     (doseq [[ns rpcs] ns->rpcs]
       (println "## Workload:" (str/capitalize ns) "\n")

       (println (unindent (:doc (meta (:ns (first rpcs))))) "\n")

       (doseq [rpc rpcs]
         (println "### RPC:" (str/capitalize (:name rpc)) "\n")
         (println (unindent (:doc rpc)) "\n")
         (println "Request:\n")
         (println "```clj")
         (pprint (:send rpc))
         (println "```")
         (println "\nResponse:\n")
         (println "```clj")
         (pprint (:recv rpc))
         (println "```")
         (println "\n"))

       (println)))))

(defn print-error-registry
  "Prints out the error registry, as Markdown, for documentation purposes."
  []
  (println "| Code | Name | Definite | Description |")
  (println "| ---- | ---- | -------- | ----------- |")

  (doseq [{:keys [code name definite? doc]} (map val (sort @c/error-registry))]
    (println "|" code "|" name "|" (if definite? "✓" " ") "|"
             (str/replace doc #"\n" " ") "|")))

(defn print-protocol
  "Prints out the protocol documentation, including errors."
  []
  (println (slurp (io/resource "protocol-intro.md")))
  (print-error-registry))

(defn write-docs!
  "Writes out all documentation files."
  []
  (with-open [w (io/writer workloads-filename)]
    (binding [*out* w]
      (print-workloads)))

  (with-open [w (io/writer protocol-filename)]
    (binding [*out* w]
      (print-protocol))))
