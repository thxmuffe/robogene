(ns robogene.frontend.views
  (:require [re-frame.core :as rf]
            [robogene.frontend.views.main-gallery :as gallery-page]
            [robogene.frontend.views.frame-page :as frame-page]))

(defn main-view []
  (let [episodes @(rf/subscribe [:episodes])
        frame-inputs @(rf/subscribe [:frame-inputs])
        new-episode-description @(rf/subscribe [:new-episode-description])
        route @(rf/subscribe [:route])]
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]]
     (if (= :frame (:view route))
       [frame-page/frame-page route episodes frame-inputs]
       [gallery-page/main-gallery-page episodes frame-inputs new-episode-description])]))
