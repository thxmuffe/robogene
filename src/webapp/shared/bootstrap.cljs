(ns webapp.shared.bootstrap
  (:require [re-frame.core :as rf]
            [reagent.dom.client :as rdom]
            [webapp.shared.controls :as controls]
            [webapp.shared.events.handlers.common]
            [webapp.shared.subs]
            [webapp.app-shell :as app-shell]))

(defonce root* (atom nil))
(defonce initialized?* (atom false))
(defonce background-parallax-bound?* (atom false))
(defonce background-orbit-running?* (atom false))

(defn- update-background-parallax! []
  (let [scroll-y (or (.-scrollY js/window) 0)
        coarse-y (str (* scroll-y 0.12) "px")]
    (.setProperty (.-style (.-documentElement js/document)) "--bg-parallax-y" coarse-y)))

(defn- tick-background-orbit! []
  (let [t (/ (.now js/Date) 1000)
        x (* 5 (js/Math.cos (* t 0.22)))
        y (* 5 (js/Math.sin (* t 0.22)))]
    (.setProperty (.-style (.-documentElement js/document)) "--bg-orbit-x" (str x "px"))
    (.setProperty (.-style (.-documentElement js/document)) "--bg-orbit-y" (str y "px"))
    (when @background-orbit-running?*
      (.requestAnimationFrame js/window tick-background-orbit!))))

(defn- register-background-parallax! []
  (when-not @background-parallax-bound?*
    (reset! background-parallax-bound?* true)
    (update-background-parallax!)
    (.addEventListener js/window "scroll" update-background-parallax! #js {:passive true})))

(defn- register-background-orbit! []
  (when-not @background-orbit-running?*
    (reset! background-orbit-running?* true)
    (tick-background-orbit!)))

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
    (controls/register-global-listeners!)
    (register-background-parallax!)
    (register-background-orbit!))
  (mount-root))
