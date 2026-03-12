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
(defonce frame-motion-bound?* (atom false))
(defonce frame-motion-pointer-bound?* (atom false))
(defonce frame-motion-animating?* (atom false))
(defonce frame-motion-state* (atom {:x 0 :y 0 :rot 0 :last-scroll-y 0}))
(defonce frame-pointer-state* (atom {:x nil :y nil}))
(def ^:const gallery-motion-translation-amplitude 0.2)
(def ^:const gallery-motion-rotation-amplitude 0.65)
(def ^:const gallery-motion-max-x 22)
(def ^:const gallery-motion-max-y 30)
(def ^:const gallery-motion-max-rot 0.42)
(def ^:const gallery-motion-pointer-min-weight 0.28)

(defn- clamp [min-value max-value value]
  (max min-value (min max-value value)))

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

(defn- apply-frame-motion! [{:keys [x y rot]}]
  (let [style (.-style (.-documentElement js/document))]
    (.setProperty style "--gallery-scroll-x" (str x "px"))
    (.setProperty style "--gallery-scroll-y" (str y "px"))
    (.setProperty style "--gallery-scroll-rot" (str rot "deg"))))

(defn- curved-scroll-delta [delta]
  (let [direction (if (neg? delta) -1 1)
        magnitude (js/Math.abs delta)
        ;; Slow scrolls should barely move the gallery, while fast flicks ramp up hard.
        curved (* magnitude (+ 0.12 (* 0.88 (js/Math.pow (min 1 (/ magnitude 52)) 1.45))))
        limited (min curved 64)]
    (* direction limited)))

(defn- update-frame-pointer-weight! []
  (let [{:keys [x y]} @frame-pointer-state*
        items (.querySelectorAll js/document ".gallery-motion-item")]
    (doseq [item (array-seq items)]
      (let [style (.-style item)]
        (if (and (number? x) (number? y))
          (let [rect (.getBoundingClientRect item)
                center-x (+ (.-left rect) (/ (.-width rect) 2))
                center-y (+ (.-top rect) (/ (.-height rect) 2))
                dx (- x center-x)
                dy (- y center-y)
                distance (js/Math.sqrt (+ (* dx dx) (* dy dy)))
                viewport-span (max 1 (js/Math.sqrt (+ (* (.-innerWidth js/window) (.-innerWidth js/window))
                                                     (* (.-innerHeight js/window) (.-innerHeight js/window)))))
                proximity (- 1 (clamp 0 1 (/ distance (* viewport-span 0.58))))
                weight (+ gallery-motion-pointer-min-weight (* proximity (- 1 gallery-motion-pointer-min-weight)))]
            (.setProperty style "--gallery-motion-pointer-weight" (.toFixed weight 3)))
          (.setProperty style "--gallery-motion-pointer-weight" "1"))))))

(defn- update-frame-pointer! [event]
  (reset! frame-pointer-state* {:x (.-clientX event)
                                :y (.-clientY event)})
  (update-frame-pointer-weight!))

(defn- clear-frame-pointer! []
  (reset! frame-pointer-state* {:x nil :y nil})
  (update-frame-pointer-weight!))

(defn- register-frame-pointer-motion! []
  (when-not @frame-motion-pointer-bound?*
    (reset! frame-motion-pointer-bound?* true)
    (update-frame-pointer-weight!)
    (.addEventListener js/window "pointermove" update-frame-pointer! #js {:passive true})
    (.addEventListener js/window "pointerleave" clear-frame-pointer! #js {:passive true})
    (.addEventListener js/window "blur" clear-frame-pointer!))))

(defn- tick-frame-motion! []
  (let [{:keys [x y rot last-scroll-y]} @frame-motion-state*
        next-x (* x 0.8)
        next-y (* y 0.8)
        next-rot (* rot 0.81)]
    (if (and (< (js/Math.abs next-x) 0.08)
             (< (js/Math.abs next-y) 0.08)
             (< (js/Math.abs next-rot) 0.02))
      (do
        (reset! frame-motion-animating?* false)
        (reset! frame-motion-state* {:x 0 :y 0 :rot 0 :last-scroll-y last-scroll-y})
        (apply-frame-motion! {:x 0 :y 0 :rot 0}))
      (do
        (reset! frame-motion-state* {:x next-x :y next-y :rot next-rot :last-scroll-y last-scroll-y})
        (apply-frame-motion! {:x next-x :y next-y :rot next-rot})
        (.requestAnimationFrame js/window tick-frame-motion!)))))

(defn- update-frame-motion-on-scroll! []
  (let [{:keys [last-scroll-y]} @frame-motion-state*
        scroll-y (or (.-scrollY js/window) 0)
        delta (- scroll-y last-scroll-y)
        motion-delta (curved-scroll-delta delta)
        next-x (clamp (- gallery-motion-max-x) gallery-motion-max-x
                      (* motion-delta 1.18 gallery-motion-translation-amplitude))
        next-y (clamp (- gallery-motion-max-y) gallery-motion-max-y
                      (* motion-delta 1.54 gallery-motion-translation-amplitude))
        next-rot (clamp (- gallery-motion-max-rot) gallery-motion-max-rot
                        (* motion-delta 0.011 gallery-motion-rotation-amplitude))]
    (reset! frame-motion-state* {:x next-x :y next-y :rot next-rot :last-scroll-y scroll-y})
    (apply-frame-motion! {:x next-x :y next-y :rot next-rot})
    (update-frame-pointer-weight!)
    (when-not @frame-motion-animating?*
      (reset! frame-motion-animating?* true)
      (.requestAnimationFrame js/window tick-frame-motion!))))

(defn- register-frame-motion! []
  (when-not @frame-motion-bound?*
    (reset! frame-motion-bound?* true)
    (swap! frame-motion-state* assoc :last-scroll-y (or (.-scrollY js/window) 0))
    (apply-frame-motion! {:x 0 :y 0 :rot 0})
    (.addEventListener js/window "scroll" update-frame-motion-on-scroll! #js {:passive true})))

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
    (register-background-orbit!)
    (register-frame-motion!)
    (register-frame-pointer-motion!))
  (mount-root))
