(ns webapp.views.frame-view
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [webapp.views.frame-actions :as frame-actions]))

(defn frame-image [{:keys [imageDataUrl frameNumber]}]
  [:img {:src (or imageDataUrl "") :alt (str "Frame " frameNumber)}])

(defn frame-editor [{:keys [frameId status error]} frame-input editable?]
  (let [busy? (or (= status "queued") (= status "processing"))
        textarea-props
        {:value (or frame-input "")
         :placeholder "Describe this frame..."
         :readOnly (not editable?)
         :title (when-not editable? "Click to edit this description.")
         :on-click (fn [e]
                     (let [el (.-currentTarget e)]
                       (.stopPropagation e)
                       (rf/dispatch [:set-frame-actions-open frameId true])
                       (js/setTimeout
                        (fn []
                          (.focus el))
                        0)))
         :on-double-click (fn [e]
                            (let [el (.-currentTarget e)]
                              (.stopPropagation e)
                              (rf/dispatch [:set-frame-actions-open frameId true])
                              (js/setTimeout
                               (fn []
                                 (.focus el))
                               0)))
         :on-focus (fn [e]
                     (.stopPropagation e))
         :on-key-down (fn [e]
                        (let [enter? (= "Enter" (.-key e))
                              submit? (and (not busy?) enter? (not (.-shiftKey e)))]
                          (cond
                            submit?
                            (do
                              (.preventDefault e)
                              (.stopPropagation e)
                              (rf/dispatch [:set-frame-actions-open frameId true])
                              (rf/dispatch [:generate-frame frameId]))
                            (and (not editable?) enter?)
                            (do
                              (.preventDefault e)
                              (.stopPropagation e)
                              (rf/dispatch [:set-frame-actions-open frameId true]))
                            :else
                            (.stopPropagation e))))
         :on-change (fn [e]
                      (when editable?
                        (let [next-value (.. e -target -value)]
                          (rf/dispatch (vector :frame-direction-changed frameId next-value)))))}]
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

(defn frame-view
  ([frame frame-input]
   [frame-view frame frame-input {:clickable? true}])
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
                        :on-mouse-enter #(rf/dispatch [:set-active-frame (:frameId frame)])
                        :on-blur (fn [e]
                                   (when (true? actions-open?)
                                     (let [container (.-currentTarget e)]
                                       (js/setTimeout
                                        (fn []
                                          (let [active-el (.-activeElement js/document)]
                                            (when (and (some? active-el)
                                                       (not (.contains container active-el)))
                                              (rf/dispatch [:set-frame-actions-open (:frameId frame) false]))))
                                        60))))}
                 clickable? (assoc :role "button"
                                   :tab-index 0
                                   :on-focus #(rf/dispatch [:set-active-frame (:frameId frame)])
                                   :on-click #(do
                                                (rf/dispatch [:set-active-frame (:frameId frame)])
                                                (rf/dispatch [:navigate-frame (:episodeId frame) (:frameNumber frame)]))
                                   :on-key-down (fn [e]
                                                   (when (or (= "Enter" (.-key e))
                                                             (= " " (.-key e)))
                                                     (.preventDefault e)
                                                     (rf/dispatch [:set-active-frame (:frameId frame)])
                                                     (rf/dispatch [:navigate-frame (:episodeId frame) (:frameNumber frame)])))))]
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
