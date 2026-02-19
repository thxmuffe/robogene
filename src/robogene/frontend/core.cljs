(ns robogene.frontend.core
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdom]
            [robogene.frontend.events]
            [robogene.frontend.subs]
            [robogene.frontend.views :as views]))

(defonce root* (atom nil))

(defn mount-root []
  (when-let [el (.getElementById js/document "app")]
    (let [root (or @root*
                   (let [r (rdom/create-root el)]
                     (reset! root* r)
                     r))]
      (rdom/render root [views/main-view]))))

(defn ^:export init! []
  (rf/dispatch-sync [:initialize])
  (.addEventListener js/window "focus" #(rf/dispatch [:force-refresh]))
  (.addEventListener js/document "visibilitychange"
                     #(when-not (.-hidden js/document)
                        (rf/dispatch [:force-refresh])))
  (mount-root))
