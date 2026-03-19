(ns webapp.components.gallery
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.components.entity-card :as entity-card]
            [webapp.shared.model :as model]
            ["@mantine/core" :refer [Box]]))

(defn- seeded-unit [seed n]
  (let [x (* (+ seed (* 97 n)) 12.9898)
        s (js/Math.sin x)]
    (- (* (- s (js/Math.floor s)) 2) 1)))

(defn- gallery-motion-style [seed-key]
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

(defn search-gallery
  "Generic gallery for search results. Renders sequences if an entity has children,
   frames via the frame component, otherwise generic item cards."
  [{:keys [entities]}]
  [:> Box {:className "gallery"}
   (map-indexed
    (fn [idx ent]
      ^{:key (or (:id ent) (str "search-" idx))}
      [:div.gallery-motion-item
       {:style (gallery-motion-style (:id ent))}
       [entity-card/entity-card
        {:entity ent
         :on-click #(set! (.-hash js/location) (model/route-hash-for-entity ent))}]])
    entities)])

(defn- chapter-preview-card [chapter]
  (let [chapter-id (:chapterId chapter)
        name (or (some-> (:name chapter) str/trim not-empty)
                 (some-> (:description chapter) str/trim not-empty)
                 "Chapter")
        description (some-> (:description chapter) str/trim not-empty)
        frames @(rf/subscribe [:frames-for-chapter chapter-id])
        preview-url (some->> frames
                             (keep (fn [frame-row]
                                     (some-> (:imageUrl frame-row) str/trim not-empty)))
                             first)
        frame-count (count frames)]
    [:article
     {:className "frame frame-clickable add-frame-tile chapter-preview-tile"
      :role "button"
      :tabIndex 0
      :onClick #(rf/dispatch [:navigate-chapter-page chapter-id])
      :onKeyDown (fn [e]
                   (when (or (= "Enter" (.-key e))
                             (= " " (.-key e)))
                     (.preventDefault e)
                     (rf/dispatch [:navigate-chapter-page chapter-id])))}
     (if preview-url
       [:img {:className "chapter-preview-image"
              :src preview-url
              :alt (str name " preview")}]
       [:div.chapter-preview-placeholder])
     [:div.add-frame-tile-title name]
     [:div.add-frame-tile-sub
      (or description
          (str frame-count " frame" (when (not= 1 frame-count) "s")))]]))

(defn chapter-preview-gallery [saga-id]
  (let [chapters @(rf/subscribe [:chapters-by-saga-id saga-id])]
    [:> Box {:className "gallery"}
     (map-indexed (fn [idx chapter]
                    ^{:key (or (:chapterId chapter) (str "chapter-preview-" idx))}
                    [:div.gallery-motion-item
                     {:style (gallery-motion-style (:chapterId chapter))}
                     [chapter-preview-card chapter]])
                  chapters)]))
