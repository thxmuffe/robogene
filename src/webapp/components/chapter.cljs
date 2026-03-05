(ns webapp.components.chapter
  (:require [re-frame.core :as rf]
            [webapp.components.frame :as frame]
            ["@mantine/core" :refer [Box]]))

(defn chapter [owner-id owner-type frame-inputs open-frame-actions active-frame-id]
  (let [frames @(rf/subscribe [:frames-for-owner owner-type owner-id])
        frame-subtitle (if (= "character" (str owner-type))
                         "Create the next image for this character"
                         "Create the next frame in this chapter")]
    [:> Box {:className "gallery"}
     (map-indexed (fn [idx frame-row]
                    ^{:key (or (:frameId frame-row) (str "frame-" idx))}
                    [frame/frame frame-row
                     (get frame-inputs (:frameId frame-row) "")
                     {:active? (= active-frame-id (:frameId frame-row))
                      :actions-open? (true? (get open-frame-actions (:frameId frame-row)))}])
                  frames)
     [:article.add-frame-tile
      {:className "frame frame-clickable add-frame-tile"
       :role "button"
       :tabIndex 0
       :aria-label "Add new frame"
       :onClick #(rf/dispatch [:add-frame owner-id owner-type])
       :onKeyDown (fn [e]
                    (when (or (= "Enter" (.-key e))
                              (= " " (.-key e)))
                      (.preventDefault e)
                      (rf/dispatch [:add-frame owner-id owner-type])))}
      [:div.add-frame-tile-title "Add New Frame"]
      [:div.add-frame-tile-sub frame-subtitle]]]))
