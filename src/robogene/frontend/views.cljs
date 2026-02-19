(ns robogene.frontend.views
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(defn frame-label [frame]
  (str "Frame " (:sceneNumber frame)))

(defn card-image [{:keys [imageDataUrl sceneNumber]}]
  [:img {:src (or imageDataUrl "") :alt (str "Scene " sceneNumber)}])

(defn generic-scene-label? [{:keys [beatText sceneNumber]}]
  (let [beat (str/trim (or beatText ""))]
    (or (str/blank? beat)
        (= (str/lower-case beat) (str "scene " sceneNumber)))))

(defn frame-editor [{:keys [frameId status error]} frame-input]
  (let [busy? (or (= status "queued") (= status "processing"))]
    [:div.frame-editor
     [:textarea.direction-input
      {:value frame-input
       :placeholder "Describe this frame..."
       :disabled busy?
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
     {:disabled busy?
      :on-click #(rf/dispatch [:generate-frame frameId])}
     button-label]))

(defn frame-card [frame frame-input]
  (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))]
    [:article.card {:data-frame-id (:frameId frame)}
     [:div.media-shell
      (if has-image?
        [card-image frame]
        [frame-placeholder frame])
      [frame-action-button frame]]
     [:div.meta
      [:strong
       (frame-label frame)
       (when (:reference frame) [:span.badge "Reference"])
       (when (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         [:span.badge.queue "In Queue"])]
      (when-not (generic-scene-label? frame)
        [:div (or (:beatText frame) "")])
      (when-not has-image?
        [frame-editor frame frame-input])]]))

(defn main-view []
  (let [status @(rf/subscribe [:status])
        gallery @(rf/subscribe [:gallery-items])
        frame-inputs @(rf/subscribe [:frame-inputs])]
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]
      [:p "Shared episode timeline. Each frame is generated independently by frame ID."]
      [:div.status status]]
     [:section
      [:h2 "Gallery"]
      [:div.gallery
       (for [frame gallery]
         ^{:key (:frameId frame)}
         [frame-card frame (get frame-inputs (:frameId frame) "")])]]]))
