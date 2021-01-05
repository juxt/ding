(ns crux.fixtures.lucene
  (:require [crux.fixtures :as fix :refer [*api*]]
            [crux.lucene :as l])
  (:import [org.apache.lucene.index DirectoryReader]
           [org.apache.lucene.store Directory]))

(defn with-lucene-module [f]
  (fix/with-tmp-dirs #{db-dir}
    (fix/with-opts {::l/lucene-store {:db-dir db-dir}}
      f)))

(defn with-lucene-opts [lucene-opts]
  (fn [f]
    (fix/with-tmp-dirs #{db-dir}
      (fix/with-opts {::l/lucene-store (merge {:db-dir db-dir} lucene-opts)}
        f))))

(defn- lucene-store []
  (:crux.lucene/lucene-store @(:!system *api*)))

(defn ^crux.api.ICursor search [f & args]
  (let [lucene-store (lucene-store)
        analyzer (:analyzer lucene-store)
        q (apply f analyzer args)]
    (l/search lucene-store q)))

(defn doc-count []
  (let [{:keys [^Directory directory]} (lucene-store)
        directory-reader (DirectoryReader/open directory)]
    (.numDocs directory-reader)))
