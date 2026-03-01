(ns webapp.components.frames
  (:require [re-frame.core :as rf]
            [webapp.components.frame :as frame]
            ["@mui/material/Box" :default Box]))

(defn chapter-frames [chapter-id frame-inputs open-frame-actions image-ui-by-frame-id active-frame-id]
  (let [frames @(rf/subscribe [:frames-for-chapter chapter-id])]
    [:> Box {:className "gallery"}
     (map-indexed (fn [idx frame-row]
                    ^{:key (or (:frameId frame-row) (str "frame-" idx))}
                    [frame/frame frame-row
                     (get frame-inputs (:frameId frame-row) "")
                     {:active? (= active-frame-id (:frameId frame-row))
                      :actions-open? (true? (get open-frame-actions (:frameId frame-row)))
                      :image-ui (get image-ui-by-frame-id (:frameId frame-row) {:state :idle})}])
                  frames)]))
