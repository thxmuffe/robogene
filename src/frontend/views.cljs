(ns frontend.views
  (:require [re-frame.core :as rf]
            [frontend.views.main-gallery :as gallery-page]
            [frontend.views.frame-page :as frame-page]))

(defn main-view []
  (let [episodes @(rf/subscribe [:episodes])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-episode-description @(rf/subscribe [:new-episode-description])
        new-episode-panel-open? @(rf/subscribe [:new-episode-panel-open?])
        show-episode-celebration? @(rf/subscribe [:show-episode-celebration?])
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
        show-episode-celebration?])]))
