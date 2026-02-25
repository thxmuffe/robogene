(ns webapp.pages.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.components.frame :as frame]))

(defn prev-next-by-id [frames frame-id]
  (loop [remaining (seq frames)
         prev nil]
    (if-let [current (first remaining)]
      (if (= (:frameId current) frame-id)
        {:prev prev
         :next (second remaining)
         :current current}
        (recur (rest remaining) current))
      {:prev nil :next nil :current nil})))

(defn detail-controls [chapter-id frame-neighbors]
  (let [prev-frame (:prev frame-neighbors)
        next-frame (:next frame-neighbors)]
    [:div.detail-controls
     [:button.btn
      {:type "button"
       :on-click #(rf/dispatch [:navigate-index])}
      "Back to Gallery"]
     [:button.btn
      {:type "button"
       :disabled (nil? prev-frame)
       :on-click #(when prev-frame
                    (rf/dispatch [:navigate-frame chapter-id (:frameId prev-frame)]))}
      "Previous"]
     [:button.btn
      {:type "button"
       :disabled (nil? next-frame)
       :on-click #(when next-frame
                    (rf/dispatch [:navigate-frame chapter-id (:frameId next-frame)]))}
      "Next"]]))

(defn frame-page [route frame-inputs open-frame-actions]
  (let [chapter-id (:chapter route)
        ordered @(rf/subscribe [:frames-for-chapter chapter-id])
        frame-id (:frame-id route)
        frame-neighbors (prev-next-by-id ordered frame-id)
        frame (:current frame-neighbors)]
    [:section
     (if frame
       [:div.detail-page
        [detail-controls chapter-id frame-neighbors]
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
