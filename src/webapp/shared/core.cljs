(ns webapp.shared.core
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [reagent.dom.client :as rdom]
            [webapp.shared.events.handlers]
            [webapp.shared.subs]
            [webapp.app-shell :as app-shell]))

(defonce root* (atom nil))
(defonce initialized?* (atom false))

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
      (rdom/render root [app-shell/main-view]))))

(defn ^:dev/after-load after-load! []
  (mount-root)
  (rf/dispatch [:fetch-state]))

(defn ^:export init! []
  (when-not @initialized?*
    (reset! initialized?* true)
    (rf/dispatch-sync [:initialize])
    (.addEventListener js/window "focus" #(rf/dispatch [:force-refresh]))
    (.addEventListener js/window "hashchange"
                       #(rf/dispatch [:hash-changed (.-hash js/location)]))
    (.addEventListener js/window "keydown"
                       (fn [e]
                         (when-not (typing-target? (.-target e))
                           (case (.-key e)
                             "Escape" (rf/dispatch [:navigate-index])
                             "ArrowLeft" (do
                                           (.preventDefault e)
                                           (rf/dispatch [:keyboard-arrow "ArrowLeft"]))
                             "ArrowRight" (do
                                            (.preventDefault e)
                                            (rf/dispatch [:keyboard-arrow "ArrowRight"]))
                             "ArrowUp" (do
                                         (.preventDefault e)
                                         (rf/dispatch [:keyboard-arrow "ArrowUp"]))
                             "ArrowDown" (do
                                           (.preventDefault e)
                                           (rf/dispatch [:keyboard-arrow "ArrowDown"]))
                             "Enter" (do
                                       (.preventDefault e)
                                       (rf/dispatch [:open-active-frame]))
                             nil))))
    (.addEventListener js/document "visibilitychange"
                       #(when-not (.-hidden js/document)
                          (rf/dispatch [:force-refresh]))))
  (mount-root))
