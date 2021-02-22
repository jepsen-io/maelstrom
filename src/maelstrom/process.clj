(ns maelstrom.process
  "Handles process spawning and IO"
  (:require [amalloy.ring-buffer :as ring-buffer]
            [clojure [pprint :refer [pprint]]
                     [string :as str]]
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
  "Parses a line as a message, throwing appropriate exceptions.

  We may be dealing in arbitrary JSON payloads, and coercing all keys to
  keywords may really mess up maps like {\"9\": true}. I don't know a rigorous
  way to eliminate this problem yet--schema.core's coercion might come in handy
  but I don't really underSTAND it yet. For right now, we convert just the keys
  in the message and body, to one level. Nothing is nested deeper than that
  anyway."
  [node-id line]
  (let [parsed (try (json/parse-string line)
                    (catch com.fasterxml.jackson.core.JsonParseException e
                      (throw+ {:type :line-not-valid-json
                               :line line}
                              (str "Node " node-id
                                   " printed a line to STDOUT which was not well-formed JSON:\n" line "\nDid you mean to encode this line as JSON? Or was this line intended for STDERR? See doc/protocol.md for more guidance."))))
        ; Convert string keys to keywords
        message (-> parsed
                    keywordize-keys-1
                    (update :body keywordize-keys-1))]
    ; Validate
    (when-let [errors (net/check-message message)]
      (throw+ {:type    :malformed-message
               :message message
               :error   errors}
              (str "Malformed network message. Node " node-id
                   " tried to send the following message via STDOUT:\n\n"
                   (with-out-str (pprint message))
                   "\nThis is malformed because:\n\n"
                   (with-out-str (pprint errors))
                   "\nSee doc/protocol.md for more guidance.")))
    message))

(defmacro io-thread
  "Spawns an IO thread for a process. Takes a running? atom, a node id, a
  thread name (e.g. \"stdin\"), [sym closable-expression ...] bindings (for
  with-open), a single loop-recur binding, and a body. Spawns a future, holding
  the closeable open, evaluating body in the loop-recur bindings as long as
  `running?` is true, and catching/logging exceptions. Body should return the
  next value for the loop iteration, or `nil` to terminate."
  [running? node-id thread-type open-bindings loop-binding & body]
  `(future
     (with-thread-name (str ~node-id " " ~thread-type)
       (with-open ~open-bindings
         ; There is technically a race condition here: we might be interrupted
         ; during evaluation of the loop bindings, *before* we enter the try.
         ; Hopefully infrequent. If it happens, not the end of the world; just
         ; yields a confusing error message, maybe some weird premature
         ; closed-stream behavior.
         (loop ~loop-binding
           (if-not (deref ~running?)
             ; We're done
             :done
             (recur (try ~@body
                         (catch InterruptedException e#
                           ; We might be interrupted if setup fails, but it's
                           ; not our job to exit here--we need to keep the
                           ; process's streams up and running so we can tell if
                           ; it terminated normally. We'll be terminated by the
                           ; DB teardown process.
                           )
                         (catch Throwable t#
                           (warn t# "Error!"))))))))))

(defn stderr-thread
  "Spawns a future which handles stderr from a process."
  [^Process p running? node-id debug-buffer log-writer log-stderr?]
  (io-thread running? node-id "stderr"
             [log log-writer]
             [lines (bs/to-line-seq (.getErrorStream p))]
             (when (seq lines)
               (let [line (first lines)]
                 ; Console log
                 (when log-stderr? (info line))

                 ; Debugging buffer log
                 (swap! debug-buffer conj line)

                 ; File log
                 (.write log line)
                 (.write log "\n")
                 (.flush log)

                 (next lines)))))

(defn stdout-thread
  "Spawns a future which reads stdout from a process and inserts messages into
  the network."
  [^Process p running? node-id debug-buffer net]
  (io-thread running? node-id "stdout"
             []
             [lines (bs/to-line-seq (.getInputStream p))]
             (when (seq lines)
               (let [line (first lines)]
                 ; Parse and insert into network
                 (try+ (let [parsed (parse-msg node-id line)]
                         (net/send! net parsed)))

                 ; Debugging buffer
                 (swap! debug-buffer conj line)

                 (next lines)))))

(defn stdin-thread
  "Spawns a future which reads messages from the network and submits them to a
  process's stdin."
  [^Process p running? node-id net]
  (io-thread running? node-id "stdin"
             [w (OutputStreamWriter. (.getOutputStream p))]
             [_ true]
             (do (when-let [msg (net/recv! net node-id 1000)]
                   (json/generate-stream msg w)
                   (.write w "\n")
                   (.flush w))
                 ; We always recur; our input is unbounded.
                 true)))

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
     :stdin-thread  (stdin-thread  process running? node-id net)
     :stderr-thread (stderr-thread process running? node-id stderr-debug-buffer
                                   log (:log-stderr? opts))
     :stdout-thread (stdout-thread process running? node-id stdout-debug-buffer
                                   net)}))

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
