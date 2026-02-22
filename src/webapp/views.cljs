(ns webapp.views
  (:require [re-frame.core :as rf]
            [webapp.views.main-gallery :as gallery-page]
            [webapp.views.frame-page :as frame-page]
            [webapp.views.traffic-indicator :as traffic-indicator]))

(defn frame-page-title [route episodes]
  (let [episode (some (fn [row] (when (= (:episodeId row) (:episode route)) row)) episodes)
        episode-name (or (:description episode)
                         (when (some? (:episodeNumber episode))
                           (str "Episode " (:episodeNumber episode)))
                         "Episode")]
    (str "Frame Page · " episode-name " · RoboGene")))

(defn main-view []
  (let [episodes @(rf/subscribe [:episodes])
        status @(rf/subscribe [:status])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-episode-description @(rf/subscribe [:new-episode-description])
        new-episode-panel-open? @(rf/subscribe [:new-episode-panel-open?])
        show-episode-celebration? @(rf/subscribe [:show-episode-celebration?])
        wait-dialog-visible? @(rf/subscribe [:wait-dialog-visible?])
        pending-api-requests @(rf/subscribe [:pending-api-requests])
        route @(rf/subscribe [:route])]
    (set! (.-title js/document)
          (if (= :frame (:view route))
            (frame-page-title route episodes)
            "RoboGene"))
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
     [traffic-indicator/traffic-indicator
      {:pending-api-requests pending-api-requests
       :wait-dialog-visible? wait-dialog-visible?
       :status status
       :episodes episodes}]]))
