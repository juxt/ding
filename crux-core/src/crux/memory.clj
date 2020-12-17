(ns ^:no-doc crux.memory
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [crux.io :as cio]
            [taoensso.nippy :as nippy])
  (:import [java.io DataInputStream DataOutputStream]
           java.nio.ByteBuffer
           [java.util ArrayList Comparator]
           java.util.function.Supplier
           [java.lang.ref Reference WeakReference]
           [org.agrona BufferUtil DirectBuffer ExpandableDirectByteBuffer MutableDirectBuffer]
           org.agrona.concurrent.UnsafeBuffer
           [org.agrona.io DirectBufferInputStream ExpandableDirectBufferOutputStream]
           crux.ByteUtils))

(set! *unchecked-math* :warn-on-boxed)

(defprotocol MemoryRegion
  (->on-heap ^bytes [this])

  (->off-heap
    ^org.agrona.MutableDirectBuffer [this]
    ^org.agrona.MutableDirectBuffer [this ^MutableDirectBuffer to])

  (off-heap? [this])

  (as-buffer ^org.agrona.MutableDirectBuffer [this])

  (^long capacity [this]))

(def ^:const max-chunk-size (* 128 1024))
(def ^:dynamic *chunk-size* max-chunk-size)
(defonce ^:private pool-allocation-stats (atom {:allocated 0
                                                :deallocated 0}))

(defn- log-pool-memory [{:keys [allocated deallocated] :as pool-allocation-stats}]
  (log/debug :pool-allocation-stats (assoc pool-allocation-stats :in-use (- (long allocated) (long deallocated)))))

(defn allocate-pooled-buffer [^long size]
  (let [chunk (ByteBuffer/allocateDirect size)]
    (assert (= size (.capacity chunk)))
    (log-pool-memory (swap! pool-allocation-stats update :allocated + (.capacity chunk)))
    (cio/register-cleaner chunk #(log-pool-memory (swap! pool-allocation-stats update :deallocated + size)))
    chunk))

(def ^:dynamic *allocate-pooled-buffer* allocate-pooled-buffer)

(defn allocate-unpooled-buffer ^org.agrona.MutableDirectBuffer [^long size]
  (UnsafeBuffer. (ByteBuffer/allocateDirect size) 0 size))

(def empty-buffer (allocate-unpooled-buffer 0))

(def ^:private ^ThreadLocal chunk-tl
  (ThreadLocal/withInitial
   (reify Supplier
     (get [_]
       (.byteBuffer ^DirectBuffer empty-buffer)))))

(def ^:private ^:const alignment-round-mask 0xf)

(defn allocate-buffer ^org.agrona.MutableDirectBuffer [^long size]
  (let [chunk ^ByteBuffer (.get chunk-tl)
        offset (.position chunk)
        new-aligned-offset (bit-and-not (+ offset size alignment-round-mask)
                                        alignment-round-mask)]
    (cond
      (> size (quot (long *chunk-size*) 4))
      (allocate-unpooled-buffer size)

      (> new-aligned-offset (.capacity chunk))
      (let [chunk (*allocate-pooled-buffer* *chunk-size*)]
        (.set chunk-tl chunk)
        (recur size))

      :else
      ;; TODO: This slice is safer if the byte buffer itself is used,
      ;; but slower.
      (let [buffer (.slice (.limit (.duplicate chunk) (+ offset size)))]
        (.position chunk new-aligned-offset)
        (UnsafeBuffer. ^ByteBuffer buffer 0 size)))))

(defmacro with-region [& body]
  `(let [chunk-tl# ^ThreadLocal @#'chunk-tl
         prev-chunk# (.get chunk-tl#)
         buffers# (ArrayList.)]
     (try
       (binding [*chunk-size* 2048
                 *allocate-pooled-buffer* (fn [^long size#]
                                            (let [buffer# (allocate-pooled-buffer size#)]
                                              (set! *chunk-size* (min max-chunk-size (* 2 (long *chunk-size*))))
                                              (.add buffers# (WeakReference. buffer#))
                                              buffer#))]
         (.set chunk-tl# (.byteBuffer ^DirectBuffer empty-buffer))
         ~@body)
       (finally
         (.set chunk-tl# prev-chunk#)
         (doseq [ref# buffers#
                 :let [buffer# (.get ^Reference ref#)]
                 :when buffer#]
           (BufferUtil/free ^ByteBuffer buffer#))))))

(defn copy-buffer
  (^org.agrona.MutableDirectBuffer [^DirectBuffer from]
   (copy-buffer from (.capacity from)))
  (^org.agrona.MutableDirectBuffer [^DirectBuffer from ^long limit]
   (copy-buffer from limit (allocate-buffer limit)))
  (^org.agrona.MutableDirectBuffer [^DirectBuffer from ^long limit ^MutableDirectBuffer to]
   (doto to
     (.putBytes 0 from 0 limit))))

(defn copy-to-unpooled-buffer ^org.agrona.MutableDirectBuffer [^DirectBuffer from]
  (copy-buffer from (.capacity from) (allocate-unpooled-buffer (.capacity from))))

(defn slice-buffer ^org.agrona.MutableDirectBuffer [^DirectBuffer buffer ^long offset ^long limit]
  (UnsafeBuffer. buffer offset limit))

(defn limit-buffer ^org.agrona.MutableDirectBuffer [^DirectBuffer buffer ^long limit]
  (slice-buffer buffer 0 limit))

(extend-protocol MemoryRegion
  (class (byte-array 0))
  (->on-heap [this]
    this)

  (->off-heap
    ([this]
     (let [b (allocate-buffer (alength ^bytes this))]
       (->off-heap this b)))

    ([this ^MutableDirectBuffer to]
     (doto to
       (.putBytes 0 ^bytes this))))

  (off-heap? [this]
    false)

  (as-buffer [this]
    (UnsafeBuffer. ^bytes this))

  (capacity [this]
    (alength ^bytes this))

  DirectBuffer
  (->on-heap [this]
    (if (and (.byteArray this)
             (= (.capacity this)
                (alength (.byteArray this))))
      (.byteArray this)
      (let [bytes (byte-array (.capacity this))]
        (.getBytes this 0 bytes)
        bytes)))

  (->off-heap
    ([this]
     (if (off-heap? this)
       this
       (->off-heap this (allocate-buffer (.capacity this)))))

    ([this ^MutableDirectBuffer to]
     (doto to
       (.putBytes 0 this 0 (.capacity this)))))

  (off-heap? [this]
    (or (some-> (.byteBuffer this) (.isDirect))
        (and (nil? (.byteArray this))
             (nil? (.byteBuffer this)))))

  (as-buffer [this]
    this)

  (capacity [this]
    (.capacity this))

  ByteBuffer
  (->on-heap [this]
    (if (and (.hasArray this)
             (= (.remaining this)
                (alength (.array this))))
      (.array this)
      (doto (byte-array (.remaining this))
        (->> (.get (.duplicate this))))))

  (->off-heap
    ([this]
     (if (.isDirect this)
       (as-buffer this)
       (->off-heap this (allocate-buffer (.remaining this)))))

    ([this ^MutableDirectBuffer to]
     (doto to
       (.putBytes 0 this (.position this) (.remaining this)))))

  (off-heap? [this]
    (.isDirect this))

  (as-buffer [this]
    (UnsafeBuffer. this (.position this) (.remaining this)))

  (capacity [this]
    (.remaining this)))

(defn ensure-off-heap ^org.agrona.DirectBuffer [b ^MutableDirectBuffer tmp]
  (if (off-heap? b)
    b
    (UnsafeBuffer. (->off-heap b tmp) 0 (capacity b))))

(defn direct-byte-buffer ^java.nio.ByteBuffer [b]
  (let [b (->off-heap b)
        offset (- (.addressOffset b)
                  (BufferUtil/address (.byteBuffer b)))]
    (-> (.byteBuffer b)
        (.duplicate)
        (.clear)
        (.position offset)
        (.limit (+ offset (.capacity b)))
        (.slice))))

(defn on-heap-buffer ^org.agrona.DirectBuffer [^bytes b]
  (UnsafeBuffer. b))

(defn buffer->hex ^String [^DirectBuffer b]
  (some-> b (ByteUtils/bufferToHex)))

(defn hex->buffer
  (^org.agrona.DirectBuffer [^String b]
   (hex->buffer b (ExpandableDirectByteBuffer.)))
  (^org.agrona.DirectBuffer [^String b ^MutableDirectBuffer to]
   (some-> b (ByteUtils/hexToBuffer to))))

(defn compare-buffers
  {:inline (fn [a b & [max-length]]
             (if max-length
               `(ByteUtils/compareBuffers ~a ~b ~max-length)
               `(ByteUtils/compareBuffers ~a ~b)))
   :inline-arities #{2 3}}
  (^long [^DirectBuffer a ^DirectBuffer b]
   (ByteUtils/compareBuffers a b))
  (^long [^DirectBuffer a ^DirectBuffer b ^long max-length]
   (ByteUtils/compareBuffers a b max-length)))

(def ^java.util.Comparator buffer-comparator
  ByteUtils/UNSIGNED_BUFFER_COMPARATOR)

(defn buffers=?
  {:inline (fn [a b & [max-length]]
             (if max-length
               `(ByteUtils/equalBuffers ~a ~b ~max-length)
               `(ByteUtils/equalBuffers ~a ~b)))
   :inline-arities #{2 3}}
  ([^DirectBuffer a ^DirectBuffer b]
   (ByteUtils/equalBuffers a b))
  ([^DirectBuffer a ^DirectBuffer b ^long max-length]
   (ByteUtils/equalBuffers a b max-length)))

(defn inc-unsigned-buffer!
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer buffer]
   (inc-unsigned-buffer! buffer (.capacity buffer)))
  (^org.agrona.MutableDirectBuffer [^MutableDirectBuffer buffer ^long prefix-length]
   (loop [idx (dec (int prefix-length))]
     (when-not (neg? idx)
       (let [b (Byte/toUnsignedInt (.getByte buffer idx))]
         (if (= 0xff b)
           (do (.putByte buffer idx (byte 0))
               (recur (dec idx)))
           (doto buffer
             (.putByte idx (unchecked-byte (inc b))))))))))

(defn <-nippy-buffer [buf]
  (nippy/thaw-from-in! (-> (DirectBufferInputStream. buf)
                           (DataInputStream.))))

(defn ->nippy-buffer [v]
  (let [to (ExpandableDirectByteBuffer. 64)
        dos (-> (ExpandableDirectBufferOutputStream. to)
                (DataOutputStream.))]
    (nippy/-freeze-without-meta! v dos)
    (-> to
        (limit-buffer (.size dos)))))
