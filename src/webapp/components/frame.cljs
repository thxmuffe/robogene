(ns webapp.components.frame
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.prompt :as prompt]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Card" :default Card]
            ["@mui/material/CardMedia" :default CardMedia]
            ["@mui/material/Box" :default Box]
            ["@mui/material/Chip" :default Chip]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/icons-material/Close" :default CloseIcon]))

(defn on-editor-enable [frame-id]
  (fn [e]
    (interaction/stop! e)
    (rf/dispatch [:set-frame-actions-open frame-id true])
    nil))

(defn on-editor-enable-keydown [frame-id]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (interaction/prevent! e)
      ((on-editor-enable frame-id) e))))

(defn on-editor-close [frame-id]
  (fn [e]
    (interaction/stop! e)
    (rf/dispatch [:set-frame-actions-open frame-id false])))

(defn on-frame-click [chapter-id frame-id]
  (fn [e]
    (when-not (interaction/interactive-child-event? e)
      (controls/navigate-frame! chapter-id frame-id))))

(defn on-media-nav-click [delta]
  (fn [e]
    (interaction/halt! e)
    (rf/dispatch [:navigate-relative-frame delta])))

(defn frame-image [{:keys [imageDataUrl frameId]}]
  [:> CardMedia
   {:component "img"
    :src (or imageDataUrl "")
    :alt (str "Frame " frameId)}])

(defn subtitle-display [{:keys [frameId description]} frame-input]
  (let [subtitle (str/trim (or frame-input description ""))]
    [:> Box {:className "subtitle-display"
             :role "button"
             :tab-index 0
             :title "Click subtitle to edit prompt"
             :on-click (on-editor-enable frameId)
             :on-double-click (on-editor-enable frameId)
             :on-key-down (on-editor-enable-keydown frameId)}
     [:span {:className "subtitle-display-text"}
      (if (seq subtitle)
        subtitle
        "Click subtitle to add prompt")]]))

(defn frame-placeholder [{:keys [status]}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Add subtitle and generate")]
    [:> Box {:className "placeholder-img"}
     (when (or (= status "queued") (= status "processing"))
      [:div.spinner])
     [:div.placeholder-text label]]))

(defn frame
  ([frame frame-input]
   [frame frame-input {:clickable? true}])
  ([frame frame-input {:keys [clickable? active? actions-open? media-nav? on-media-double-click]
                       :or {clickable? true active? false actions-open? false media-nav? false}}]
   (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))
         busy? (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         editable? (true? actions-open?)
         frame* (assoc frame :actionsOpen actions-open?)
         media-attrs (cond-> {}
                       (fn? on-media-double-click) (assoc :on-double-click on-media-double-click))
         attrs {:data-frame-id (:frameId frame)
                :className (str "frame frame-card"
                                (when clickable? " frame-clickable")
                                (when active? " frame-active"))
                :on-mouse-enter (controls/on-frame-activate (:frameId frame))}
         nav-attrs (cond-> {:className "frame-nav-surface"}
                     clickable? (assoc :on-click (on-frame-click (:chapterId frame) (:frameId frame))))]
     [:> Card
      (merge attrs
             {:component "article"
              :variant "outlined"})
     [:> Box (merge {:className "media-shell"} media-attrs)
       [:> Box nav-attrs
        (if has-image?
          [:<>
           [frame-image frame*]
           (when busy?
             [:div.media-loading-overlay
              [:div.spinner]
              [:div.placeholder-text "Generating..."]])]
          [frame-placeholder frame])]
       (if editable?
         [prompt/prompt-panel frame* frame-input]
         [subtitle-display frame* frame-input])
       (when editable?
         [:> IconButton
         {:className "frame-prompt-close"
           :aria-label "Close prompt"
           :title "Close prompt"
           :on-click (on-editor-close (:frameId frame))}
          [:> CloseIcon]])
       (when media-nav?
         [:div.media-nav-zones
          [:> Button
           {:className "media-nav-zone media-nav-prev"
            :variant "text"
            :tabIndex -1
            :disableRipple true
            :disableFocusRipple true
            :on-focus (fn [e] (.blur (.-currentTarget e)))
            :aria-label "Previous frame"
            :on-click (on-media-nav-click -1)}]
          [:> Button
          {:className "media-nav-zone media-nav-next"
            :variant "text"
            :tabIndex -1
            :disableRipple true
            :disableFocusRipple true
            :on-focus (fn [e] (.blur (.-currentTarget e)))
            :aria-label "Next frame"
            :on-click (on-media-nav-click 1)}]])]
      (when busy?
        [:> Chip {:className "badge queue badge-queue-overlay"
                  :size "small"
                  :label "In Queue"}])])))
