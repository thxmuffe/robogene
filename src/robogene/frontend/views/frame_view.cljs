(ns robogene.frontend.views.frame-view
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            ["sweetalert2" :as Swal]))

(defn frame-image [{:keys [imageDataUrl frameNumber]}]
  [:img {:src (or imageDataUrl "") :alt (str "Frame " frameNumber)}])

(defn frame-editor [{:keys [frameId status error]} frame-input]
  (let [busy? (or (= status "queued") (= status "processing"))]
    [:div.frame-editor
     [:textarea.direction-input
      {:value (or frame-input "")
       :placeholder "Describe this frame..."
       :disabled busy?
       :on-click #(.stopPropagation %)
       :on-key-down #(.stopPropagation %)
       :on-change #(rf/dispatch [:frame-direction-changed frameId (.. % -target -value)])}]
     (when (and (seq (or error "")) (not busy?))
       [:div.error-line (str "Last error: " error)])]))

(defn frame-placeholder [{:keys [status]}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Click to generate")
        cta? (not (or (= status "queued") (= status "processing")))]
    [:div.placeholder-img
     (when (or (= status "queued") (= status "processing"))
       [:div.spinner])
     [:div.placeholder-text label]
     (when cta?
       [:div.placeholder-clue
        [:span.placeholder-arrow "↘"]
        [:span "Open Frame actions"]])]))

(defn frame-action-button [{:keys [frameId status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))
        button-label (if busy?
                       (if (= status "processing") "Generating..." "Queued...")
                       (if has-image? "Regenerate Frame" "Generate Frame"))]
    [:button.btn.btn-primary.btn-small
     {:type "button"
      :disabled busy?
      :on-mouse-down #(.stopPropagation %)
      :on-click (fn [e]
                  (.stopPropagation e)
                  (rf/dispatch [:generate-frame frameId]))}
     button-label]))

(defn clear-image-button [{:keys [frameId frameNumber status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))]
    (when has-image?
      [:button.btn.btn-secondary.btn-small
       {:type "button"
        :disabled busy?
        :title (if busy?
                 "Cannot remove image while queued/processing"
                 "Remove image but keep frame")
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
       "Remove Image"])))

(defn frame-actions-menu [frame]
  (let [{:keys [frameId frameNumber status imageDataUrl]} frame
        busy? (or (= status "queued") (= status "processing"))]
    [:details.frame-danger-zone
     {:on-click #(.stopPropagation %)}
     [:summary "Frame actions"]
     [:div.frame-actions
      (when (not (str/blank? (or imageDataUrl "")))
        [frame-action-button frame])
      [clear-image-button frame]
      [:button.btn.btn-danger.btn-small
       {:type "button"
        :disabled busy?
        :title (if busy?
                 "Cannot delete while queued/processing"
                 "Delete this frame")
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
       "Delete Frame"]]]))

(defn frame-view
  ([frame frame-input]
   [frame-view frame frame-input {:clickable? true}])
  ([frame frame-input {:keys [clickable?]
                       :or {clickable? true}}]
   (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))
         attrs (cond-> {:data-frame-id (:frameId frame)
                        :class (if clickable? "frame frame-clickable" "frame")}
                 clickable? (assoc :role "button"
                                   :tab-index 0
                                   :on-click #(rf/dispatch [:navigate-frame (:episodeId frame) (:frameNumber frame)])
                                   :on-key-down (fn [e]
                                                  (when (or (= "Enter" (.-key e))
                                                            (= " " (.-key e)))
                                                    (.preventDefault e)
                                                    (rf/dispatch [:navigate-frame (:episodeId frame) (:frameNumber frame)])))))]
     [:article attrs
      [:div.media-shell
       (if has-image?
         [frame-image frame]
         [:div
          [frame-placeholder frame]
          [:div.placeholder-action
           [frame-action-button frame]]])]
      [:div.meta
       (when (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         [:span.badge.queue "In Queue"])
       [frame-editor frame frame-input]
       [frame-actions-menu frame]]])))
