(ns robogene.frontend.views.frame-page
  (:require [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]
            [robogene.frontend.views.frame-view :as frame-view]))

(defn detail-controls [episode-id frames idx]
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
                    (rf/dispatch [:navigate-frame episode-id (:frameNumber prev-frame)]))}
      "Previous"]
     [:button.btn
      {:type "button"
       :disabled (nil? next-frame)
       :on-click #(when next-frame
                    (rf/dispatch [:navigate-frame episode-id (:frameNumber next-frame)]))}
      "Next"]]))

(defn frame-page [route episodes frame-inputs]
  (let [episode-id (:episode route)
        episode (some (fn [row] (when (= (:episodeId row) episode-id) row)) episodes)
        frame-number (:frame-number route)
        ordered (model/ordered-frames (:frames episode))
        idx (model/frame-index-by-number ordered frame-number)
        frame (when (some? idx) (nth ordered idx))]
    [:section
     (if frame
       [:div.detail-page
        [detail-controls episode-id ordered idx]
        [frame-view/frame-view frame (get frame-inputs (:frameId frame) "") {:clickable? false}]
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
