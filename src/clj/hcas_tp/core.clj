(ns hcas-tp.core
  (:require [clojure.string :as string]
            [clj-http.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as s])
  (:import (java.io FileNotFoundException)))

(def podcast-base-url "https://hashtagcauseascene.com/podcast/")

(def rss-url (str podcast-base-url "feed/podcast/"))

(def episode-cache-dir "resources/podcasts/")

(def rss-content-types ["application/rss+xml"
                        "application/rdf+xml;q=0.8"
                        "application/atom+xml;q=0.6"
                        "application/xml;q=0.4"
                        "text/xml;q=0.4"])

(defn fetch-rss [url]
  (-> (http/get url {:accept (string/join ", " rss-content-types)})
      :body))

(def parse-hickory (comp hickory/as-hickory hickory/parse))

(def rss-items (partial s/select (s/tag :item)))

(defn episode-link [item]
  (->> item
       :content
       (remove map?)
       (some #(and (string/starts-with? % podcast-base-url)
                   %))))

(defn episode-slug [episode]
  (-> episode
      episode-link
      (string/replace podcast-base-url "")
      (string/replace "/" "")))

(defn episode-title [episode]
  (->> episode
       (s/select (s/tag :title))
       first
       :content
       first))

(defn episode-cache-filename [slug]
  (str episode-cache-dir slug ".html"))

(defn cached-episode [slug]
  (try
    (slurp (episode-cache-filename slug))
    (catch FileNotFoundException _)))

(defn cache-episode [slug content]
  (spit (episode-cache-filename slug) content))

(defn get-episode-page [episode]
  (let [link (episode-link episode)
        slug (episode-slug episode)
        cached (cached-episode slug)
        content (or cached
                    (->> (http/get link) :body))]
    (when-not cached
      (cache-episode slug content))
    (parse-hickory content)))

(defn has-transcript? [episode]
  (->> episode
       get-episode-page
       (s/select (s/attr :data-widget_type #(= "read-more.default" %)))
       not-empty
       boolean))
