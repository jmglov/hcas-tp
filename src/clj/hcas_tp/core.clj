(ns hcas-tp.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.transcribe :as transcribe]
            [camel-snake-kebab.core :as csk]
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

(defn s3-object-exists? [bucket key]
  (s3/does-object-exist bucket key))

(def ->job-name csk/->Camel_Snake_Case)

(defn ->slug [raw-transcript]
  (-> raw-transcript
      (string/replace #"-." #(-> % string/lower-case (subs 1)))
      (string/replace #"[.]json$" "")
      csk/->kebab-case))

(defn episode-for-slug [episodes slug]
  (some #(and (= slug (episode-slug %)) %) episodes))

(defn local-filename [dir extension episode]
  (let [slug (episode-slug episode)]
    (str dir "/episodes/" (->job-name slug) "/" (->job-name slug) extension)))

(defn mp3-filename [dir episode]
  (local-filename dir ".mp3" episode))

(defn otr-filename [dir episode]
  (local-filename dir ".otr" episode))

(defn transcript-filename [dir episode]
  (local-filename dir ".json" episode))

(defn download-episode
  ([dir episode]
   (download-episode false dir episode))
  ([force-download? dir episode]
   (let [mp3-url (episode-mp3 episode)
         filename (mp3-filename dir episode)
         file (io/file filename)]
     (when (or force-download? (not (.exists file)))
       (io/make-parents filename)
       (some-> (http/get mp3-url {:as :byte-array}) :body (io/copy file))))
   episode))

(defn download-transcript
  ([jobs-bucket dir episode]
   (download-transcript false jobs-bucket dir episode))
  ([force-download? jobs-bucket dir episode]
   (let [slug (episode-slug episode)
         in (->> (s3/get-object :bucket-name jobs-bucket
                                :key (str (->job-name slug) ".json"))
                 :input-stream)
         out (io/file (transcript-filename dir episode))]
     (io/copy in out)
     (.close in)
     episode)))

(defn upload-mp3
  ([mp3-bucket mp3-prefix dir episode]
   (upload-mp3 false mp3-bucket mp3-prefix dir episode))
  ([force-upload? mp3-bucket mp3-prefix dir episode]
   (let [filename (mp3-filename dir episode)
         file (io/file filename)
         s3-key (str mp3-prefix "/" (.getName file))]
     (when (or force-upload?
               (not (s3-object-exists? mp3-bucket s3-key)))
       (s3/put-object :bucket-name mp3-bucket
                      :key s3-key
                      :file file)))
   episode))

(defn upload-otr [otr-bucket otr-prefix dir episode]
  (let [slug (episode-slug episode)]
    (s3/put-object :bucket-name otr-bucket
                   :key (str otr-prefix "/" slug ".otr")
                   :file (io/file (otr-filename dir episode))))
  episode)

(defn job-exists? [episode]
  (try
    (->> episode
         episode-slug
         ->job-name
         (transcribe/get-transcription-job :transcription-job-name))
    true
    (catch Exception _
      false)))

(defn start-job
  ([mp3-bucket mp3-prefix jobs-bucket episode]
   (start-job false {} mp3-bucket mp3-prefix jobs-bucket episode))
  ([force-start? options mp3-bucket mp3-prefix jobs-bucket episode]
   (let [job-name (->> episode episode-slug ->job-name)
         {:keys [language-code num-speakers]} (merge {:language-code "en-US"
                                                      :num-speakers 2}
                                                     options)]
     (transcribe/start-transcription-job
      :transcription-job-name job-name
      :language-code language-code
      :media-format "mp3"
      :media {:media-file-uri (format "s3://%s/%s/%s.mp3"
                                      mp3-bucket mp3-prefix job-name)}
      :output-bucket-name jobs-bucket
      :settings {:show-speaker-labels true
                 :max-speaker-labels num-speakers}))))
