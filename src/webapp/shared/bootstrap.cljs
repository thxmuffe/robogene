(ns webapp.shared.bootstrap
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdom]
            [webapp.shared.controls :as controls]
            [webapp.shared.events.handlers]
            [webapp.shared.subs]
            [webapp.app-shell :as app-shell]))

(defonce root* (atom nil))
(defonce initialized?* (atom false))

(defn mount-root []
  (when-let [el (.getElementById js/document "app")]
    (let [root (or @root*
                   (let [r (rdom/create-root el)]
                     (reset! root* r)
                     r))]
      (rdom/render root [app-shell/main-view]))))

(defn ^:dev/after-load after-load! []
  (mount-root)
  (rf/dispatch [:fetch-state]))

(defn ^:export init! []
  (when-not @initialized?*
    (reset! initialized?* true)
    (rf/dispatch-sync [:initialize])
    (controls/register-global-listeners!))
  (mount-root))
