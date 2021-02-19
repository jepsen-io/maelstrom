(ns maelstrom.process
  "Handles process spawning and IO"
  (:require [amalloy.ring-buffer :as ring-buffer]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.tools.logging :refer [info warn]]
            [byte-streams :as bs]
            [cheshire.core :as json]
            [jepsen.util :refer [with-thread-name]]
            [maelstrom [net :as net]]
            [slingshot.slingshot :refer [try+ throw+]])
  (:import (java.lang Process
                      ProcessBuilder
                      ProcessBuilder$Redirect)
           (java.io File
                    OutputStreamWriter)
           (java.util.concurrent TimeUnit)))

(def debug-buffer-size
  "Number of lines of stderr and stdout we store for debugging assistance"
  32)

(defn keywordize-keys-1
  "Converts keys to keywords at a single level of a map."
  [m]
  (persistent!
    (reduce (fn [m [k v]]
              (assoc! m (keyword k) v))
            (transient {})
            m)))

(defn parse-msg
  "We may be dealing in arbitrary JSON payloads, and coercing all keys to
  keywords may really mess up maps like {\"9\": true}. I don't know a rigorous
  way to eliminate this problem yet--schema.core's coercion might come in handy
  but I don't really underSTAND it yet. For right now, we convert just the keys
  in the message and body, to one level. Nothing is nested deeper than that
  anyway."
  [message]
  (-> message
      keywordize-keys-1
      (update :body keywordize-keys-1)))

(defn stderr-thread
  "Spawns a future which handles stderr from a process."
  [^Process p node-id debug-buffer log-writer log-stderr?]
  (future
    (with-thread-name (str node-id " stderr")
      (with-open [log log-writer]
        (doseq [line (bs/to-line-seq (.getErrorStream p))]
          ; Console log
          (when log-stderr? (info line))
          ; File log
          (.write log line)
          (.write log "\n")
          (.flush log)
          ; Debugging buffer log
          (swap! debug-buffer conj line)))
      :stderr-done)))

(defn stdout-thread
  "Spawns a future which reads stdout from a process and inserts messages into
  the network."
  [^Process p node-id debug-buffer net]
  (future
    (with-thread-name (str node-id " stdout")
      (try
        (doseq [line (bs/to-line-seq (.getInputStream p))]
          ; Debugging buffer
          (swap! debug-buffer conj line)
          ; Parse and insert into network
          (try
            (let [parsed (-> line json/parse-string parse-msg)]
              (try
                (net/send! net parsed)
                (catch java.lang.AssertionError e
                  (when-not (re-find #"Invalid dest" (.getMessage e))
                    (throw e))
                  (warn "Discarding message for nonexistent node"
                        (:dest parsed)))))
            (catch InterruptedException e
              (throw e))
            (catch java.io.IOException e)
            (catch Throwable e
              (warn e "error processing stdout:\n" line))))
        ; Interrupted? Shut down politely.
        (catch InterruptedException e))
      :stdout-done)))

(defn stdin-thread
  "Spawns a future which reads messages from the network and submits them to a
  process's stdin."
  [^Process p node-id net running?]
  (future
    (with-thread-name (str node-id " stdin")
      (try
        (with-open [w (OutputStreamWriter. (.getOutputStream p))]
          (while (deref running?)
            (try
              (when-let [msg (net/recv! net node-id 1000)]
                (json/generate-stream msg w)
                (.write w "\n")
                (.flush w))
              (catch java.io.IOException e)
              (catch InterruptedException e
                (throw e))
              (catch Throwable e
                (warn e "error processing stdin")))))
        ; Interrupted? Shut down politely.
        (catch InterruptedException e)
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
        _       (io/make-parents (:log-file opts))
        log     (io/writer (:log-file opts))
        bin     (.getCanonicalPath (io/file (:bin opts)))
        process (-> (ProcessBuilder. (cons bin (:args opts)))
                    (.directory (io/file (:dir opts)))
                    (.redirectOutput ProcessBuilder$Redirect/PIPE)
                    (.redirectInput  ProcessBuilder$Redirect/PIPE)
                    (.start))
        running? (atom true)
        stdout-debug-buffer (atom (ring-buffer/ring-buffer debug-buffer-size))
        stderr-debug-buffer (atom (ring-buffer/ring-buffer debug-buffer-size))]
    {:process       process
     :running?      running?
     :node-id       node-id
     :net           net
     :log           log
     :log-file      (:log-file opts)
     :stderr-debug-buffer stderr-debug-buffer
     :stdout-debug-buffer stdout-debug-buffer
     :stdin-thread  (stdin-thread  process node-id net running?)
     :stderr-thread (stderr-thread process node-id stderr-debug-buffer log
                                   (:log-stderr? opts))
     :stdout-thread (stdout-thread process node-id stdout-debug-buffer net)}))

(defn stop-node!
  "Kills a node. Throws if the node already exited."
  [{:keys [^Process process running? node-id net log-file log stdin-thread
           stderr-thread stdout-thread stderr-debug-buffer
           stdout-debug-buffer]}]
  (let [crashed? (not (.isAlive process))]
    (when-not crashed?
      ; Kill
      (.. ^Process process destroyForcibly (waitFor 5 TimeUnit/SECONDS)))

    ; Shut down workers
    (reset! running? false)
    (mapv deref [stdin-thread stderr-thread stdout-thread])

    ; Remove self from network
    (net/remove-node! net node-id)

    ; Close log writer
    (.close log)

    ; If we crashed, throw a nice exception
    (when crashed?
      (throw+ {:type :node-crashed
               :node node-id
               :exit (.exitValue process)}
              nil
              (str "Node " node-id " crashed with exit status "
                   (.exitValue process)
                   ". Before crashing, it wrote to STDOUT:\n\n"
                   (->> @stdout-debug-buffer (str/join "\n"))
                   "\n\nAnd to STDERR:\n\n"
                   (->> @stderr-debug-buffer (str/join "\n"))
                   "\n\n"
                   "Full STDERR logs are available in " log-file)))

    ; Return status of workers
    {:exit        (.exitValue process)
     :stdin       @stdin-thread
     :stderr      @stderr-thread
     :stdout      @stdout-thread}))
