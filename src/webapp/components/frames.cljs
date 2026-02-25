(ns webapp.components.frames
  (:require [re-frame.core :as rf]
            [webapp.components.frame :as frame]))

(defn chapter-frames [chapter-id frame-inputs open-frame-actions active-frame-id]
  (let [frames @(rf/subscribe [:frames-for-chapter chapter-id])]
    [:div.gallery
     (map-indexed (fn [idx frame-row]
                    ^{:key (or (:frameId frame-row) (str "frame-" idx))}
                    [frame/frame frame-row
                     (get frame-inputs (:frameId frame-row) "")
                     {:active? (= active-frame-id (:frameId frame-row))
                      :actions-open? (true? (get open-frame-actions (:frameId frame-row)))}])
                  frames)]))
