(ns hcas-tp.core
  (:require [clojure.string :as string]
            [clj-http.client :as http]
            [hickory.core :as hickory]
            [hickory.select :as s])
  (:import (java.io FileNotFoundException)
           (java.time ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def podcast-base-url "https://hashtagcauseascene.com/podcast/")

(def rss-url (str podcast-base-url "feed/podcast/"))

(def episode-cache-dir "cache/podcasts/")

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

(def content (comp first :content first))

(defn episode-date [episode]
  (-> (->> episode
           (s/select (s/tag :pubdate))
           content)
      (ZonedDateTime/parse DateTimeFormatter/RFC_1123_DATE_TIME)
      str))

(defn episode-duration [episode]
  (->> episode
       (s/select (s/tag :itunes:duration))
       content))

(defn episode-link [episode]
  (->> episode
       :content
       (remove map?)
       (some #(and (string/starts-with? % podcast-base-url)
                   %))))

(defn episode-mp3 [episode]
  (->> episode
       (s/select (s/tag :enclosure))
       first
       :attrs
       :url))

(defn episode-slug [episode]
  (-> episode
      episode-link
      (string/replace podcast-base-url "")
      (string/replace "/" "")))

(defn episode-title [episode]
  (->> episode
       (s/select (s/tag :title))
       content))

(defn episode-cache-filename [slug]
  (str episode-cache-dir slug ".html"))

(defn cached-episode [slug]
  (try
    (slurp (episode-cache-filename slug))
    (catch FileNotFoundException _)))

(defn cache-episode [slug content]
  (spit (episode-cache-filename slug) content))

(defn get-episode-page
  ([episode]
   (get-episode-page episode false))
  ([episode re-fetch?]
   (let [link (episode-link episode)
         slug (episode-slug episode)
         cached (if re-fetch? nil (cached-episode slug))
         content (or cached
                     (->> (http/get link) :body))]
     (when-not cached
       (cache-episode slug content))
     (parse-hickory content))))

(defn has-transcript? [episode]
  (->> episode
       get-episode-page
       (s/select (s/attr :data-widget_type #(= "read-more.default" %)))
       not-empty
       boolean))

(defn ->episode-key-fn [k]
  (-> (str "episode-" k) symbol))

(defn ->episode [episode]
  (->> {:date episode-date
        :duration episode-duration
        :link episode-link
        :mp3 episode-mp3
        :slug episode-slug
        :title episode-title}
       (map (fn [[k f]] [k (f episode)]))
       (into {})))
