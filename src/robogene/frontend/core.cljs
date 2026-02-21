(ns robogene.frontend.core
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [reagent.dom.client :as rdom]
            [robogene.frontend.events.handlers]
            [robogene.frontend.subs]
            [robogene.frontend.views :as views]))

(defonce root* (atom nil))

(defn typing-target? [el]
  (let [tag (some-> el .-tagName str/lower-case)]
    (or (= tag "input")
        (= tag "textarea")
        (= tag "select")
        (true? (.-isContentEditable el)))))

(defn mount-root []
  (when-let [el (.getElementById js/document "app")]
    (let [root (or @root*
                   (let [r (rdom/create-root el)]
                     (reset! root* r)
                     r))]
      (rdom/render root [views/main-view]))))

(defn ^:dev/after-load after-load! []
  (mount-root)
  (rf/dispatch [:fetch-state]))

(defn ^:export init! []
  (rf/dispatch-sync [:initialize])
  (.addEventListener js/window "focus" #(rf/dispatch [:force-refresh]))
  (.addEventListener js/window "hashchange"
                     #(rf/dispatch [:hash-changed (.-hash js/location)]))
  (.addEventListener js/window "keydown"
                     (fn [e]
                       (when-not (typing-target? (.-target e))
                         (case (.-key e)
                           "Escape" (rf/dispatch [:navigate-index])
                           "ArrowLeft" (rf/dispatch [:navigate-relative-frame -1])
                           "ArrowRight" (rf/dispatch [:navigate-relative-frame 1])
                           nil))))
  (.addEventListener js/document "visibilitychange"
                     #(when-not (.-hidden js/document)
                        (rf/dispatch [:force-refresh])))
  (mount-root))
