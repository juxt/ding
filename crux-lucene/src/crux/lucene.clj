(ns crux.lucene
  (:require [clojure.spec.alpha :as s]
            [crux.codec :as cc]
            [crux.db :as db]
            [crux.io :as cio]
            [crux.memory :as mem]
            [crux.query :as q]
            [crux.system :as sys]
            [clojure.tools.logging :as log])
  (:import crux.codec.EntityTx
           java.io.Closeable
           java.nio.file.Path
           org.apache.lucene.analysis.Analyzer
           org.apache.lucene.analysis.standard.StandardAnalyzer
           [org.apache.lucene.document Document Field Field$Store StringField TextField]
           [org.apache.lucene.index DirectoryReader IndexWriter IndexWriterConfig Term]
           org.apache.lucene.queries.function.FunctionScoreQuery
           org.apache.lucene.queryparser.classic.QueryParser
           [org.apache.lucene.search BooleanClause$Occur BooleanQuery$Builder DoubleValuesSource IndexSearcher Query ScoreDoc TermQuery]
           [org.apache.lucene.store Directory FSDirectory]))

(def ^:dynamic *node*)

(defrecord LuceneNode [directory analyzer]
  java.io.Closeable
  (close [this]
    (doseq [^Closeable c [directory]]
      (.close c))))

(defn- id->stored-bytes [eid]
  (mem/->on-heap (cc/->value-buffer eid)))

(defn- ^String eid->str [eid]
  (mem/buffer->hex (cc/->id-buffer eid)))

(defn- crux-doc->triples [crux-doc]
  (->> (dissoc crux-doc :crux.db/id)
       (mapcat (fn [[k v]]
                 (for [v (if (coll? v) v [v])
                       :when (string? v)]
                   [(name k) v])))))

(defrecord DocumentId [a v])

(defn- ^Document triple->doc [[k ^String v]]
  (doto (Document.)
    ;; To search for triples by eid-a-v for deduping
    (.add (StringField. "id", (eid->str (DocumentId. k v)), Field$Store/NO))
    ;; The actual term, which will be tokenized
    (.add (TextField. (name k), v, Field$Store/YES))
    ;; Uses for wildcard searches
    (.add (TextField. "_val", v, Field$Store/YES))
    ;; The Attr (storage only, for temporal resolution)
    (.add (StringField. "_attr", (name k), Field$Store/YES))))

(defn- ^Term triple->term [[k ^String v]]
  (Term. "id" (eid->str (DocumentId. k v))))

(defn doc-count []
  (let [{:keys [^Directory directory]} *node*
        directory-reader (DirectoryReader/open directory)]
    (.numDocs directory-reader)))

(defn write-docs! [^IndexWriter index-writer docs]
  (doseq [d docs t (crux-doc->triples d)]
    (.updateDocument index-writer (triple->term t) (triple->doc t))))

(defn evict! [indexer, node, eids]
  (let [{:keys [^Directory directory ^Analyzer analyzer]} node
        attrs-id->attr (->> (db/read-index-meta indexer :crux/attribute-stats)
                            keys
                            (map #(vector (mem/buffer->hex (cc/->id-buffer %)) %))
                            (into {}))]
    (with-open [index-snapshot (db/open-index-snapshot indexer)
                index-writer (IndexWriter. directory, (IndexWriterConfig. analyzer))]
      (let [qs (for [[a v] (db/exclusive-avs indexer eids)
                     :let [a (attrs-id->attr (mem/buffer->hex a))
                           v (db/decode-value index-snapshot v)]
                     :when (not= :crux.db/id a)]
                 (TermQuery. (Term. "id" (eid->str (DocumentId. (name a) v)))))]
        (.deleteDocuments index-writer ^"[Lorg.apache.lucene.search.Query;" (into-array Query qs))))))

(defn search [node, k, v]
  (let [{:keys [^Directory directory ^Analyzer analyzer]} node
        directory-reader (DirectoryReader/open directory)
        index-searcher (IndexSearcher. directory-reader)
        qp (if k
             (QueryParser. (name k) analyzer)
             (QueryParser. "_val" analyzer))
        b (doto (BooleanQuery$Builder.)
            (.add (.parse qp v) BooleanClause$Occur/MUST))
        q (.build b)
        q (FunctionScoreQuery. q (DoubleValuesSource/fromQuery q))
        score-docs (.-scoreDocs (.search index-searcher q 1000))]

    (when (seq score-docs)
      (log/debug (.explain index-searcher q (.-doc ^ScoreDoc (first score-docs)))))

    (cio/->cursor (fn []
                    (.close directory-reader))
                  (map (fn [^ScoreDoc d] (vector (.doc index-searcher (.-doc d))
                                                 (.-score d))) score-docs))))
(defn- normalise-scores [tuples]
  (when (seq tuples)
    (let [max-score (bigdec (last (first tuples)))]
      (for [[e v a s] tuples]
        [e v a (double (with-precision 2 (/ (bigdec s) max-score)))]))))

(defn- full-text [node index-snapshot entity-resolver-fn attr arg-v]
  (with-open [search-results ^crux.api.ICursor (search node attr arg-v)]
    (->> (iterator-seq search-results)
         (mapcat (fn [[^Document doc score]]
                   (let [v (.get ^Document doc "_val")
                         a (keyword (.get ^Document doc "_attr"))
                         encoded-v (db/encode-value index-snapshot v)]
                     (for [eid (db/ave index-snapshot a v nil entity-resolver-fn)]
                       [(db/decode-value index-snapshot eid) v a score]))))
         (normalise-scores)
         (into []))))

(defmethod q/pred-args-spec 'text-search [_]
  (s/cat :pred-fn  #{'text-search} :args (s/spec (s/cat :v string? :attr (s/? keyword?))) :return (s/? :crux.query/binding)))

(defmethod q/pred-constraint 'text-search [_ {:keys [encode-value-fn idx-id arg-bindings rule-name->rules return-type tuple-idxs-in-join-order] :as pred-ctx}]
  (let [[vval attr] (rest arg-bindings)]
    (fn pred-get-attr-constraint [index-snapshot {:keys [entity-resolver-fn] :as db} idx-id->idx join-keys]
      (q/bind-binding return-type tuple-idxs-in-join-order (get idx-id->idx idx-id) (full-text *node* index-snapshot entity-resolver-fn attr vval)))))

(defn- entity-txes->content-hashes [txes]
  (set (for [^EntityTx entity-tx txes]
         (.content-hash entity-tx))))

(defrecord LuceneDecoratingIndexStore [index-store document-store lucene-node]
  db/IndexStore
  (index-docs [this docs]
    (db/index-docs index-store docs))
  (unindex-eids [this eids]
    (try
      (evict! index-store lucene-node eids)
      (catch Throwable t
        (clojure.tools.logging/error t)
        (throw t)))
    (db/unindex-eids index-store eids))
  (index-entity-txs [this tx entity-txs]
    (let [{:keys [^Directory directory ^Analyzer analyzer]} lucene-node
          index-writer (IndexWriter. directory, (IndexWriterConfig. analyzer))]
      (try
        (let [content-hashes (entity-txes->content-hashes entity-txs)
              docs (vals (db/fetch-docs document-store content-hashes))]
          (write-docs! index-writer docs)
          (db/index-entity-txs index-store tx entity-txs)
          (.close index-writer))
        (catch Throwable t
          (clojure.tools.logging/error t)
          (.rollback index-writer)
          (throw t)))))
  (mark-tx-as-failed [this tx]
    (db/mark-tx-as-failed index-store tx))
  (store-index-meta [this k v]
    (db/store-index-meta index-store k v))
  (read-index-meta [this k]
    (db/read-index-meta index-store k))
  (read-index-meta [this k not-found]
    (db/read-index-meta index-store k not-found))
  (latest-completed-tx [this]
    (db/latest-completed-tx index-store))
  (tx-failed? [this tx-id]
    (db/tx-failed? index-store tx-id))
  (open-index-snapshot ^java.io.Closeable [this]
    (db/open-index-snapshot index-store)))

(defn ->node
  {::sys/args {:db-dir {:doc "Lucene DB Dir"
                        :required? true
                        :spec ::sys/path}}}
  [{:keys [^Path db-dir]}]
  (let [directory (FSDirectory/open db-dir)
        analyzer (StandardAnalyzer.)
        node (LuceneNode. directory analyzer)]
    (alter-var-root #'*node* (constantly node))))

(defn ->index-store
  {::sys/deps {:index-store :crux/index-store
               :document-store :crux/document-store
               :lucene-node ::node}}
  [{:keys [index-store document-store lucene-node]}]
  (LuceneDecoratingIndexStore. index-store document-store lucene-node))
