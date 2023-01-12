#!/usr/bin/env bb

(load-file (clojure.string/replace *file* #"/[^/]+$" "/node.clj"))

(ns maelstrom.demo.kafka
  "A kafka-like stream processing system which stores its data in lin-kv."
  (:require [maelstrom.demo.node :as node
             :refer [defhandler
                     then
                     exceptionally]]
            [slingshot.slingshot :refer [try+ throw+]]))

(def chunk-service
  "Where do we store our log chunks?"
  "lin-kv")

(def chunk-size
  "How many offsets do we store in a single lin-kv key?"
  32)

(def offset-service
  "Where do we store offsets?"
  "lin-kv")

(def offset-key
  "What key do we store offsets under?"
  "offsets")

(def offset-cache
  "A map of log keys to what we think the next empty offset is."
  (atom {}))

(defn bump-offset-cache!
  "Inform the offset cache that for key log-key, the next free offset is at
  least offset."
  [log-key offset]
  (node/log "Bump offset cache for" log-key "to" offset)
  (swap! offset-cache
         (fn [oc]
           (let [o  (get oc log-key 0)
                 o' (max o offset)]
             (assoc oc log-key o'))))
  (node/log "Offset cache: " @offset-cache))

(defn chunk-key
  "Takes a log key and an offset, and returns a key in lin-kv that we use to
  store that log."
  [log-key offset]
  (str "log-" log-key "-" (-> offset
                              (- (mod offset chunk-size))
                              (/ chunk-size))))

(defn get-chunk
  "Fetches the chunk for a log key and offset. Returns a future."
  [log-key offset]
  (-> (node/rpc! chunk-service
                 {:type "read", :key (chunk-key log-key offset)})
      (then [res]
            (let [chunk (:value res)]
              ; Every time we read a chunk, advance the offset cache
              (bump-offset-cache! log-key (-> offset
                                              (- (mod offset chunk-size))
                                              (+ (count chunk))))
              chunk))))

(defn try-append!
  "Tries to append a message to a log-key via CaS on that log chunk. Returns
  a future of the offset it was appended to."
  [log-key msg]
  (let [offset (get @offset-cache log-key 0)
        chunk  (-> (get-chunk log-key offset)
                   (exceptionally [_] [])
                   deref)
        i      (count chunk)]
    (if (<= chunk-size i)
      ; Chunk full! Bump our offset and retry.
      (do (swap! offset-cache update log-key max
                 (-> offset
                     (- (mod offset chunk-size))
                     (+ chunk-size)))
          (recur log-key msg))
      ; Chunk not full; append and CaS
      (-> (node/rpc! chunk-service {:type "cas"
                                    :key  (chunk-key log-key offset)
                                    :from chunk
                                    :to   (conj chunk msg)
                                    :create_if_not_exists true})
          (then [res]
                (let [offset' (-> offset
                                  (- (mod offset chunk-size))
                                  (+ i))]
                  (bump-offset-cache! log-key (inc offset'))
                  offset'))))))

(defhandler send
  "When a client asks to send a message to a key, we try appending it, and if
  that succeeds, return a reply."
  [req]
  (let [{:keys [key msg]} (:body req)]
    (try+
      (-> (try-append! key msg)
          (then [offset]
                (node/reply! req {:type "send_ok", :offset offset}))
          deref)
      (catch [:code 22] _
        (throw+ {:code 30} nil "cas conflict")))))

(defhandler poll
  "When a client asks to poll a key, we fetch the chunk for that offset and
  return everything after it."
  [req]
  (let [offsets (:offsets (:body req))
        ; Spawn requests for all chunks in parallel
        chunks (map (fn [[k offset]]
                      {:key    k
                       :offset offset
                       :chunk  (get-chunk k offset)})
                    offsets)
        ; Zip together into response
        msgs (->> chunks
                  (map (fn [{:keys [key offset chunk]}]
                         ; Where should we start in the chunk?
                         (let [i0 (mod offset chunk-size)]
                           [key (map vector
                                     (iterate inc offset)
                                     (drop i0 (-> chunk
                                                  (exceptionally [_] [])
                                                  deref)))])))
                 (into {}))]
    (node/reply! req
                 {:type "poll_ok"
                  :msgs msgs})))

(defn get-offsets
  "Fetches the offset map from storage. Returns a future."
  []
  (-> (node/rpc! offset-service {:type "read", :key offset-key})
      (then [res] (:value res))
      (exceptionally [res]
                     {})))

(defhandler list_committed_offsets
  "We fetch the committed offsets map from storage, and filter it to just the
  requested keys."
  [{:keys [body] :as req}]
  (-> (get-offsets)
      (then [offsets]
            (node/reply! req
                         {:type    "list_committed_offsets_ok"
                          :offsets (select-keys offsets (:keys body))}))
      deref))

(defhandler commit_offsets
  "To commit offsets, we CaS the offsets key, advancing offsets to their
  maxima."
  [{:keys [body] :as req}]
  (let [offsets  @(get-offsets)
        offsets' (merge-with max offsets (:offsets body))]
    (try+
      (-> (node/rpc! offset-service
                     {:type "cas"
                      :key offset-key
                      :from offsets
                      :to offsets'
                      :create_if_not_exists true})
          (then [res]
                (node/reply! req {:type "commit_offsets_ok"}))
          deref)
      (catch [:code 22] _
        (throw+ {:code 30} nil "cas conflict")))))

(node/start!)
