(ns webapp.pages.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.shared.controls :as controls]
            [webapp.components.frame :as frame]
            ["@mui/material/Button" :default Button]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/Box" :default Box]
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
  [:> Stack {:className "detail-share"
             :direction "row"
             :spacing 1
             :alignItems "center"}
   [:> Box {:className "share-label"} "Share"]
   [:> Stack {:className "share-actions"
              :direction "row"
              :spacing 1}
    [:> Tooltip {:title "Share on Facebook"}
     [:> IconButton
      {:className "share-icon-btn"
       :sx {:width 42
            :height 42
            :color "#fff"
            :bgcolor "#1877f2"
            "&:hover" {:bgcolor "#1668d5"}}
       :aria-label "Share on Facebook"
       :on-click #(open-share! "https://www.facebook.com/sharer/sharer.php?u=")}
      [:> FaFacebookF]]]
    [:> Tooltip {:title "Share on LinkedIn"}
     [:> IconButton
      {:className "share-icon-btn"
       :sx {:width 42
            :height 42
            :color "#fff"
            :bgcolor "#0a66c2"
            "&:hover" {:bgcolor "#0855a1"}}
       :aria-label "Share on LinkedIn"
       :on-click #(open-share! "https://www.linkedin.com/sharing/share-offsite/?url=")}
      [:> FaLinkedinIn]]]
    [:> Tooltip {:title "Share on X"}
     [:> IconButton
      {:className "share-icon-btn"
       :sx {:width 42
            :height 42
            :color "#fff"
            :bgcolor "#111"
            "&:hover" {:bgcolor "#000"}}
       :aria-label "Share on X"
       :on-click #(let [url (js/encodeURIComponent (current-share-url))
                        text (js/encodeURIComponent "Check out this RoboGene frame")]
                    (.open js/window
                           (str "https://twitter.com/intent/tweet?url=" url "&text=" text)
                           "_blank"
                           "noopener,noreferrer"))}
      [:> FaXTwitter]]]
    [:> Tooltip {:title "Copy link"}
     [:> IconButton
      {:className "share-icon-btn"
       :sx {:width 42
            :height 42
            :color "#fff"
            :bgcolor "#20639b"
            "&:hover" {:bgcolor "#1a5180"}}
       :aria-label "Copy link"
       :on-click #(copy-link!)}
      [:> FaLink]]]]])

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
    [:> Stack {:className "detail-controls"
               :direction "row"
               :spacing 1
               :flexWrap "wrap"}
     [:> Button
      {:variant "outlined"
      :size "small"
       :on-click #(rf/dispatch [:navigate-index])}
      "Back to Gallery"]
     [:> Button
      {:variant "outlined"
       :size "small"
       :disabled (nil? prev-frame)
       :on-click #(when prev-frame
                    (rf/dispatch [:navigate-frame chapter-id (:frameId prev-frame)]))}
      "Previous"]
     [:> Button
      {:variant "outlined"
       :size "small"
       :disabled (nil? next-frame)
       :on-click #(when next-frame
                    (rf/dispatch [:navigate-frame chapter-id (:frameId next-frame)]))}
      "Next"]
     [:> Button
      {:variant "contained"
      :size "small"
      :color "secondary"
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
       [:> Box {:className (str "detail-page" (when fullscreen? " detail-page-fullscreen"))}
        (when-not fullscreen?
          [detail-controls chapter-id frame-neighbors])
        [frame/frame frame
         (get frame-inputs (:frameId frame) "")
         {:clickable? false
          :media-nav? true
          :on-media-double-click controls/on-media-double-click
          :actions-open? (true? (get open-frame-actions (:frameId frame)))}]
        (if fullscreen?
          [:> IconButton
           {:sx {:position "absolute"
                 :top 16
                 :right 16
                 :zIndex 1800
                 :width 44
                 :height 44}
            :color "secondary"
            :aria-label "Close fullscreen"
            :title "Close fullscreen"
            :on-click #(rf/dispatch [:set-frame-fullscreen false])}
           [:> FaXmark]]
          [share-actions])]
       [:> Box {:className "detail-missing"}
        [:p "Frame not found in this chapter."]
        [:> Button
         {:variant "outlined"
          :size "small"
          :on-click #(rf/dispatch [:navigate-index])}
         "Back to Gallery"]])]))
