(ns robogene.frontend.core
  (:require [re-frame.core :as rf]
            [reagent.dom :as rdom]
            [robogene.frontend.events]
            [robogene.frontend.subs]
            [robogene.frontend.views :as views]))

(defn mount-root []
  (when-let [el (.getElementById js/document "app")]
    (rdom/render [views/main-view] el)))

(defn ^:export init! []
  (rf/dispatch-sync [:initialize])
  (.addEventListener js/window "focus" #(rf/dispatch [:force-refresh]))
  (.addEventListener js/document "visibilitychange"
                     #(when-not (.-hidden js/document)
                        (rf/dispatch [:force-refresh])))
  (mount-root))
