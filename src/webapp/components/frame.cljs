(ns webapp.components.frame
  (:require [clojure.string :as str]
            [webapp.shared.controls :as controls]
            [webapp.components.frame-actions :as frame-actions]
            ["@mui/material/Button" :default Button]
            ["@mui/material/TextField" :default TextField]))

(defn frame-image [{:keys [imageDataUrl frameId]}]
  [:img {:src (or imageDataUrl "") :alt (str "Frame " frameId)}])

(defn frame-editor [{:keys [frameId status error]} frame-input editable?]
  (let [busy? (or (= status "queued") (= status "processing"))
        textarea-props
        {:value (or frame-input "")
         :placeholder "Describe this frame..."
         :readOnly (not editable?)
         :title (when-not editable? "Click to edit this description.")
         :on-click (controls/on-frame-editor-enable frameId)
         :on-double-click (controls/on-frame-editor-enable frameId)
         :on-focus controls/on-frame-editor-focus
         :on-key-down (controls/on-frame-editor-keydown frameId busy? editable?)
         :on-change (controls/on-frame-editor-change frameId editable?)}
        send-disabled? busy?
        send-title (if editable?
                     (if busy? "Generating..." "Generate frame (Cmd/Ctrl+Enter)")
                     "Click to edit prompt")]
    [:div.frame-editor
     [:div.chat-composer
      [:> TextField
       (merge {:className "direction-input subtitle-input"
               :multiline true
               :minRows 2
               :maxRows 14
               :fullWidth true
               :variant "filled"
               :InputProps #js {:disableUnderline true
                                :readOnly (not editable?)}}
              textarea-props)]
      [:> Button
       {:className "send-btn"
        :variant "contained"
        :color "secondary"
        :size "small"
        :aria-label send-title
        :title send-title
        :disabled send-disabled?
        :on-click (controls/on-frame-send-click frameId busy? editable?)}
       "Send"]]
     (when (and (seq (or error "")) (not busy?))
       [:div.error-line (str "Last error: " error)])]))

(defn frame-placeholder [{:keys [status]}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Add subtitle and generate")]
    [:div.placeholder-img
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
         attrs (cond-> {:data-frame-id (:frameId frame)
                        :class (str "frame"
                                    (when clickable? " frame-clickable")
                                    (when active? " frame-active"))
                        :on-mouse-enter (controls/on-frame-activate (:frameId frame))
                        :on-blur (controls/on-frame-blur-close-actions (:frameId frame) actions-open?)}
                 clickable? (assoc :role "button"
                                   :tab-index 0
                                   :on-focus (controls/on-frame-activate (:frameId frame))
                                   :on-click (controls/on-frame-click (:chapterId frame) (:frameId frame))
                                   :on-key-down (controls/on-frame-keydown-open (:chapterId frame) (:frameId frame))))]
     [:article attrs
      [:div.media-shell media-attrs
       (if has-image?
         [:<>
          [frame-image frame*]
          (when busy?
            [:div.media-loading-overlay
             [:div.spinner]
             [:div.placeholder-text "Generating..."]])]
         [frame-placeholder frame])
       [frame-editor frame* frame-input editable?]
       (when media-nav?
         [:div.media-nav-zones
          [:> Button
           {:className "media-nav-zone media-nav-prev"
            :variant "text"
            :aria-label "Previous frame"
            :on-click (controls/on-media-nav-click -1)}]
          [:> Button
           {:className "media-nav-zone media-nav-next"
            :variant "text"
            :aria-label "Next frame"
            :on-click (controls/on-media-nav-click 1)}]])]
      [:div.meta
       (when busy?
         [:span.badge.queue "In Queue"])
       [frame-actions/frame-actions-row frame* editable?]]])))
