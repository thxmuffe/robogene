(ns webapp.shared.events.effects
  (:require [re-frame.core :as rf]))

(rf/reg-fx
 :start-chapter-celebration
 (fn [_]
   (js/setTimeout
    (fn []
      (rf/dispatch [:chapter-celebration-ended]))
    2200)))

(rf/reg-fx
 :dispatch-after-burst
 (fn [{:keys [delays event]}]
   (doseq [ms (or delays [])]
     (js/setTimeout
      (fn []
        (rf/dispatch event))
      ms))))
