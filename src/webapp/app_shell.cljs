(ns webapp.app-shell
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.theme :as theme]
            [webapp.shared.model :as model]
            [webapp.pages.gallery-page :as gallery-page]
            [webapp.pages.roster-page :as roster-page]
            [webapp.pages.chapter-page :as chapter-page]
            [webapp.pages.frame-page :as frame-page]
            [webapp.components.traffic-indicator :as traffic-indicator]
            ["@mantine/core" :refer [MantineProvider Container Stack Box]]))

(def app-name
  "robogene")

(defn display-saga-name [saga]
  (or (some-> (:name saga) str/trim not-empty)
      "Saga"))

(defn chapter-by-id [chapters chapter-id]
  (some (fn [row]
          (when (= (:chapterId row) chapter-id)
            row))
        chapters))

(defn saga-for-chapter [sagas chapters chapter-id]
  (let [chapter (chapter-by-id chapters chapter-id)]
    (some (fn [saga]
            (when (= (:sagaId saga) (:sagaId chapter))
              saga))
          sagas)))

(defn frame-page-title [route sagas chapters]
  (let [chapter (chapter-by-id chapters (:chapter route))
        saga (saga-for-chapter sagas chapters (:chapter route))
        chapter-name (or (some-> chapter :name str/trim not-empty)
                         (some-> chapter :description str/trim not-empty)
                         "Chapter")]
    (str "Frame Page · " chapter-name " · " (display-saga-name saga))))

(defn chapter-page-title [route sagas chapters]
  (let [chapter (chapter-by-id chapters (:chapter route))
        saga (saga-for-chapter sagas chapters (:chapter route))
        chapter-name (or (some-> chapter :name str/trim not-empty)
                         (some-> chapter :description str/trim not-empty)
                         "Chapter")]
    (str "Chapter Page · " chapter-name " · " (display-saga-name saga))))

(defn display-roster-name [roster]
  (or (some-> (:name roster) str/trim not-empty)
      "Roster"))

(defn main-page-title [route selected-saga selected-roster]
  (let [page-title (case (:view route)
                     :index "Index"
                     :roster (display-roster-name selected-roster)
                     (display-saga-name selected-saga))]
    (str app-name " · " page-title)))

(defn main-view []
  (let [sagas @(rf/subscribe [:sagas])
        chapters @(rf/subscribe [:saga])
        selected-saga @(rf/subscribe [:selected-saga])
        selected-saga-chapters @(rf/subscribe [:chapters-for-selected-saga])
        selected-roster @(rf/subscribe [:selected-roster])
        roster-characters @(rf/subscribe [:characters-for-selected-roster])
        gallery-items @(rf/subscribe [:gallery-items])
        status @(rf/subscribe [:status])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-saga-name @(rf/subscribe [:new-saga-name])
        new-saga-description @(rf/subscribe [:new-saga-description])
        new-saga-panel-open? @(rf/subscribe [:new-saga-panel-open?])
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
        frame-view? (= :frame (:view route))
        collection-view? (not frame-view?)
        saga-name* (display-saga-name selected-saga)]
    (set! (.-title js/document)
          (case (:view route)
            :frame (frame-page-title route sagas chapters)
            :chapter (chapter-page-title route sagas chapters)
            (main-page-title route selected-saga selected-roster)))
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
                                 (when collection-view? " hero-collection"))}
         [:h1
          [:a {:href (model/index-hash)
               :className "hero-home-link"}
           app-name]]]
        (case (:view route)
          :frame
          [frame-page/frame-page route saga-name*]

          :chapter
          [chapter-page/chapter-page route]

          :roster
          [roster-page/roster-page selected-roster
           saga-name*
           roster-characters
           active-frame-id
           new-character-name
           new-character-description
           new-character-panel-open?]

          :saga
          [gallery-page/saga-page selected-saga
           selected-saga-chapters
           active-frame-id
           new-chapter-name
           new-chapter-description
           new-chapter-panel-open?
           show-chapter-celebration?]

          [gallery-page/index-page sagas
           chapters
           gallery-items
           new-saga-name
           new-saga-description
           new-saga-panel-open?])
        [traffic-indicator/traffic-indicator
         {:pending-api-requests pending-api-requests
          :wait-lights-visible? wait-lights-visible?
          :status status
          :frames gallery-items
          :events wait-lights-events}]]]]]))
