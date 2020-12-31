(ns hcas-tp.views
  (:require [re-frame.core :as rf]
            [hcas-tp.events :as events]
            [hcas-tp.subs :as subs]))

(defn main []
  (let [episodes (rf/subscribe [::subs/episodes])]
    [:div
     [:h1 "#causeascene Transcription Project"]
     [:div
      [:button {:on-click #(rf/dispatch [::events/load-rss])}
       "Load RSS"]]
     [:div {:style {:display (if @episodes "block" "none")}}
      (str "We have " (count @episodes) " episodes")]]))
