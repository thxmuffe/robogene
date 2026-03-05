(ns webapp.pages.frame-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.frame :as frame]
            [webapp.shared.ui.frame-nav :as frame-nav]
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
    (if-let [active-frame (first remaining)]
      (if (= (:frameId active-frame) frame-id)
        {:prev prev
         :next (second remaining)
         :active active-frame}
        (recur (rest remaining) active-frame))
      {:prev nil :next nil :active nil})))

(def back-button-label-by-page
  {:characters "Back to Roster"
   :saga "Back to Saga_page"})

(defn detail-controls [chapter-id frame-neighbors from-page]
  (let [prev-frame (:prev frame-neighbors)
        next-frame (:next frame-neighbors)
        back-text (get back-button-label-by-page from-page)]
    [:> Group {:className "detail-controls"
               :gap "xs"
               :wrap "wrap"}
     (when back-text
       [:> Button
        {:variant "default"
         :size "sm"
         :onClick #(rf/dispatch [:navigate-from-page])}
        back-text])
     [:> Button
      {:variant "default"
      :size "sm"
       :disabled (nil? prev-frame)
       :onClick #(when prev-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId prev-frame) from-page]))}
      "Previous"]
     [:> Button
      {:variant "default"
       :size "sm"
       :disabled (nil? next-frame)
       :onClick #(when next-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId next-frame) from-page]))}
      "Next"]
     [:> Button
      {:variant "filled"
       :size "sm"
       :color "orange"
       :onClick #(rf/dispatch [:toggle-frame-fullscreen])}
      "Fullscreen (F)"]]))

(defn handle-frame-page-key-down! [{:keys [fullscreen? active-frame-id prompt-open? from-page]} e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
    (when-not (or (interaction/modal-open?)
                  (interaction/menu-open?)
                  (interaction/editable-target? (.-target e)))
      (cond
        (= "Enter" key)
        (when active-frame-id
          (interaction/halt! e)
          (rf/dispatch [:set-frame-actions-open active-frame-id true]))

        (= "Escape" key)
        (do
          (interaction/halt! e)
          (cond
            prompt-open?
            (do
              (rf/dispatch [:set-frame-actions-open active-frame-id false])
              (.requestAnimationFrame js/window
                                      (fn []
                                        (frame-nav/focus-subtitle! active-frame-id))))

            fullscreen?
            (rf/dispatch [:set-frame-fullscreen false])

            :else
            (do
              (when active-frame-id
                (rf/dispatch [:set-active-frame active-frame-id]))
              (when from-page
                (rf/dispatch [:navigate-from-page])))))

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
  (r/with-let [key-context* (r/atom nil)
               focused-subtitle-key* (r/atom nil)
               key-handler (fn [e]
                             (handle-frame-page-key-down! @key-context* e))]
    (.addEventListener js/window "keydown" key-handler)
    (let [chapter-id (:chapter route)
          ordered @(rf/subscribe [:frames-for-chapter chapter-id])
          frame-id (:frame-id route)
          from-page (:from-page route)
          fullscreen? (true? (:fullscreen? route))
          prompt-open? (true? (get open-frame-actions frame-id))
          frame-neighbors (prev-next-by-id ordered frame-id)
          active-frame (:active frame-neighbors)]
      (reset! key-context* {:fullscreen? fullscreen?
                            :active-frame-id frame-id
                            :prompt-open? prompt-open?
                            :from-page from-page})
      (let [focus-key [frame-id fullscreen? prompt-open?]]
        (when (and active-frame
                   (not prompt-open?)
                   (not= focus-key @focused-subtitle-key*))
          (reset! focused-subtitle-key* focus-key)
          (.requestAnimationFrame js/window
                                  (fn []
                                    (frame-nav/focus-subtitle! frame-id)))))
      [:section
       (if active-frame
         [:> Box {:className (str "detail-page" (when fullscreen? " detail-page-fullscreen"))}
          (when-not fullscreen?
            [detail-controls chapter-id frame-neighbors from-page])
          [frame/frame active-frame
           (get frame-inputs (:frameId active-frame) "")
           {:clickable? false
            :media-nav? true
            :actions-open? (true? (get open-frame-actions (:frameId active-frame)))
            :image-fit (if fullscreen? "contain" "cover")}]
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
          (when-let [back-text (get back-button-label-by-page from-page)]
            [:> Button
             {:variant "default"
              :size "sm"
              :onClick #(rf/dispatch [:navigate-from-page])}
             back-text])])])
    (finally
      (.removeEventListener js/window "keydown" key-handler))))
