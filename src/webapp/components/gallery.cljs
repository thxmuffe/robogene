(ns webapp.components.gallery
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.components.entity-card :as entity-card]
            [webapp.shared.model :as model]
            [webapp.shared.visual-effects :as visual-effects]
            ["@mantine/core" :refer [Box]]))

(defn search-gallery
  "Generic gallery for search results. Renders sequences if an entity has children,
   frames via the frame component, otherwise generic item cards."
  [{:keys [entity-ids]}]
  [:> Box {:className "gallery"}
   (for [entity-id entity-ids
         :let [entity @(rf/subscribe [:entity entity-id])]
         :when entity]
     ^{:key entity-id}
     [:div.gallery-motion-item
      {:style (visual-effects/gallery-motion-style entity-id)}
      [entity-card/entity-card
       {:entity entity
        :on-click #(set! (.-hash js/location) (model/route-hash-for-entity entity))}]])])

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
                     {:style (visual-effects/gallery-motion-style (:chapterId chapter))}
                     [chapter-preview-card chapter]])
                  chapters)]))
