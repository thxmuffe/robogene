(ns webapp.app-shell
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.theme :as theme]
            [webapp.pages.main-gallery :as gallery-page]
            [webapp.pages.characters-page :as characters-page]
            [webapp.pages.frame-page :as frame-page]
            [webapp.components.traffic-indicator :as traffic-indicator]
            ["@mantine/core" :refer [MantineProvider Container Stack Box]]))

(def app-name
  "robogene")

(defn saga-name [_]
  "Robot Emperor")

(defn frame-page-title [route saga]
  (let [chapter (some (fn [row] (when (= (:chapterId row) (:chapter route)) row)) saga)
        chapter-name (or (some-> chapter :description str/trim not-empty)
                         "Chapter")]
    (str "Frame Page · " chapter-name " · " (saga-name saga))))

(defn main-page-title [route]
  (if (= :characters (:view route))
    (str app-name " · Saga_characters_page")
    (str app-name " · Saga_page")))

(defn main-view []
  (let [saga @(rf/subscribe [:saga])
        characters @(rf/subscribe [:characters])
        gallery-items @(rf/subscribe [:gallery-items])
        status @(rf/subscribe [:status])
        frame-inputs @(rf/subscribe [:frame-inputs])
        open-frame-actions @(rf/subscribe [:open-frame-actions])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-chapter-description @(rf/subscribe [:new-chapter-description])
        new-chapter-panel-open? @(rf/subscribe [:new-chapter-panel-open?])
        new-character-description @(rf/subscribe [:new-character-description])
        new-character-panel-open? @(rf/subscribe [:new-character-panel-open?])
        show-chapter-celebration? @(rf/subscribe [:show-chapter-celebration?])
        wait-lights-visible? @(rf/subscribe [:wait-lights-visible?])
        pending-api-requests @(rf/subscribe [:pending-api-requests])
        wait-lights-events @(rf/subscribe [:wait-lights-events])
        route @(rf/subscribe [:route])
        saga-name* (saga-name saga)]
    (set! (.-title js/document)
          (if (= :frame (:view route))
            (frame-page-title route saga)
            (main-page-title route)))
    [:> MantineProvider {:theme theme/app-theme}
     [:> Container {:fluid true}
      [:main.app
       [:> Stack {:gap "md"}
        [:> Box {:component "header" :className "hero"}
         [:h1 app-name]]
        (if (= :frame (:view route))
          [frame-page/frame-page route frame-inputs open-frame-actions saga-name*]
          (if (= :characters (:view route))
            [characters-page/characters-page saga-name*
             characters
             frame-inputs
             open-frame-actions
             active-frame-id
             new-character-description
             new-character-panel-open?]
            [gallery-page/saga-page saga
             frame-inputs
             open-frame-actions
             active-frame-id
             new-chapter-description
             new-chapter-panel-open?
             show-chapter-celebration?]))
        [traffic-indicator/traffic-indicator
         {:pending-api-requests pending-api-requests
          :wait-lights-visible? wait-lights-visible?
          :status status
          :frames gallery-items
          :events wait-lights-events}]]]]]))
