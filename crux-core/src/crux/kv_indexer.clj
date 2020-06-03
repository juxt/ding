(ns ^:no-doc crux.kv-indexer
  (:require [crux.codec :as c]
            [crux.db :as db]
            [crux.io :as cio]
            [crux.kv :as kv]
            [crux.memory :as mem]
            [crux.status :as status]
            [crux.morton :as morton])
  (:import (crux.codec Id EntityTx)
           crux.api.IndexVersionOutOfSyncException
           java.io.Closeable
           java.nio.ByteOrder
           [java.util Date HashMap Map]
           java.util.function.Supplier
           java.util.concurrent.atomic.AtomicBoolean
           (clojure.lang MapEntry)
           (org.agrona DirectBuffer MutableDirectBuffer ExpandableDirectByteBuffer)))

(set! *unchecked-math* :warn-on-boxed)

(def ^:private ^ThreadLocal seek-buffer-tl
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_]
       (ExpandableDirectByteBuffer.)))))

;; NOTE: A buffer returned from an kv/KvIterator can only be assumed
;; to be valid until the next call on the same iterator. In practice
;; this limitation is only for RocksJNRKv.
;; TODO: It would be nice to make this explicit somehow.

(defrecord PrefixKvIterator [i ^DirectBuffer prefix]
  kv/KvIterator
  (seek [_ k]
    (when-let [k (kv/seek i k)]
      (when (mem/buffers=? k prefix (.capacity prefix))
        k)))

  (next [_]
    (when-let [k (kv/next i)]
      (when (mem/buffers=? k prefix (.capacity prefix))
        k)))

  (value [_]
    (kv/value i))

  Closeable
  (close [_]
    (.close ^Closeable i)))

(defn new-prefix-kv-iterator ^java.io.Closeable [i prefix]
  (->PrefixKvIterator i prefix))

(defn all-keys-in-prefix
  ([i ^DirectBuffer prefix] (all-keys-in-prefix i prefix (.capacity prefix) {}))
  ([i seek-k prefix-length] (all-keys-in-prefix i seek-k prefix-length {}))
  ([i ^DirectBuffer seek-k, prefix-length {:keys [entries? reverse?]}]
   (letfn [(step [k]
             (lazy-seq
              (when (and k (mem/buffers=? seek-k k prefix-length))
                (cons (if entries?
                        (MapEntry/create (mem/copy-to-unpooled-buffer k) (mem/copy-to-unpooled-buffer (kv/value i)))
                        (mem/copy-to-unpooled-buffer k))
                      (step (if reverse? (kv/prev i) (kv/next i)))))))]
     (step (if reverse?
             (when (kv/seek i (-> seek-k (mem/copy-buffer) (mem/inc-unsigned-buffer!)))
               (kv/prev i))
             (kv/seek i seek-k))))))

(defn- buffer-or-value-buffer [v]
  (cond
    (instance? DirectBuffer v)
    v

    (some? v)
    (c/->value-buffer v)

    :else
    c/empty-buffer))

(defn- buffer-or-id-buffer [v]
  (cond
    (instance? DirectBuffer v)
    v

    (some? v)
    (c/->id-buffer v)

    :else
    c/empty-buffer))

(defn- inc-unsigned-prefix-buffer [buffer prefix-size]
  (mem/inc-unsigned-buffer! (mem/limit-buffer (mem/copy-buffer buffer prefix-size (.get seek-buffer-tl)) prefix-size)))

(defn ^EntityTx enrich-entity-tx [entity-tx ^DirectBuffer content-hash]
  (assoc entity-tx :content-hash (when (pos? (.capacity content-hash))
                                   (c/safe-id (c/new-id content-hash)))))

(defn safe-entity-tx ^crux.codec.EntityTx [entity-tx]
  (-> entity-tx
      (update :eid c/safe-id)
      (update :content-hash c/safe-id)))

(defrecord Quad [attr eid content-hash value])

;;;; Content indices

(defn encode-ave-key-to
  (^org.agrona.MutableDirectBuffer[b attr]
   (encode-ave-key-to b attr c/empty-buffer c/empty-buffer))
  (^org.agrona.MutableDirectBuffer[b attr v]
   (encode-ave-key-to b attr v c/empty-buffer))
  (^org.agrona.MutableDirectBuffer
   [^MutableDirectBuffer b ^DirectBuffer attr ^DirectBuffer v ^DirectBuffer entity]
   (assert (= c/id-size (.capacity attr)) (mem/buffer->hex attr))
   (assert (or (= c/id-size (.capacity entity))
               (zero? (.capacity entity))) (mem/buffer->hex entity))
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (+ c/index-id-size c/id-size (.capacity v) (.capacity entity))))]
     (mem/limit-buffer
      (doto b
        (.putByte 0 c/ave-index-id)
        (.putBytes c/index-id-size attr 0 c/id-size)
        (.putBytes (+ c/index-id-size c/id-size) v 0 (.capacity v))
        (.putBytes (+ c/index-id-size c/id-size (.capacity v)) entity 0 (.capacity entity)))
      (+ c/index-id-size c/id-size (.capacity v) (.capacity entity))))))

(defn decode-ave-key-from ^crux.kv_indexer.Quad [^DirectBuffer k]
  (let [length (long (.capacity k))]
    (assert (<= (+ c/index-id-size c/id-size c/id-size) length) (mem/buffer->hex k))
    (let [index-id (.getByte k 0)]
      (assert (= c/ave-index-id index-id))
      (let [value-size (- length c/id-size c/id-size c/index-id-size)
            attr (Id. (mem/slice-buffer k c/index-id-size c/id-size) 0)
            value (mem/slice-buffer k (+ c/index-id-size c/id-size) value-size)
            entity (Id. (mem/slice-buffer k (+ c/index-id-size c/id-size value-size) c/id-size) 0)]
        (->Quad attr entity nil value)))))

(defn decode-ave-key-to-v-from ^org.agrona.DirectBuffer [^DirectBuffer k]
  (let [value-size (- (.capacity k) c/id-size c/id-size c/index-id-size)]
    (mem/slice-buffer k (+ c/index-id-size c/id-size) value-size)))

(defn decode-ave-key-to-e-from ^org.agrona.DirectBuffer [^DirectBuffer k]
  (let [value-size (- (.capacity k) c/id-size c/id-size c/index-id-size)]
    (mem/slice-buffer k (+ c/index-id-size c/id-size value-size) c/id-size)))

(defn encode-aecv-key-to
  (^org.agrona.MutableDirectBuffer [b]
   (encode-aecv-key-to b c/empty-buffer c/empty-buffer c/empty-buffer c/empty-buffer))
  (^org.agrona.MutableDirectBuffer [b attr]
   (encode-aecv-key-to b attr c/empty-buffer c/empty-buffer c/empty-buffer))
  (^org.agrona.MutableDirectBuffer [b attr entity]
   (encode-aecv-key-to b attr entity c/empty-buffer c/empty-buffer))
  (^org.agrona.MutableDirectBuffer [b attr entity content-hash]
   (encode-aecv-key-to b attr entity content-hash c/empty-buffer))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^DirectBuffer attr ^DirectBuffer entity ^DirectBuffer content-hash ^DirectBuffer v]
   (assert (or (zero? (.capacity attr)) (= c/id-size (.capacity attr)))
           (mem/buffer->hex attr))
   (assert (or (zero? (.capacity entity)) (= c/id-size (.capacity entity)))
           (mem/buffer->hex entity))
   (assert (or (zero? (.capacity content-hash)) (= c/id-size (.capacity content-hash)))
           (mem/buffer->hex content-hash))
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (+ c/index-id-size (.capacity attr) (.capacity entity) (.capacity content-hash) (.capacity v))))]
     (-> (doto b
           (.putByte 0 c/aecv-index-id)
           (.putBytes c/index-id-size attr 0 (.capacity attr))
           (.putBytes (+ c/index-id-size (.capacity attr)) entity 0 (.capacity entity))
           (.putBytes (+ c/index-id-size (.capacity attr) (.capacity entity)) content-hash 0 (.capacity content-hash))
           (.putBytes (+ c/index-id-size (.capacity attr) (.capacity entity) (.capacity content-hash)) v 0 (.capacity v)))
         (mem/limit-buffer (+ c/index-id-size (.capacity attr) (.capacity entity) (.capacity content-hash) (.capacity v)))))))

(defn decode-aecv-key-from ^crux.kv_indexer.Quad [^DirectBuffer k]
  (let [length (long (.capacity k))]
    (assert (<= (+ c/index-id-size c/id-size c/id-size) length) (mem/buffer->hex k))
    (let [index-id (.getByte k 0)]
      (assert (= c/aecv-index-id index-id))
      (let [value-size (- length c/id-size c/id-size c/id-size c/index-id-size)
            attr (Id. (mem/slice-buffer k c/index-id-size c/id-size) 0)
            entity (Id. (mem/slice-buffer k (+ c/index-id-size c/id-size) c/id-size) 0)
            content-hash (Id. (mem/slice-buffer k (+ c/index-id-size c/id-size c/id-size) c/id-size) 0)
            value (mem/slice-buffer k (+ c/index-id-size c/id-size c/id-size c/id-size) value-size)]
        (->Quad attr entity content-hash value)))))

(defn decode-aecv-key-to-e-from ^org.agrona.DirectBuffer [^DirectBuffer k]
  (mem/slice-buffer k (+ c/index-id-size c/id-size) c/id-size))

(defn decode-aecv-key-to-v-from ^org.agrona.DirectBuffer [^DirectBuffer k]
  (let [value-size (- (.capacity k) c/id-size c/id-size c/id-size c/index-id-size)]
    (mem/slice-buffer k (+ c/index-id-size c/id-size c/id-size c/id-size) value-size)))

(defn all-attrs [i]
  (let [seek-buffer (.get seek-buffer-tl)
        aecv-prefix (encode-aecv-key-to seek-buffer)
        i (new-prefix-kv-iterator i aecv-prefix)]
    (letfn [(step [k]
              (lazy-seq
               (when-let [^DirectBuffer k (kv/seek i k)]
                 (cons (Id. (mem/slice-buffer k c/index-id-size c/id-size) 0)
                       (step (-> (mem/copy-buffer k (.capacity k) seek-buffer)
                                 (mem/limit-buffer (+ c/index-id-size c/id-size))
                                 (mem/inc-unsigned-buffer!)))))))]
      (step aecv-prefix))))

(defn encode-hash-cache-key-to
  (^org.agrona.MutableDirectBuffer [b value]
   (encode-hash-cache-key-to b value c/empty-buffer))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^DirectBuffer value ^DirectBuffer entity]
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (+ c/index-id-size (.capacity value) (.capacity entity))))]
     (-> (doto b
           (.putByte 0 c/hash-cache-index-id)
           (.putBytes c/index-id-size value 0 (.capacity value))
           (.putBytes (+ c/index-id-size (.capacity value)) entity 0 (.capacity entity)))
         (mem/limit-buffer (+ c/index-id-size (.capacity value) (.capacity entity)))))))

;;;; Bitemp indices

(defn encode-entity+vt+tt+tx-id-key-to
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b]
   (encode-entity+vt+tt+tx-id-key-to b c/empty-buffer nil nil nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b entity]
   (encode-entity+vt+tt+tx-id-key-to b entity nil nil nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b entity valid-time]
   (encode-entity+vt+tt+tx-id-key-to b entity valid-time nil nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^DirectBuffer entity ^Date valid-time ^Date transact-time ^Long tx-id]
   (assert (or (= c/id-size (.capacity entity))
               (zero? (.capacity entity))) (mem/buffer->hex entity))
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (cond-> (+ c/index-id-size (.capacity entity))
                                                             valid-time (+ Long/BYTES)
                                                             transact-time (+ Long/BYTES)
                                                             tx-id (+ Long/BYTES))))]
     (.putByte b 0 c/entity+vt+tt+tx-id->content-hash-index-id)
     (.putBytes b c/index-id-size entity 0 (.capacity entity))
     (when valid-time
       (.putLong b (+ c/index-id-size c/id-size) (c/date->reverse-time-ms valid-time) ByteOrder/BIG_ENDIAN))
     (when transact-time
       (.putLong b (+ c/index-id-size c/id-size Long/BYTES) (c/date->reverse-time-ms transact-time) ByteOrder/BIG_ENDIAN))
     (when tx-id
       (.putLong b (+ c/index-id-size c/id-size Long/BYTES Long/BYTES) (c/descending-long tx-id) ByteOrder/BIG_ENDIAN))
     (->> (+ c/index-id-size (.capacity entity)
             (c/maybe-long-size valid-time) (c/maybe-long-size transact-time) (c/maybe-long-size tx-id))
          (mem/limit-buffer b)))))

(defn decode-entity+vt+tt+tx-id-key-from ^crux.codec.EntityTx [^DirectBuffer k]
  (assert (= (+ c/index-id-size c/id-size Long/BYTES Long/BYTES Long/BYTES) (.capacity k)) (mem/buffer->hex k))
  (let [index-id (.getByte k 0)]
    (assert (= c/entity+vt+tt+tx-id->content-hash-index-id index-id))
    (let [entity (Id. (mem/slice-buffer k c/index-id-size c/id-size) 0)
          valid-time (c/reverse-time-ms->date (.getLong k (+ c/index-id-size c/id-size) ByteOrder/BIG_ENDIAN))
          transact-time (c/reverse-time-ms->date (.getLong k (+ c/index-id-size c/id-size Long/BYTES) ByteOrder/BIG_ENDIAN))
          tx-id (c/descending-long (.getLong k (+ c/index-id-size c/id-size Long/BYTES Long/BYTES) ByteOrder/BIG_ENDIAN))]
      (c/->EntityTx entity valid-time transact-time tx-id nil))))

(defn decode-entity+vt+tt+tx-id-key-as-tt-from ^java.util.Date [^DirectBuffer k]
  (c/reverse-time-ms->date (.getLong k (+ c/index-id-size c/id-size Long/BYTES) ByteOrder/BIG_ENDIAN)))

(defn encode-entity-tx-z-number [valid-time transaction-time]
  (morton/longs->morton-number (c/date->reverse-time-ms valid-time)
                               (c/date->reverse-time-ms transaction-time)))

(defn encode-entity+z+tx-id-key-to
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b]
   (encode-entity+z+tx-id-key-to b c/empty-buffer nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b entity]
   (encode-entity+z+tx-id-key-to b entity nil nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b entity z]
   (encode-entity+z+tx-id-key-to b entity z nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^DirectBuffer entity z ^Long tx-id]
   (assert (or (= c/id-size (.capacity entity))
               (zero? (.capacity entity))) (mem/buffer->hex entity))
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (cond-> (+ c/index-id-size (.capacity entity))
                                                             z (+ (* 2 Long/BYTES))
                                                             tx-id (+ Long/BYTES))))
         [upper-morton lower-morton] (when z
                                       (morton/morton-number->interleaved-longs z))]
     (.putByte b 0 c/entity+z+tx-id->content-hash-index-id)
     (.putBytes b c/index-id-size entity 0 (.capacity entity))
     (when z
       (.putLong b (+ c/index-id-size c/id-size) upper-morton ByteOrder/BIG_ENDIAN)
       (.putLong b (+ c/index-id-size c/id-size Long/BYTES) lower-morton ByteOrder/BIG_ENDIAN))
     (when tx-id
       (.putLong b (+ c/index-id-size c/id-size Long/BYTES Long/BYTES) (c/descending-long tx-id) ByteOrder/BIG_ENDIAN))
     (->> (+ c/index-id-size (.capacity entity) (if z (* 2 Long/BYTES) 0) (c/maybe-long-size tx-id))
          (mem/limit-buffer b)))))

(defn decode-entity+z+tx-id-key-as-z-number-from [^DirectBuffer k]
  (assert (= (+ c/index-id-size c/id-size Long/BYTES Long/BYTES Long/BYTES) (.capacity k)) (mem/buffer->hex k))
  (let [index-id (.getByte k 0)]
    (assert (= c/entity+z+tx-id->content-hash-index-id index-id))
    (morton/interleaved-longs->morton-number
     (.getLong k (+ c/index-id-size c/id-size) ByteOrder/BIG_ENDIAN)
     (.getLong k (+ c/index-id-size c/id-size Long/BYTES) ByteOrder/BIG_ENDIAN))))

(defn decode-entity+z+tx-id-key-from ^crux.codec.EntityTx [^DirectBuffer k]
  (assert (= (+ c/index-id-size c/id-size Long/BYTES Long/BYTES Long/BYTES) (.capacity k)) (mem/buffer->hex k))
  (let [index-id (.getByte k 0)]
    (assert (= c/entity+z+tx-id->content-hash-index-id index-id))
    (let [entity (Id. (mem/slice-buffer k c/index-id-size c/id-size) 0)
          [valid-time transaction-time] (morton/morton-number->longs (decode-entity+z+tx-id-key-as-z-number-from k))
          tx-id (c/descending-long (.getLong k (+ c/index-id-size c/id-size Long/BYTES Long/BYTES) ByteOrder/BIG_ENDIAN))]
      (c/->EntityTx entity (c/reverse-time-ms->date valid-time) (c/reverse-time-ms->date transaction-time) tx-id nil))))

;; Index Version

(defn- encode-index-version-key-to ^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b]
  (let [^MutableDirectBuffer b (or b (mem/allocate-buffer c/index-id-size))]
    (.putByte b 0 c/index-version-index-id)
    (mem/limit-buffer b c/index-id-size)))

(defn- encode-index-version-value-to ^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^long version]
  (let [^MutableDirectBuffer b (or b (mem/allocate-buffer c/index-version-size))]
    (doto b
      (.putLong 0 version ByteOrder/BIG_ENDIAN))
    (mem/limit-buffer b c/index-version-size)))

(defn- decode-index-version-value-from ^long [^MutableDirectBuffer b]
  (.getLong b 0 ByteOrder/BIG_ENDIAN))

(defn current-index-version [kv]
  (with-open [snapshot (kv/new-snapshot kv)]
    (some->> (kv/get-value snapshot (encode-index-version-key-to (.get seek-buffer-tl)))
             (decode-index-version-value-from))))

(defn check-and-store-index-version [kv]
  (if-let [index-version (current-index-version kv)]
    (when (not= c/index-version index-version)
      (throw (IndexVersionOutOfSyncException.
              (str "Index version on disk: " index-version " does not match index version of code: " c/index-version))))
    (doto kv
      (kv/store [[(encode-index-version-key-to nil)
                  (encode-index-version-value-to nil c/index-version)]])
      (kv/fsync)))
  kv)

(defn- check-z-curve-index-config [kv z-curve-index?]
  (with-open [snapshot (kv/new-snapshot kv)
              i (kv/new-iterator snapshot)]
    (let [z-present? (boolean (kv/seek i (encode-entity+z+tx-id-key-to (.get seek-buffer-tl))))
          bitemp-present? (boolean (kv/seek i (encode-entity+vt+tt+tx-id-key-to (.get seek-buffer-tl))))]
      (when (and z-curve-index?
                 bitemp-present?
                 (not z-present?))
        (throw (IllegalArgumentException. "Can't add a z-curve index to an existing node"))))))

;; Meta

(defn encode-meta-key-to ^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b ^DirectBuffer k]
  (assert (= c/id-size (.capacity k)) (mem/buffer->hex k))
  (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (+ c/index-id-size c/id-size)))]
    (mem/limit-buffer
     (doto b
       (.putByte 0 c/meta-key->value-index-id)
       (.putBytes c/index-id-size k 0 (.capacity k)))
     (+ c/index-id-size c/id-size))))

(defn meta-kv [k v]
  [(encode-meta-key-to (.get seek-buffer-tl) (c/->id-buffer k))
   (mem/->nippy-buffer v)])

(defn store-meta [kv k v]
  (kv/store kv [(meta-kv k v)]))

(defn read-meta [kv k]
  (let [seek-k (encode-meta-key-to (.get seek-buffer-tl) (c/->id-buffer k))]
    (with-open [snapshot (kv/new-snapshot kv)]
      (some->> (kv/get-value snapshot seek-k)
               (mem/<-nippy-buffer)))))

;;;; Failed tx-id

(defn encode-failed-tx-id-key-to
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b]
   (encode-failed-tx-id-key-to b nil))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer b tx-id]
   (let [^MutableDirectBuffer b (or b (mem/allocate-buffer (+ c/index-id-size (c/maybe-long-size tx-id))))]
     (.putByte b 0 c/failed-tx-id-index-id)
     (when tx-id
       (.putLong b c/index-id-size (c/descending-long tx-id) ByteOrder/BIG_ENDIAN))
     (mem/limit-buffer b (+ c/index-id-size (c/maybe-long-size tx-id))))))

;;;; Entity as-of

(defn- find-first-entity-tx-within-range [i min max eid]
  (let [prefix-size (+ c/index-id-size c/id-size)
        seek-k (encode-entity+z+tx-id-key-to (.get seek-buffer-tl)
                                             eid
                                             min)]
    (loop [k (kv/seek i seek-k)]
      (when (and k (mem/buffers=? seek-k k prefix-size))
        (let [z (decode-entity+z+tx-id-key-as-z-number-from k)]
          (if (morton/morton-number-within-range? min max z)
            (let [entity-tx (safe-entity-tx (decode-entity+z+tx-id-key-from k))
                  v (kv/value i)]
              (if-not (mem/buffers=? c/nil-id-buffer v)
                [(c/->id-buffer (.eid entity-tx))
                 (enrich-entity-tx entity-tx v)
                 z]
                [::deleted-entity entity-tx z]))
            (let [[litmax bigmin] (morton/morton-range-search min max z)]
              (when-not (neg? (.compareTo ^Comparable bigmin z))
                (recur (kv/seek i (encode-entity+z+tx-id-key-to (.get seek-buffer-tl)
                                                                eid
                                                                bigmin)))))))))))

(defn- find-entity-tx-within-range-with-highest-valid-time [i min max eid prev-candidate]
  (if-let [[_ ^EntityTx entity-tx z :as candidate] (find-first-entity-tx-within-range i min max eid)]
    (let [[^long x ^long y] (morton/morton-number->longs z)
          min-x (long (first (morton/morton-number->longs min)))
          max-x (dec x)]
      (if (and (not (pos? (Long/compareUnsigned min-x max-x)))
               (not= y -1))
        (let [min (morton/longs->morton-number
                   min-x
                   (unchecked-inc y))
              max (morton/longs->morton-number
                   max-x
                   -1)]
          (recur i min max eid candidate))
        candidate))
    prev-candidate))

;;;; History

(defn- ->entity-tx [[k v]]
  (-> (decode-entity+vt+tt+tx-id-key-from k)
      (enrich-entity-tx v)))

(defn entity-history-seq-ascending
  ([i eid] ([i eid] (entity-history-seq-ascending i eid {})))
  ([i eid {{^Date start-vt :crux.db/valid-time, ^Date start-tt :crux.tx/tx-time} :start
           {^Date end-vt :crux.db/valid-time, ^Date end-tt :crux.tx/tx-time} :end
           :keys [with-corrections?]}]
   (let [seek-k (encode-entity+vt+tt+tx-id-key-to nil (c/->id-buffer eid) start-vt)]
     (-> (all-keys-in-prefix i seek-k (+ c/index-id-size c/id-size)
                             {:reverse? true, :entries? true})
         (->> (map ->entity-tx))
         (cond->> end-vt (take-while (fn [^EntityTx entity-tx]
                                       (neg? (compare (.vt entity-tx) end-vt))))
                  start-tt (remove (fn [^EntityTx entity-tx]
                                     (neg? (compare (.tt entity-tx) start-tt))))
                  end-tt (filter (fn [^EntityTx entity-tx]
                                   (neg? (compare (.tt entity-tx) end-tt)))))
         (cond-> (not with-corrections?) (->> (partition-by :vt)
                                              (map last)))))))

(defn entity-history-seq-descending
  ([i eid] (entity-history-seq-descending i eid {}))
  ([i eid {{^Date start-vt :crux.db/valid-time, ^Date start-tt :crux.tx/tx-time} :start
           {^Date end-vt :crux.db/valid-time, ^Date end-tt :crux.tx/tx-time} :end
           :keys [with-corrections?]}]
   (let [seek-k (encode-entity+vt+tt+tx-id-key-to nil (c/->id-buffer eid) start-vt)]
     (-> (all-keys-in-prefix i seek-k (+ c/index-id-size c/id-size)
                             {:entries? true})
         (->> (map ->entity-tx))
         (cond->> end-vt (take-while (fn [^EntityTx entity-tx]
                                         (pos? (compare (.vt entity-tx) end-vt))))
                  start-tt (remove (fn [^EntityTx entity-tx]
                                    (pos? (compare (.tt entity-tx) start-tt))))
                  end-tt (filter (fn [^EntityTx entity-tx]
                                   (pos? (compare (.tt entity-tx) end-tt)))))
         (cond-> (not with-corrections?) (->> (partition-by :vt)
                                              (map first)))))))

;;;; IndexStore

(declare new-kv-index-store)

(defrecord KvIndexStore [snapshot
                         close-snapshot?
                         z-curve-index?
                         level-1-iterator-delay
                         level-2-iterator-delay
                         entity-as-of-iterator-delay
                         decode-value-iterator-delay
                         nested-index-store-state
                         ^Map temp-hash-cache
                         ^AtomicBoolean closed?]
  Closeable
  (close [_]
    (when (.compareAndSet closed? false true)
      (doseq [nested-index-store @nested-index-store-state]
        (cio/try-close nested-index-store))
      (doseq [i [level-1-iterator-delay level-2-iterator-delay entity-as-of-iterator-delay decode-value-iterator-delay]
              :when (realized? i)]
        (cio/try-close @i))
      (when close-snapshot?
        (cio/try-close snapshot))))

  db/IndexStore
  (av [this a min-v entity-resolver-fn]
    (let [attr-buffer (c/->id-buffer a)
          prefix (encode-ave-key-to nil attr-buffer)
          i (new-prefix-kv-iterator @level-1-iterator-delay prefix)]
      (some->> (encode-ave-key-to (.get seek-buffer-tl)
                                  attr-buffer
                                  (buffer-or-value-buffer min-v))
               (kv/seek i)
               ((fn step [^DirectBuffer k]
                  (when k
                    (cons (decode-ave-key-to-v-from k)
                          (lazy-seq
                           (some->> (inc-unsigned-prefix-buffer k (- (.capacity k) c/id-size))
                                    (kv/seek i)
                                    (step))))))))))

  (ave [this a v min-e entity-resolver-fn]
    (let [attr-buffer (c/->id-buffer a)
          value-buffer (buffer-or-value-buffer v)
          prefix (encode-ave-key-to nil attr-buffer value-buffer)
          i (new-prefix-kv-iterator @level-2-iterator-delay prefix)]
      (some->> (encode-ave-key-to (.get seek-buffer-tl)
                                  attr-buffer
                                  value-buffer
                                  (buffer-or-id-buffer min-e))
               (kv/seek i)
               ((fn step [^DirectBuffer k]
                  (when k
                    (let [eid-buffer (decode-ave-key-to-e-from k)
                          head (when-let [content-hash-buffer (entity-resolver-fn eid-buffer)]
                                 (let [version-k (encode-aecv-key-to (.get seek-buffer-tl)
                                                                     attr-buffer
                                                                     eid-buffer
                                                                     content-hash-buffer
                                                                     value-buffer)]
                                   (when (kv/get-value snapshot version-k)
                                     eid-buffer)))
                          tail (lazy-seq
                                (some->> (inc-unsigned-prefix-buffer k (.capacity k))
                                         (kv/seek i)
                                         (step)))]

                      (if head
                        (cons head tail)
                        tail))))))))

  (ae [this a min-e entity-resolver-fn]
    (let [attr-buffer (c/->id-buffer a)
          prefix (encode-aecv-key-to nil attr-buffer)
          i (new-prefix-kv-iterator @level-1-iterator-delay prefix)]
      (some->> (encode-aecv-key-to (.get seek-buffer-tl)
                                   attr-buffer
                                   (buffer-or-id-buffer min-e))
               (kv/seek i)
               ((fn step [^DirectBuffer k]
                  (when k
                    (let [eid-buffer (decode-aecv-key-to-e-from k)
                          tail (lazy-seq
                                (some->> (inc-unsigned-prefix-buffer k (- (.capacity k) c/id-size c/id-size))
                                         (kv/seek i)
                                         (step)))]
                      (if (entity-resolver-fn eid-buffer)
                        (cons eid-buffer tail)
                        tail))))))))

  (aev [this a e min-v entity-resolver-fn]
    (let [attr-buffer (c/->id-buffer a)
          eid-buffer (buffer-or-id-buffer e)]
      (when-let [content-hash-buffer (entity-resolver-fn eid-buffer)]
        (let [prefix (encode-aecv-key-to nil attr-buffer eid-buffer content-hash-buffer)
              i (new-prefix-kv-iterator @level-2-iterator-delay prefix)]
          (some->> (encode-aecv-key-to
                    (.get seek-buffer-tl)
                    attr-buffer
                    eid-buffer
                    content-hash-buffer
                    (buffer-or-value-buffer min-v))
                   (kv/seek i)
                   ((fn step [^DirectBuffer k]
                      (when k
                        (cons (decode-aecv-key-to-v-from k)
                              (lazy-seq (step (kv/next i))))))))))))

  (entity-as-of-resolver [this eid valid-time transact-time]
    (let [i @entity-as-of-iterator-delay
          prefix-size (+ c/index-id-size c/id-size)
          eid-buffer (c/->id-buffer eid)
          seek-k (encode-entity+vt+tt+tx-id-key-to (.get seek-buffer-tl)
                                                   eid-buffer
                                                   valid-time
                                                   transact-time
                                                   nil)]
      (loop [k (kv/seek i seek-k)]
        (when (and k (mem/buffers=? seek-k k prefix-size))
          (if (<= (compare (decode-entity+vt+tt+tx-id-key-as-tt-from k) transact-time) 0)
            (let [v (kv/value i)]
              (when-not (mem/buffers=? c/nil-id-buffer v)
                v))
            (if z-curve-index?
              (let [seek-z (encode-entity-tx-z-number valid-time transact-time)]
                (when-let [[k v] (find-entity-tx-within-range-with-highest-valid-time i seek-z morton/z-max-mask eid-buffer nil)]
                  (when-not (= ::deleted-entity k)
                    (c/->id-buffer (.content-hash ^EntityTx v)))))
              (recur (kv/next i))))))))

  (entity-as-of [this eid valid-time transact-time]
    (let [i @entity-as-of-iterator-delay
          prefix-size (+ c/index-id-size c/id-size)
          eid-buffer (c/->id-buffer eid)
          seek-k (encode-entity+vt+tt+tx-id-key-to (.get seek-buffer-tl)
                                                   eid-buffer
                                                   valid-time
                                                   transact-time
                                                   nil)]
      (loop [k (kv/seek i seek-k)]
        (when (and k (mem/buffers=? seek-k k prefix-size))
          (let [entity-tx (safe-entity-tx (decode-entity+vt+tt+tx-id-key-from k))
                v (kv/value i)]
            (if (<= (compare (.tt entity-tx) transact-time) 0)
              (when-not (mem/buffers=? c/nil-id-buffer v)
                (enrich-entity-tx entity-tx v))
              (if z-curve-index?
                (let [seek-z (encode-entity-tx-z-number valid-time transact-time)]
                  (when-let [[k v] (find-entity-tx-within-range-with-highest-valid-time i seek-z morton/z-max-mask eid-buffer nil)]
                    (when-not (= ::deleted-entity k)
                      v)))
                (recur (kv/next i)))))))))

  (open-entity-history [this eid sort-order opts]
    (let [i (kv/new-iterator snapshot)
          entity-history-seq (case sort-order
                               :asc entity-history-seq-ascending
                               :desc entity-history-seq-descending)]
      (cio/->cursor #(.close i)
                    (entity-history-seq i eid opts))))

  (decode-value [this value-buffer]
    (assert (some? value-buffer))
    (if (c/can-decode-value-buffer? value-buffer)
      (c/decode-value-buffer value-buffer)
      (or (.get temp-hash-cache value-buffer)
          (let [i @decode-value-iterator-delay
                hash-cache-prefix-key (encode-hash-cache-key-to (.get seek-buffer-tl) value-buffer)
                found-k (kv/seek i hash-cache-prefix-key)]
            (when (mem/buffers=? found-k hash-cache-prefix-key (.capacity hash-cache-prefix-key))
              (some-> (kv/value i) (mem/<-nippy-buffer)))))))

  (encode-value [this value]
    (let [value-buffer (c/->value-buffer value)]
      (when-not (c/can-decode-value-buffer? value-buffer)
        (.put temp-hash-cache (mem/copy-to-unpooled-buffer value-buffer) value))
      value-buffer))

  (open-nested-index-store [this]
    (let [nested-index-store (new-kv-index-store snapshot temp-hash-cache false z-curve-index?)]
      (swap! nested-index-store-state conj nested-index-store)
      nested-index-store)))

;;;; Indexer

(defn ->content-idx-kvs [docs]
  (let [attr-bufs (->> (into #{} (mapcat keys) (vals docs))
                       (into {} (map (juxt identity c/->id-buffer))))]
    (->> (for [[content-hash doc] docs
               :let [id (c/->id-buffer (:crux.db/id doc))
                     content-hash (c/->id-buffer content-hash)]
               [a v] doc
               :let [a (get attr-bufs a)]
               v (c/vectorize-value v)
               :let [v-buf (c/->value-buffer v)]
               :when (pos? (.capacity v-buf))]
           (cond-> [(MapEntry/create (encode-ave-key-to nil a v-buf id) c/empty-buffer)
                    (MapEntry/create (encode-aecv-key-to nil a id content-hash v-buf) c/empty-buffer)]
             (not (c/can-decode-value-buffer? v-buf))
             (conj (MapEntry/create (encode-hash-cache-key-to nil v-buf id) (mem/->nippy-buffer v)))))
         (apply concat))))

(defn- new-kv-index-store [snapshot temp-hash-cache close-snapshot? z-curve-index?]
  (->KvIndexStore snapshot
                  close-snapshot?
                  z-curve-index?
                  (delay (kv/new-iterator snapshot))
                  (delay (kv/new-iterator snapshot))
                  (delay (kv/new-iterator snapshot))
                  (delay (kv/new-iterator snapshot))
                  (atom [])
                  temp-hash-cache
                  (AtomicBoolean.)))

(defrecord KvIndexer [kv-store z-curve-index?]
  db/Indexer
  (index-docs [this docs]
    (with-open [snapshot (kv/new-snapshot kv-store)]
      (let [docs (->> docs
                      (into {} (remove (let [crux-db-id (c/->id-buffer :crux.db/id)]
                                         (fn [[k doc]]
                                           (let [eid (c/->id-buffer (:crux.db/id doc))]
                                             (kv/get-value snapshot (encode-aecv-key-to (.get seek-buffer-tl)
                                                                                        crux-db-id
                                                                                        eid
                                                                                        (c/->id-buffer k)
                                                                                        eid)))))))
                      not-empty)

            content-idx-kvs (->content-idx-kvs docs)]

        (some->> (seq content-idx-kvs) (kv/store kv-store))

        {:bytes-indexed (->> content-idx-kvs (transduce (comp (mapcat seq) (map mem/capacity)) +))
         :indexed-docs docs})))

  (unindex-eids [this eids]
    (with-open [snapshot (kv/new-snapshot kv-store)
                i (kv/new-iterator snapshot)]
      (let [attrs (vec (all-attrs i))
            {:keys [tombstones ks]} (->> (for [attr attrs
                                               eid eids
                                               aecv-key (all-keys-in-prefix i
                                                                            (encode-aecv-key-to (.get seek-buffer-tl)
                                                                                                (c/->id-buffer attr)
                                                                                                (c/->id-buffer eid)))]
                                           aecv-key)

                                         (reduce (fn [acc aecv-key]
                                                   (let [quad (decode-aecv-key-from aecv-key)
                                                         eid-buffer (c/->id-buffer (.eid quad))
                                                         value-buffer (.value quad)]
                                                     (cond-> acc
                                                       true (update :tombstones assoc (.content-hash quad) {:crux.db/id (.eid quad), :crux.db/evicted? true})
                                                       true (update :ks conj
                                                                    (encode-ave-key-to nil
                                                                                       (c/->id-buffer (.attr quad))
                                                                                       value-buffer
                                                                                       eid-buffer)
                                                                    aecv-key)
                                                       (not (c/can-decode-value-buffer? value-buffer))
                                                       (update :ks conj (encode-hash-cache-key-to nil value-buffer eid-buffer)))))
                                                 {:tombstones {}
                                                  :ks #{}}))]

        (kv/delete kv-store ks)
        {:tombstones tombstones})))

  (mark-tx-as-failed [this {:crux.tx/keys [tx-id] :as tx}]
    (kv/store kv-store [(meta-kv ::latest-completed-tx tx)
                        [(encode-failed-tx-id-key-to nil tx-id) c/empty-buffer]]))

  (index-entity-txs [this tx entity-txs]
    (kv/store kv-store (into (sorted-map-by mem/buffer-comparator)
                             (conj (mapcat (fn [^EntityTx etx]
                                             (cond-> [[(encode-entity+vt+tt+tx-id-key-to
                                                        nil
                                                        (c/->id-buffer (.eid etx))
                                                        (.vt etx)
                                                        (.tt etx)
                                                        (.tx-id etx))
                                                       (c/->id-buffer (.content-hash etx))]]

                                               z-curve-index?
                                               (conj [(encode-entity+z+tx-id-key-to
                                                       nil
                                                       (c/->id-buffer (.eid etx))
                                                       (encode-entity-tx-z-number (.vt etx) (.tt etx))
                                                       (.tx-id etx))
                                                      (c/->id-buffer (.content-hash etx))])))
                                           entity-txs)

                                   (meta-kv ::latest-completed-tx tx)))))

  (store-index-meta [_ k v]
    (store-meta kv-store k v))

  (read-index-meta [_  k]
    (read-meta kv-store k))

  (latest-completed-tx [this]
    (db/read-index-meta this ::latest-completed-tx))

  (tx-failed? [this tx-id]
    (with-open [snapshot (kv/new-snapshot kv-store)]
      (some? (kv/get-value snapshot (encode-failed-tx-id-key-to nil tx-id)))))

  (open-index-store [this]
    (new-kv-index-store (kv/new-snapshot kv-store) (HashMap.) true z-curve-index?))

  status/Status
  (status-map [this]
    {:crux.index/index-version (current-index-version kv-store)
     :crux.doc-log/consumer-state (db/read-index-meta this :crux.doc-log/consumer-state)
     :crux.tx-log/consumer-state (db/read-index-meta this :crux.tx-log/consumer-state)}))

(def kv-indexer
  {:start-fn (fn [{:crux.node/keys [kv-store]} {::keys [z-curve-index?]}]
               (check-and-store-index-version kv-store)
               (check-z-curve-index-config kv-store z-curve-index?)
               (->KvIndexer kv-store z-curve-index?))
   :deps [:crux.node/kv-store]
   :args {::z-curve-index? {:crux.config/type :boolean
                            :default false
                            :doc "Enable the Z-curve bitemporal index"}}})
