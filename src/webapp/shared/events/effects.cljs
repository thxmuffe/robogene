(ns webapp.shared.events.effects
  (:require [re-frame.core :as rf]))

(defn frame-node-list []
  (array-seq (.querySelectorAll js/document ".frame[data-frame-id]")))

(defn frame-id-of [el]
  (.getAttribute el "data-frame-id"))

(defn frame-centers [el]
  (let [r (.getBoundingClientRect el)]
    {:x (+ (.-left r) (/ (.-width r) 2))
     :y (+ (.-top r) (/ (.-height r) 2))
     :el el}))

(defn nearest-vertical-frame-id [current-id direction]
  (let [nodes (frame-node-list)
        current-el (some (fn [el] (when (= current-id (frame-id-of el)) el)) nodes)]
    (when current-el
      (let [{cx :x cy :y} (frame-centers current-el)
            candidates (->> nodes
                            (filter (fn [el]
                                      (let [{x :x y :y} (frame-centers el)]
                                        (case direction
                                          :up (< y (- cy 8))
                                          :down (> y (+ cy 8))
                                          false))))
                            (map (fn [el]
                                   (let [{x :x y :y} (frame-centers el)
                                         dy (js/Math.abs (- y cy))
                                         dx (js/Math.abs (- x cx))]
                                     {:id (frame-id-of el)
                                      :dy dy
                                      :dx dx})))
                            (sort-by (fn [{:keys [dy dx]}] [dy dx])))]
        (or (:id (first candidates))
            (case direction
              :up (some-> nodes last frame-id-of)
              :down (some-> nodes first frame-id-of)
              nil))))))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (set! (.-hash js/location) hash)))

(rf/reg-fx
 :scroll-frame-into-view
 (fn [frame-id]
   (when (seq (or frame-id ""))
     (when-let [el (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"]"))]
       (.scrollIntoView el #js {:behavior "smooth"
                                :block (if (= frame-id "__new_chapter__") "center" "nearest")
                                :inline "nearest"})))))

(rf/reg-fx
 :move-active-frame-vertical
 (fn [{:keys [frame-id direction]}]
   (when (seq (or frame-id ""))
     (when-let [next-id (nearest-vertical-frame-id frame-id direction)]
       (rf/dispatch [:set-active-frame next-id])))))

(rf/reg-fx
 :start-chapter-celebration
 (fn [_]
   (js/setTimeout
    (fn []
      (rf/dispatch [:chapter-celebration-ended]))
    2200)))
