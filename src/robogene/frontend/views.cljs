(ns robogene.frontend.views
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(defn frame-label [frame]
  (str "Frame " (:sceneNumber frame)))

(defn card-image [{:keys [imageDataUrl sceneNumber]}]
  [:img {:src (or imageDataUrl "") :alt (str "Scene " sceneNumber)}])

(defn frame-editor [{:keys [frameId status error]} frame-input]
  (let [busy? (or (= status "queued") (= status "processing"))
        button-label (if busy?
                       (if (= status "processing") "Generating..." "Queued...")
                       "Generate")]
    [:div.frame-editor
     [:textarea.direction-input
      {:value frame-input
       :placeholder "Describe this frame..."
       :disabled busy?
       :on-change #(rf/dispatch [:frame-direction-changed frameId (.. % -target -value)])}]
     (when (and (seq (or error "")) (not busy?))
       [:div.error-line (str "Last error: " error)])
     [:button.btn.btn-primary
      {:disabled busy?
       :on-click #(rf/dispatch [:generate-frame frameId])}
      button-label]]))

(defn frame-placeholder [{:keys [status sceneNumber]}]
  (let [label (case status
                "processing" (str "Generating frame " sceneNumber "...")
                "queued" (str "Queued frame " sceneNumber "...")
                "failed" (str "Frame " sceneNumber " failed")
                (str "Frame " sceneNumber " draft"))]
    [:div.placeholder-img
     (when (or (= status "queued") (= status "processing"))
       [:div.spinner])
     [:div.placeholder-text label]]))

(defn frame-card [frame frame-input]
  (let [has-image? (not (str/blank? (or (:imageDataUrl frame) "")))]
    [:article.card {:data-frame-id (:frameId frame)}
     (if has-image?
       [card-image frame]
       [frame-placeholder frame])
     [:div.meta
      [:strong
       (frame-label frame)
       (when (:reference frame) [:span.badge "Reference"])
       (when (or (= "queued" (:status frame)) (= "processing" (:status frame)))
         [:span.badge.queue "In Queue"])]
      [:div (or (:beatText frame) "")]
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
