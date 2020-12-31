(ns hcas-tp.core
  (:require [hcas-tp.config :as config]
            [hcas-tp.events :as events]
            [hcas-tp.views :as views]
            [re-frame.core :as rf]
            [reagent.dom :as dom]))

(defn dev-setup []
  (when config/debug?
    (enable-console-print!)
    (println "dev mode")))

(defn ^:dev/after-load mount-root []
  (rf/clear-subscription-cache!)
  (dom/render [views/main]
              (.getElementById js/document "app")))

(defn ^:export init []
  (rf/dispatch-sync [::events/initialize-db])
  (dev-setup)
  (mount-root))
