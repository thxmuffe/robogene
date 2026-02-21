(ns robogene.frontend.views.frame-view
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            ["sweetalert2" :as Swal]))

(defn frame-image [{:keys [imageDataUrl frameNumber]}]
  [:img {:src (or imageDataUrl "") :alt (str "Frame " frameNumber)}])

(defn frame-editor [{:keys [frameId status error actionsOpen]} frame-input]
  (let [busy? (or (= status "queued") (= status "processing"))
        editable? (and (true? actionsOpen) (not busy?))]
    [:div.frame-editor
     [:textarea.direction-input
      {:value (or frame-input "")
       :placeholder "Describe this frame..."
       :disabled (not editable?)
       :title (when-not editable?
                "Open edit mode to edit this description.")
       :on-click (fn [e]
                   (.stopPropagation e)
                   (rf/dispatch [:set-frame-actions-open frameId true]))
       :on-focus #(rf/dispatch [:set-frame-actions-open frameId true])
       :on-key-down (fn [e]
                      (if (and (= "Enter" (.-key e))
                               (not (.-shiftKey e)))
                        (do
                          (.preventDefault e)
                          (.stopPropagation e)
                          (rf/dispatch [:generate-frame frameId]))
                        (.stopPropagation e)))
       :on-change (fn [e]
                    (rf/dispatch [:frame-direction-changed frameId (.. e -target -value)])
                    (rf/dispatch [:set-frame-actions-open frameId true]))}]
     (when (and (seq (or error "")) (not busy?))
       [:div.error-line (str "Last error: " error)])]))

(declare frame-action-button)

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
        [frame-action-button frame]]
       [:div.placeholder-text label])]))

(defn frame-action-button [{:keys [frameId status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))
        hint (if busy?
               (if (= status "processing") "Generating..." "Queued...")
               (if has-image? "Regenerate frame" "Generate frame"))
        label (if busy?
                (if (= status "processing") "Working" "Queued")
                (if has-image? "Regen" "Gen"))
        icon (if busy?
               (if (= status "processing") "..." "o")
               (if has-image? "R" "+"))]
    [:button.btn.btn-primary.btn-small
     {:type "button"
      :aria-label hint
      :disabled busy?
      :on-mouse-down #(.stopPropagation %)
      :on-click (fn [e]
                  (.stopPropagation e)
                  (rf/dispatch [:generate-frame frameId]))}
     [:span.btn-icon icon]
     [:span.btn-hint label]]))

(defn clear-image-button [{:keys [frameId frameNumber status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))]
    (when has-image?
      [:button.btn.btn-secondary.btn-small
       {:type "button"
        :aria-label "Remove image"
        :disabled busy?
        :on-click (fn [e]
                    (.stopPropagation e)
                    (-> (.fire Swal
                              (clj->js {:title (str "Remove image from Frame " frameNumber "?")
                                        :text "The frame and its description will stay."
                                        :icon "warning"
                                        :showCancelButton true
                                        :confirmButtonText "Remove image"
                                        :cancelButtonText "Cancel"
                                        :confirmButtonColor "#20639b"}))
                        (.then (fn [result]
                                 (when (true? (.-isConfirmed result))
                                   (rf/dispatch [:clear-frame-image frameId]))))))}
       [:span.btn-icon "x"]
       [:span.btn-hint "Remove"]])))

(defn frame-actions-menu [frame]
  (let [{:keys [frameId frameNumber status imageDataUrl actionsOpen]} frame
        busy? (or (= status "queued") (= status "processing"))]
    [:details.frame-danger-zone
     {:open (true? actionsOpen)
      :on-click #(.stopPropagation %)
      :on-toggle #(rf/dispatch [:set-frame-actions-open frameId (.-open (.-target %))])}
     [:summary {:aria-label "Edit frame"} "✂"]
     [:div.frame-actions
      [frame-action-button frame]
      [clear-image-button frame]
      [:button.btn.btn-danger.btn-small
       {:type "button"
        :aria-label "Delete frame"
        :on-click (fn [e]
                    (.stopPropagation e)
                    (-> (.fire Swal
                              (clj->js {:title (str "Delete Frame " frameNumber "?")
                                        :text "This cannot be undone."
                                        :icon "warning"
                                        :showCancelButton true
                                        :confirmButtonText "Delete"
                                        :cancelButtonText "Cancel"
                                        :confirmButtonColor "#8b1e3f"}))
                        (.then (fn [result]
                                 (when (true? (.-isConfirmed result))
                                   (rf/dispatch [:delete-frame frameId]))))))}
       [:span.btn-icon "!"]
       [:span.btn-hint "Delete"]]]]))

(defn frame-view
  ([frame frame-input]
   [frame-view frame frame-input {:clickable? true}])
   ([frame frame-input {:keys [clickable? active? actions-open?]
                        :or {clickable? true active? false actions-open? false}}]
   (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))
         busy? (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         frame* (assoc frame :actionsOpen actions-open?)
         attrs (cond-> {:data-frame-id (:frameId frame)
                        :class (str "frame"
                                    (when clickable? " frame-clickable")
                                    (when active? " frame-active"))}
                 clickable? (assoc :role "button"
                                   :tab-index 0
                                   :on-mouse-enter #(rf/dispatch [:set-active-frame (:frameId frame)])
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
       [frame-editor frame* frame-input]
       [frame-actions-menu frame*]]])))
