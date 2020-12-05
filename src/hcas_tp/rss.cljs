(ns hcas-tp.rss
  (:require [hickory.core :as hickory]))

(defn parse [rss-str]
  (-> (js/DOMParser.)
      (.parseFromString rss-str "text/xml")
      (.-firstChild)
      hickory/as-hiccup))

(defn get-channel [rss]
  (->> rss
       (some #(and (vector? %)
                   (= :channel (first %))
                   %))))

(defn get-items [channel]
  (->> channel
       (filter #(and (vector? %)
                     (= :item (first %))))
       (map (fn [item]
              (->> item
                   (filter vector?)
                   (reduce (fn [acc [k attrs content]]
                             (assoc acc k {:attrs attrs
                                           :content content}))
                           {}))))
       (into [])))
