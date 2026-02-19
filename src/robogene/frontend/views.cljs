(ns robogene.frontend.views
  (:require [re-frame.core :as rf]
            [robogene.frontend.views.main-gallery :as gallery-page]
            [robogene.frontend.views.frame-page :as frame-page]))

(defn main-view []
  (let [status @(rf/subscribe [:status])
        gallery @(rf/subscribe [:gallery-items])
        frame-inputs @(rf/subscribe [:frame-inputs])
        route @(rf/subscribe [:route])]
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]]
     (if (= :frame (:view route))
       [frame-page/frame-page route gallery frame-inputs]
       [gallery-page/main-gallery-page gallery frame-inputs])]))
