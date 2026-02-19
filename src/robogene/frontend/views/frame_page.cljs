(ns robogene.frontend.views.frame-page
  (:require [re-frame.core :as rf]
            [robogene.frontend.views.frame-card :as frame-card]))

(defn detail-controls [frames idx]
  (let [prev-frame (when (> idx 0) (nth frames (dec idx)))
        next-frame (when (< idx (dec (count frames))) (nth frames (inc idx)))]
    [:div.detail-controls
     [:button.btn
      {:type "button"
       :on-click #(rf/dispatch [:navigate-index])}
      "Back to Gallery"]
     [:button.btn
      {:type "button"
       :disabled (nil? prev-frame)
       :on-click #(when prev-frame
                    (rf/dispatch [:navigate-frame (:sceneNumber prev-frame)]))}
      "Previous"]
     [:button.btn
      {:type "button"
       :disabled (nil? next-frame)
       :on-click #(when next-frame
                    (rf/dispatch [:navigate-frame (:sceneNumber next-frame)]))}
      "Next"]]))

(defn frame-page [route gallery frame-inputs]
  (let [scene-number (:scene-number route)
        ordered (->> gallery (sort-by :sceneNumber) vec)
        idx (first (keep-indexed (fn [i f]
                                   (when (= (:sceneNumber f) scene-number) i))
                                 ordered))
        frame (when (some? idx) (nth ordered idx))]
    [:section
     (if frame
       [:div.detail-page
        [detail-controls ordered idx]
        [frame-card/frame-card frame (get frame-inputs (:frameId frame) "") {:clickable? false}]
        [:div.detail-share
         [:label "Share URL"]
         [:input
          {:type "text"
           :read-only true
           :value (.-href js/location)}]]]
       [:div.detail-missing
        [:p "Frame not found in this episode."]
        [:button.btn
         {:type "button"
          :on-click #(rf/dispatch [:navigate-index])}
         "Back to Gallery"]])]))
