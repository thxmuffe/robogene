(ns webapp.components.frame
  (:require [clojure.string :as str]
            [webapp.shared.controls :as controls]
            [webapp.components.frame-actions :as frame-actions]))

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
         :on-change (controls/on-frame-editor-change frameId editable?)}]
    [:div.frame-editor
     [:textarea.direction-input textarea-props]
     (when (and (seq (or error "")) (not busy?))
       [:div.error-line (str "Last error: " error)])]))

(defn frame-placeholder [{:keys [status] :as frame}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Click to generate")
        cta? (not (or (= status "queued") (= status "processing")))]
    [:div.placeholder-img
     (when (or (= status "queued") (= status "processing"))
       [:div.spinner])
     (if cta?
       [:div.placeholder-action-inline
        [frame-actions/frame-action-button frame]]
       [:div.placeholder-text label])]))

(defn frame
  ([frame frame-input]
   [frame frame frame-input {:clickable? true}])
   ([frame frame-input {:keys [clickable? active? actions-open?]
                        :or {clickable? true active? false actions-open? false}}]
   (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))
         busy? (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         editable? (true? actions-open?)
         frame* (assoc frame :actionsOpen actions-open?)
         attrs (cond-> {:data-frame-id (:frameId frame)
                        :class (str "frame"
                                    (when clickable? " frame-clickable")
                                    (when active? " frame-active"))
                        :on-mouse-enter (controls/on-frame-mouse-enter (:frameId frame))
                        :on-blur (controls/on-frame-blur-close-actions (:frameId frame) actions-open?)}
                 clickable? (assoc :role "button"
                                   :tab-index 0
                                   :on-focus (controls/on-frame-focus (:frameId frame))
                                   :on-click (controls/on-frame-click (:chapterId frame) (:frameId frame))
                                   :on-key-down (controls/on-frame-keydown-open (:chapterId frame) (:frameId frame))))]
     [:article attrs
      [:div.media-shell
       (if has-image?
         [:<>
          [frame-image frame*]
          (when busy?
            [:div.media-loading-overlay
             [:div.spinner]
             [:div.placeholder-text "Generating..."]])]
         [frame-placeholder frame])]
      [:div.meta
       (when busy?
         [:span.badge.queue "In Queue"])
       [frame-editor frame* frame-input editable?]
       [frame-actions/frame-actions-row frame* editable?]]])))
