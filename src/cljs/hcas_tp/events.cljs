(ns hcas-tp.events
  (:require [ajax.core :as ajax]
            [day8.re-frame.http-fx]
            [hcas-tp.db :as db]
            [hcas-tp.rss :as rss]
            [re-frame.core :as rf]))

(rf/reg-event-db
 ::initialize-db
 (fn [_ _]
   db/default-db))

(rf/reg-event-db
 ::rss-load-success
 (fn [db [_ result]]
   (let [rss (rss/parse result)]
     (assoc db
            :rss rss
            :episodes (-> rss rss/get-channel rss/get-items)))))

(rf/reg-event-db
 ::rss-load-failure
 (fn [db [_ result]]))

(rf/reg-event-fx
 ::load-rss
 (fn [{:keys [db]} _]
   {:http-xhrio {:method :get
                 :uri "/feed.rss"
                 :timeout 8000
                 :response-format (ajax/detect-response-format)
                 :on-success [::rss-load-success]
                 :on-failure [::rss-load-failure]}}))
