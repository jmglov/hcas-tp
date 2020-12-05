(ns hcas-tp.core
  (:require [reagent.dom :as dom]))

(defn root-view []
  [:div
   "Welcome!"])

(defn ^:dev/after-load render []
  (dom/render [root-view]
                  (js/document.getElementById "hcas-tp"))
  (println "OK, I'm reloaded!"))

(defn init []
  (render))
