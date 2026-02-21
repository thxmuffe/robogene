(ns robogene.frontend.views.frame-view
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(defn frame-label [frame]
  (str "Frame " (:frameNumber frame)))

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
       [:div.placeholder-clue "Use the button ->"])]))

(defn frame-action-button [{:keys [frameId status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))
        button-label (if busy?
                       (if (= status "processing") "Generating..." "Queued...")
                       (if has-image? "Regenerate" "Generate"))]
    [:button.btn.btn-primary.overlay-generate-btn
     {:type "button"
      :disabled busy?
      :on-mouse-down #(.stopPropagation %)
      :on-click (fn [e]
                  (.stopPropagation e)
                  (rf/dispatch [:generate-frame frameId]))}
     button-label]))

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
         [frame-placeholder frame])
       [frame-action-button frame]]
     [:div.meta
       [:strong
        (frame-label frame)
        (when (or (= "queued" (:status frame)) (= "processing" (:status frame)))
          [:span.badge.queue "In Queue"])]
       [frame-editor frame frame-input]]])))
