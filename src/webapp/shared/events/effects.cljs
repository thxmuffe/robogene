(ns webapp.shared.events.effects
  (:require [re-frame.core :as rf]))

(rf/reg-fx
 :start-chapter-celebration
 (fn [_]
   (js/setTimeout
    (fn []
      (rf/dispatch [:chapter-celebration-ended]))
    2200)))
