(ns webapp.views
  (:require [re-frame.core :as rf]
            [webapp.views.main-gallery :as gallery-page]
            [webapp.views.frame-page :as frame-page]))

(defn main-view []
  (let [episodes @(rf/subscribe [:episodes])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-episode-description @(rf/subscribe [:new-episode-description])
        new-episode-panel-open? @(rf/subscribe [:new-episode-panel-open?])
        show-episode-celebration? @(rf/subscribe [:show-episode-celebration?])
        wait-dialog-visible? @(rf/subscribe [:wait-dialog-visible?])
        route @(rf/subscribe [:route])]
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]]
     (if (= :frame (:view route))
       [frame-page/frame-page route episodes frame-inputs open-frame-actions]
       [gallery-page/main-gallery-page episodes
        frame-inputs
        open-frame-actions
        active-frame-id
        new-episode-description
        new-episode-panel-open?
        show-episode-celebration?])
     (when wait-dialog-visible?
       [:div.wait-dialog-backdrop
        {:role "status"
         :aria-live "polite"
         :aria-label "Waiting for server response"}
        [:div.wait-dialog
         [:div.spinner]
         [:h2 "Still working..."]
         [:p "The backend is processing your request."]]])]))
