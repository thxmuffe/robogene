(ns webapp.shared.visual-effects
  (:require [re-frame.core :as rf]))

(defn seeded-unit [seed n]
  (let [x (* (+ seed (* 97 n)) 12.9898)
        s (js/Math.sin x)]
    (- (* (- s (js/Math.floor s)) 2) 1)))

(defn gallery-motion-style [seed-key]
  (let [seed (reduce (fn [acc ch] (+ acc (int ch))) 0 (str (or seed-key "")))
        y-scale (+ 0.28 (* 0.96 (js/Math.abs (seeded-unit seed 1))))
        x-scale (+ 0.22 (* 0.84 (js/Math.abs (seeded-unit seed 2))))
        rot-scale (* (+ 0.2 (* 0.56 (js/Math.abs (seeded-unit seed 3))))
                     (if (neg? (seeded-unit seed 4)) -1 1))
        float-offset (* (+ 4.0 (* 18.0 (js/Math.abs (seeded-unit seed 5))))
                        (if (neg? (seeded-unit seed 6)) -1 1))
        settle-ms (+ 30 (js/Math.floor (* 240 (js/Math.abs (seeded-unit seed 7)))))
        x-bias (* 0.34 (seeded-unit seed 8))
        y-bias (* 0.4 (seeded-unit seed 9))
        rot-bias (* 0.26 (seeded-unit seed 10))]
    #js {"--gallery-motion-y-scale" y-scale
         "--gallery-motion-x-scale" x-scale
         "--gallery-motion-y-bias" y-bias
         "--gallery-motion-x-bias" x-bias
         "--gallery-motion-rot-scale" rot-scale
         "--gallery-motion-rot-bias" rot-bias
         "--gallery-motion-float-x" (str float-offset "px")
         "--gallery-motion-pointer-weight" "1"
         "--gallery-motion-duration" (str settle-ms "ms")}))

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
