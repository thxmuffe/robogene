(ns webapp.pages.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.components.frame :as frame]))

(defn detail-controls [chapter-id frames idx]
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
                    (rf/dispatch [:navigate-frame chapter-id (:frameNumber prev-frame)]))}
      "Previous"]
     [:button.btn
      {:type "button"
       :disabled (nil? next-frame)
       :on-click #(when next-frame
                    (rf/dispatch [:navigate-frame chapter-id (:frameNumber next-frame)]))}
      "Next"]]))

(defn frame-page [route chapters frame-inputs open-frame-actions]
  (let [chapter-id (:chapter route)
        chapter (some (fn [row] (when (= (:chapterId row) chapter-id) row)) chapters)
        frame-number (:frame-number route)
        ordered (model/ordered-frames (:frames chapter))
        idx (model/frame-index-by-number ordered frame-number)
        frame (when (some? idx) (nth ordered idx))]
    [:section
     (if frame
       [:div.detail-page
        [detail-controls chapter-id ordered idx]
        [frame/frame frame
         (get frame-inputs (:frameId frame) "")
         {:clickable? false
          :actions-open? (true? (get open-frame-actions (:frameId frame)))}]
        [:div.detail-share
         [:label "Share URL"]
         [:input
          {:type "text"
           :read-only true
           :value (.-href js/location)}]]]
       [:div.detail-missing
        [:p "Frame not found in this chapter."]
        [:button.btn
         {:type "button"
          :on-click #(rf/dispatch [:navigate-index])}
         "Back to Gallery"]])]))
