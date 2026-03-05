(ns webapp.app-shell
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.theme :as theme]
            [webapp.shared.model :as model]
            [webapp.pages.gallery-page :as gallery-page]
            [webapp.pages.roster-page :as roster-page]
            [webapp.pages.frame-page :as frame-page]
            [webapp.components.traffic-indicator :as traffic-indicator]
            ["@mantine/core" :refer [MantineProvider Container Stack Box]]))

(def app-name
  "robogene")

(defn saga-name [saga-meta]
  (or (some-> (:name saga-meta) str/trim not-empty)
      "Robot Emperor"))

(defn frame-page-title [route saga saga-meta]
  (let [chapter (some (fn [row] (when (= (:chapterId row) (:chapter route)) row)) saga)
        chapter-name (or (some-> chapter :name str/trim not-empty)
                         (some-> chapter :description str/trim not-empty)
                         "Chapter")]
    (str "Frame Page · " chapter-name " · " (saga-name saga-meta))))

(defn main-page-title [route saga-meta]
  (let [page-title (if (= :roster (:view route))
                     (:page-title roster-page/roster-config)
                     (saga-name saga-meta))]
    (str app-name " · " page-title)))

(defn main-view []
  (let [saga @(rf/subscribe [:saga])
        saga-meta @(rf/subscribe [:saga-meta])
        roster @(rf/subscribe [:roster])
        gallery-items @(rf/subscribe [:gallery-items])
        status @(rf/subscribe [:status])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-chapter-name @(rf/subscribe [:new-chapter-name])
        new-chapter-description @(rf/subscribe [:new-chapter-description])
        new-chapter-panel-open? @(rf/subscribe [:new-chapter-panel-open?])
        new-character-name @(rf/subscribe [:new-character-name])
        new-character-description @(rf/subscribe [:new-character-description])
        new-character-panel-open? @(rf/subscribe [:new-character-panel-open?])
        show-chapter-celebration? @(rf/subscribe [:show-chapter-celebration?])
        wait-lights-visible? @(rf/subscribe [:wait-lights-visible?])
        pending-api-requests @(rf/subscribe [:pending-api-requests])
        wait-lights-events @(rf/subscribe [:wait-lights-events])
        route @(rf/subscribe [:route])
        saga-name* (saga-name saga-meta)]
    (set! (.-title js/document)
          (if (= :frame (:view route))
            (frame-page-title route saga saga-meta)
            (main-page-title route saga-meta)))
    [:> MantineProvider {:theme theme/app-theme}
     [:> Container {:fluid true}
      [:main.app
        [:> Stack {:gap "md"}
        [:> Box {:component "header" :className "hero"}
         [:h1
          [:a {:href (model/saga-hash)
               :className "hero-home-link"}
           app-name]]]
        (case (:view route)
          :frame
          [frame-page/frame-page route frame-inputs open-frame-actions saga-name*]

          :roster
          [roster-page/roster-page saga-name*
           roster
           frame-inputs
           open-frame-actions
           active-frame-id
           new-character-name
           new-character-description
           new-character-panel-open?]

          [gallery-page/saga-page saga
           frame-inputs
           open-frame-actions
           active-frame-id
           new-chapter-name
           new-chapter-description
           new-chapter-panel-open?
           show-chapter-celebration?])
        [traffic-indicator/traffic-indicator
         {:pending-api-requests pending-api-requests
          :wait-lights-visible? wait-lights-visible?
          :status status
          :frames gallery-items
          :events wait-lights-events}]]]]]))
