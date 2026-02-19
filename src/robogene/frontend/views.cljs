(ns robogene.frontend.views
  (:require [re-frame.core :as rf]))

(defn history-card [{:keys [sceneNumber imageDataUrl beatText reference]}]
  [:article.card {:data-scene sceneNumber}
   [:img {:src (or imageDataUrl "") :alt (str "Scene " sceneNumber)}]
   [:div.meta
    [:strong
     (str "Scene " sceneNumber)
     (when reference [:span.badge "Reference"])]
    [:div (or beatText "")]]])

(defn pending-card [{:keys [jobId sceneNumber beatText status]}]
  (let [label (case status
                "processing" "Processing"
                "completed" "Finalizing"
                "failed" "Failed"
                "Queued")]
    [:article.card.pending {:data-job jobId}
     [:div.placeholder-img
      [:div.spinner]
      [:div.placeholder-text (str label " scene " sceneNumber "...")]]
     [:div.meta
      [:strong
       (str "Scene " sceneNumber)
       [:span.badge.queue "In Queue"]]
      [:div (or beatText "")]]]))

(defn gallery-item [{:keys [kind payload]}]
  (case kind
    :history [history-card payload]
    :pending [pending-card payload]
    [:div]))

(defn main-view []
  (let [status @(rf/subscribe [:status])
        direction @(rf/subscribe [:direction-input])
        gallery @(rf/subscribe [:gallery-items])
        submitting? @(rf/subscribe [:submitting?])]
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]
      [:p "Episode timeline with shared queue and story memory."]
      [:label.dir-label {:for "directionInput"} "Scene Direction (editable)"]
      [:textarea.direction-input
       {:id "directionInput"
        :placeholder "Loading default scene direction..."
        :value direction
        :on-change #(rf/dispatch [:direction-changed (.. % -target -value)])}]
      [:button.btn.btn-primary
       {:id "nextBtn"
        :disabled submitting?
        :on-click #(rf/dispatch [:generate-next])}
       "Generate Next"]
      [:div.status status]]
     [:section
      [:h2 "Gallery (Most Recent First)"]
      [:div.gallery
       (for [item gallery]
         ^{:key (str (:kind item) "-" (:scene-number item))}
         [gallery-item item])]]]))
