(ns webapp.components.chapter
  (:require [re-frame.core :as rf]
            [webapp.components.frame :as frame]
            ["@mantine/core" :refer [Box]]))

(defn- seeded-unit [seed n]
  (let [x (* (+ seed (* 97 n)) 12.9898)
        s (js/Math.sin x)]
    (- (* (- s (js/Math.floor s)) 2) 1)))

(defn- gallery-motion-style [seed-key]
  (let [seed (reduce (fn [acc ch] (+ acc (int ch))) 0 (str (or seed-key "")))
        y-scale (+ 0.08 (* 4.8 (js/Math.abs (seeded-unit seed 1))))
        x-scale (+ 0.05 (* 5.6 (js/Math.abs (seeded-unit seed 2))))
        rot-scale (* (+ 0.1 (* 15.8 (js/Math.abs (seeded-unit seed 3))))
                     (if (neg? (seeded-unit seed 4)) -1 1))
        float-offset (* (+ 1.0 (* 28.0 (js/Math.abs (seeded-unit seed 5))))
                        (if (neg? (seeded-unit seed 6)) -1 1))
        settle-ms (+ 30 (js/Math.floor (* 240 (js/Math.abs (seeded-unit seed 7)))))
        x-bias (* 1.7 (seeded-unit seed 8))
        y-bias (* 1.9 (seeded-unit seed 9))
        rot-bias (* 2.6 (seeded-unit seed 10))]
    #js {"--gallery-motion-y-scale" y-scale
         "--gallery-motion-x-scale" x-scale
         "--gallery-motion-y-bias" y-bias
         "--gallery-motion-x-bias" x-bias
         "--gallery-motion-rot-scale" rot-scale
         "--gallery-motion-rot-bias" rot-bias
         "--gallery-motion-float-x" (str float-offset "px")
         "--gallery-motion-duration" (str settle-ms "ms")}))

(defn chapter [owner-id owner-type active-frame-id]
  (let [frames @(rf/subscribe [:frames-for-owner owner-type owner-id])
        character-owner? (= "character" (str owner-type))
        add-tile-title (if character-owner?
                         "Add image"
                         "Add New Frame")
        frame-subtitle (if character-owner?
                         "Create the next image for this character"
                         "Create the next frame in this chapter")]
    [:> Box {:className "gallery"}
     (map-indexed (fn [idx frame-row]
                    ^{:key (or (:frameId frame-row) (str "frame-" idx))}
                    [:div.gallery-motion-item
                     {:style (gallery-motion-style (:frameId frame-row))}
                     [frame/frame frame-row
                      {:active? (= active-frame-id (:frameId frame-row))}]])
                  frames)
     [:div.gallery-motion-item
      {:style (gallery-motion-style (str owner-id "-add-tile"))}
      [:article.add-frame-tile
       {:className "frame frame-clickable add-frame-tile"
        :role "button"
        :tabIndex 0
        :aria-label add-tile-title
        :onClick #(rf/dispatch [:add-frame owner-id owner-type])
        :onKeyDown (fn [e]
                     (when (or (= "Enter" (.-key e))
                               (= " " (.-key e)))
                       (.preventDefault e)
                       (rf/dispatch [:add-frame owner-id owner-type])))}
       [:div.add-frame-tile-title add-tile-title]
       [:div.add-frame-tile-sub frame-subtitle]]]]))
