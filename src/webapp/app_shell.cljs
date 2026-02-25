(ns webapp.app-shell
  (:require [re-frame.core :as rf]
            [webapp.pages.main-gallery :as gallery-page]
            [webapp.pages.frame-page :as frame-page]
            [webapp.components.traffic-indicator :as traffic-indicator]))

(defn frame-page-title [route chapters]
  (let [chapter (some (fn [row] (when (= (:chapterId row) (:chapter route)) row)) chapters)
        chapter-name (or (:description chapter)
                         "Chapter")]
    (str "Frame Page · " chapter-name " · RoboGene")))

(defn main-view []
  (let [chapters @(rf/subscribe [:chapters])
        gallery-items @(rf/subscribe [:gallery-items])
        status @(rf/subscribe [:status])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-chapter-description @(rf/subscribe [:new-chapter-description])
        new-chapter-panel-open? @(rf/subscribe [:new-chapter-panel-open?])
        show-chapter-celebration? @(rf/subscribe [:show-chapter-celebration?])
        wait-lights-visible? @(rf/subscribe [:wait-lights-visible?])
        pending-api-requests @(rf/subscribe [:pending-api-requests])
        wait-lights-events @(rf/subscribe [:wait-lights-events])
        route @(rf/subscribe [:route])]
    (set! (.-title js/document)
          (if (= :frame (:view route))
            (frame-page-title route chapters)
            "RoboGene"))
    [:main.app
     [:header.hero
      [:h1 "RoboGene"]]
     (if (= :frame (:view route))
       [frame-page/frame-page route frame-inputs open-frame-actions]
       [gallery-page/main-gallery-page chapters
        frame-inputs
        open-frame-actions
        active-frame-id
        new-chapter-description
        new-chapter-panel-open?
        show-chapter-celebration?])
     [traffic-indicator/traffic-indicator
      {:pending-api-requests pending-api-requests
       :wait-lights-visible? wait-lights-visible?
       :status status
       :frames gallery-items
       :events wait-lights-events}]]))
