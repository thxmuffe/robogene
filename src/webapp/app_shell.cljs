(ns webapp.app-shell
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.theme :as theme]
            [webapp.shared.model :as model]
            [webapp.pages.search-page :as search-page]
            [webapp.pages.sequence-page :as sequence-page]
            [webapp.pages.item-page :as item-page]
            [webapp.pages.gallery-page :as gallery-page]
            [webapp.components.traffic-indicator :as traffic-indicator]
            ["@mantine/core" :refer [MantineProvider Container Stack Box]]))

(def app-name "robogene")

(defn route-entity-id [route]
  (or (:entity-id route)
      (:frame-id route)
      (:chapter route)
      (:roster-id route)
      (:saga-id route)))

(defn page-title [route entities]
  (let [entity-id (route-entity-id route)
        entity (when entity-id (get entities entity-id))
        entity-title (some-> (:title entity) str/trim not-empty)]
    (str app-name
         (when (not (str/blank? (or entity-title "")))
           (str " · " entity-title))
         (when (and (str/blank? (or entity-title "")) (:view route))
           (str " · " (name (:view route)))))))

(defn main-view []
  (let [entities @(rf/subscribe [:entities])
        gallery-items @(rf/subscribe [:gallery-items])
        status @(rf/subscribe [:status])
        wait-lights-visible? @(rf/subscribe [:wait-lights-visible?])
        pending-api-requests @(rf/subscribe [:pending-api-requests])
        wait-lights-events @(rf/subscribe [:wait-lights-events])
        route @(rf/subscribe [:route])
        frame-view? (= :frame (:view route))]
    (set! (.-title js/document) (page-title route entities))
    [:> MantineProvider {:theme theme/app-theme}
     [:> Container {:fluid true
                    :px (when frame-view? 0)
                    :className (when frame-view? "app-shell-frame")}
      [:main {:className (str "app" (when frame-view? " app-frame"))
              :style (when frame-view?
                       {:padding-left 0
                        :padding-right 0})}
       [:> Stack {:gap "md"
                  :style (when frame-view? {:width "100%"})
                  :className (when frame-view? "app-stack-frame")}
        [:> Box {:component "header"
                 :className (str "hero"
                                 (when frame-view? " hero-frame")
                                 (when (not frame-view?) " hero-collection"))}
         [:h1
          [:a {:href (model/index-hash)
               :className "hero-home-link"}
           app-name]]]
        (case (:view route)
          :frame
          [item-page/item-page-view]

          :chapter
          [sequence-page/sequence-page-view]

          :roster
          [gallery-page/gallery-page-view]

          :saga
          [gallery-page/gallery-page-view]

          [search-page/search-page-view])
        [traffic-indicator/traffic-indicator
         {:pending-api-requests pending-api-requests
          :wait-lights-visible? wait-lights-visible?
          :status status
          :frames gallery-items
          :events wait-lights-events}]]]]]))
