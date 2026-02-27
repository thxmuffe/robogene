(ns webapp.pages.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.shared.controls :as controls]
            [webapp.components.frame :as frame]
            ["react-icons/fa6" :refer [FaFacebookF FaLinkedinIn FaXTwitter FaLink FaXmark]]))

(defn current-share-url []
  (.-href js/location))

(defn open-share! [base-url]
  (let [target (str base-url (js/encodeURIComponent (current-share-url)))]
    (.open js/window target "_blank" "noopener,noreferrer")))

(defn copy-link! []
  (let [url (current-share-url)
        clipboard (some-> js/navigator .-clipboard)]
    (if (fn? (some-> clipboard .-writeText))
      (.writeText clipboard url)
      (do
        (.prompt js/window "Copy link:" url)
        nil))))

(defn share-actions []
  [:div.detail-share
   [:span.share-label "Share"]
   [:div.share-actions
    [:button.share-icon-btn.share-facebook
     {:type "button"
      :aria-label "Share on Facebook"
      :title "Share on Facebook"
      :on-click #(open-share! "https://www.facebook.com/sharer/sharer.php?u=")}
     [:> FaFacebookF]]
    [:button.share-icon-btn.share-linkedin
     {:type "button"
      :aria-label "Share on LinkedIn"
      :title "Share on LinkedIn"
      :on-click #(open-share! "https://www.linkedin.com/sharing/share-offsite/?url=")}
     [:> FaLinkedinIn]]
    [:button.share-icon-btn.share-x
     {:type "button"
      :aria-label "Share on X"
      :title "Share on X"
      :on-click #(let [url (js/encodeURIComponent (current-share-url))
                       text (js/encodeURIComponent "Check out this RoboGene frame")]
                   (.open js/window
                          (str "https://twitter.com/intent/tweet?url=" url "&text=" text)
                          "_blank"
                          "noopener,noreferrer"))}
     [:> FaXTwitter]]
    [:button.share-icon-btn.share-copy
     {:type "button"
      :aria-label "Copy link"
      :title "Copy link"
      :on-click #(copy-link!)}
     [:> FaLink]]]])

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
      "Next"]
     [:button.btn
      {:type "button"
       :on-click #(rf/dispatch [:toggle-frame-fullscreen])}
      "Fullscreen (F)"]]))

(defn frame-page [route frame-inputs open-frame-actions]
  (let [chapter-id (:chapter route)
        ordered @(rf/subscribe [:frames-for-chapter chapter-id])
        frame-id (:frame-id route)
        fullscreen? (true? (:fullscreen? route))
        frame-neighbors (prev-next-by-id ordered frame-id)
        frame (:current frame-neighbors)]
    [:section
     (if frame
       [:div {:class (str "detail-page" (when fullscreen? " detail-page-fullscreen"))}
        (when-not fullscreen?
          [detail-controls chapter-id frame-neighbors])
        [frame/frame frame
         (get frame-inputs (:frameId frame) "")
         {:clickable? false
          :media-nav? true
          :on-media-double-click controls/on-media-double-click
          :actions-open? (true? (get open-frame-actions (:frameId frame)))}]
        (if fullscreen?
          [:button.btn.btn-secondary.fullscreen-close
           {:type "button"
            :aria-label "Close fullscreen"
            :title "Close fullscreen"
            :on-click #(rf/dispatch [:set-frame-fullscreen false])}
           [:> FaXmark]]
          [share-actions])]
       [:div.detail-missing
        [:p "Frame not found in this chapter."]
        [:button.btn
         {:type "button"
          :on-click #(rf/dispatch [:navigate-index])}
         "Back to Gallery"]])]))
