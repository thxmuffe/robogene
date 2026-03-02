(ns webapp.pages.frame-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.frame :as frame]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon Box Button Group Tooltip]]
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

(defn share-actions [saga-name]
  [:> Group {:className "detail-share"
             :gap "xs"
             :align "center"}
   [:> Group {:className "share-actions"
              :gap "xs"}
    [:> Tooltip {:label "Share on Facebook"}
     [:> ActionIcon
      {:className "share-icon-btn share-facebook"
       :aria-label "Share on Facebook"
       :variant "subtle"
       :radius "xl"
       :onClick #(open-share! "https://www.facebook.com/sharer/sharer.php?u=")}
      [:> FaFacebookF]]]
    [:> Tooltip {:label "Share on LinkedIn"}
     [:> ActionIcon
      {:className "share-icon-btn share-linkedin"
       :aria-label "Share on LinkedIn"
       :variant "subtle"
       :radius "xl"
       :onClick #(open-share! "https://www.linkedin.com/sharing/share-offsite/?url=")}
      [:> FaLinkedinIn]]]
    [:> Tooltip {:label "Share on X"}
     [:> ActionIcon
      {:className "share-icon-btn share-x"
       :aria-label "Share on X"
       :variant "subtle"
       :radius "xl"
       :onClick #(let [url (js/encodeURIComponent (current-share-url))
                        text (js/encodeURIComponent (str "Check out this " saga-name " frame"))]
                    (.open js/window
                           (str "https://twitter.com/intent/tweet?url=" url "&text=" text)
                           "_blank"
                           "noopener,noreferrer"))}
      [:> FaXTwitter]]]
    [:> Tooltip {:label "Copy link"}
     [:> ActionIcon
      {:className "share-icon-btn share-copy"
       :aria-label "Copy link"
       :variant "subtle"
       :radius "xl"
       :onClick #(copy-link!)}
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
    [:> Group {:className "detail-controls"
               :gap "xs"
               :wrap "wrap"}
     [:> Button
      {:variant "default"
       :size "sm"
       :onClick #(rf/dispatch [:navigate-index])}
      "Back to Gallery"]
     [:> Button
      {:variant "default"
       :size "sm"
       :disabled (nil? prev-frame)
       :onClick #(when prev-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId prev-frame)]))}
      "Previous"]
     [:> Button
      {:variant "default"
       :size "sm"
       :disabled (nil? next-frame)
       :onClick #(when next-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId next-frame)]))}
      "Next"]
     [:> Button
      {:variant "filled"
       :size "sm"
       :color "orange"
       :onClick #(rf/dispatch [:toggle-frame-fullscreen])}
      "Fullscreen (F)"]]))

(defn handle-frame-page-key-down! [fullscreen? e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
    (when-not (or (interaction/modal-open?)
                  (interaction/menu-open?)
                  (interaction/editable-target? (.-target e)))
      (cond
        (= "Escape" key)
        (do
          (interaction/halt! e)
          (if fullscreen?
            (rf/dispatch [:set-frame-fullscreen false])
            (rf/dispatch [:navigate-index])))

        (= "f" lower-key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:toggle-fullscreen-shortcut]))

        (= "ArrowLeft" key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:navigate-relative-frame -1]))

        (= "ArrowRight" key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:navigate-relative-frame 1]))

        :else nil))))

(defn frame-page [route frame-inputs open-frame-actions saga-name]
  (r/with-let [key-handler (fn [e]
                             (handle-frame-page-key-down! (true? (:fullscreen? route)) e))]
    (.addEventListener js/window "keydown" key-handler)
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
            :actions-open? (true? (get open-frame-actions (:frameId frame)))}]
          (if fullscreen?
            [:> ActionIcon
             {:className "fullscreen-close"
              :color "orange"
              :aria-label "Close fullscreen"
              :title "Close fullscreen"
              :variant "filled"
              :radius "xl"
              :onClick #(rf/dispatch [:set-frame-fullscreen false])}
             [:> FaXmark]]
            [share-actions saga-name])]
         [:> Box {:className "detail-missing"}
          [:p "Frame not found in this chapter."]
          [:> Button
           {:variant "default"
            :size "sm"
            :onClick #(rf/dispatch [:navigate-index])}
           "Back to Gallery"]])])
    (finally
      (.removeEventListener js/window "keydown" key-handler))))
