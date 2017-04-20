(ns maelstrom.process
  "Handles process spawning and IO"
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [byte-streams :as bs]
            [cheshire.core :as json]
            [jepsen.util :refer [with-thread-name]]
            [maelstrom [net :as net]])
  (:import (java.lang Process
                      ProcessBuilder
                      ProcessBuilder$Redirect)
           (java.io File
                    OutputStreamWriter)
           (java.util.concurrent TimeUnit)))

(defn stderr-thread
  "Spawns a future which handles stderr from a process."
  [^Process p node-id log-writer log-stderr?]
  (future
    (with-thread-name (str "node " node-id)
      (with-open [log log-writer]
        (doseq [line (bs/to-line-seq (.getErrorStream p))]
          (when log-stderr? (info line))
          (.write log line)
          (.write log "\n")
          (.flush log)))
      :stderr-done)))

(defn stdout-thread
  "Spawns a future which reads stdout from a process and inserts messages into
  the network."
  [^Process p node-id net]
  (future
    (with-thread-name (str "node " node-id)
      (doseq [line (bs/to-line-seq (.getInputStream p))]
        (try
          (let [parsed (json/parse-string line true)]
            (net/send! net parsed))
          (catch java.io.IOException e)
          (catch Throwable e
            (warn e "error processing stdout:\n" line))))
      :stdout-done)))

(defn stdin-thread
  "Spawns a future which reads messages from the network and submits them to a
  process's stdin."
  [^Process p node-id net running?]
  (future
    (with-thread-name (str "node " node-id)
      (try
        (with-open [w (OutputStreamWriter. (.getOutputStream p))]
          (while (deref running?)
            (try
              (when-let [msg (net/recv! net node-id 1000)]
                (json/generate-stream msg w)
                (.write w "\n")
                (.flush w))
              (catch java.io.IOException e)
              (catch Throwable e
                (warn e "error processing stdin")))))
        ; When we try to close the OutputStreamWriter, it'll try to write
        ; to its underlying stream and throw *again*
        (catch java.io.IOException e))
      :stdin-done)))

(defn start-node!
  "Starts a node. Options:

      :dir          The directory to run in
      :bin          A program to run
      :args         A list of arguments to the program
      :node-id      This node's ID
      :log-file     A string file to receive stderr output
      :net          A network.
      :log-stderr?  Whether to log stderr output from processes to our logger

  Returns:

  {:process         The java Process object started
   :running?        An atom used for controlling worker threads
   :stdin-thread    The future used for writing to the process' stdin
   :stdout-thread   The future used for stdout messages
   :stderr-thread   The future used for stderr messages
  "
  [opts]
  (info "launching" (:bin opts) (pr-str (:args opts)))
  (let [node-id (:node-id opts)
        net     (:net opts)
        _       (net/add-node! net node-id)
        log     (io/writer (:log-file opts))
        bin     (.getCanonicalPath (io/file (:bin opts)))
        process (-> (ProcessBuilder. (cons bin (:args opts)))
                    (.directory (io/file (:dir opts)))
                    (.redirectOutput ProcessBuilder$Redirect/PIPE)
                    (.redirectInput  ProcessBuilder$Redirect/PIPE)
                    (.start))
        running? (atom true)]
    {:process       process
     :running?      running?
     :node-id       node-id
     :net           net
     :stdin-thread  (stdin-thread  process node-id net running?)
     :stderr-thread (stderr-thread process node-id log (:log-stderr? opts))
     :stdout-thread (stdout-thread process node-id net)}))

(defn stop-node!
  "Kills a node."
  [{:keys [process running? node-id net
           stdin-thread stderr-thread stdout-thread]}]
  (let [exit-status (-> process
                        .destroyForcibly
                        (.waitFor 5 TimeUnit/SECONDS))]
    ; Shut down workers
    (reset! running? false)
    (mapv deref [stdin-thread stderr-thread stdout-thread])

    ; Remove self from network
    (net/remove-node! net node-id)

    ; Return status of workers
    {:stdin  @stdin-thread
     :stderr @stderr-thread
     :stdout @stdout-thread}))
