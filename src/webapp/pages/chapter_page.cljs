(ns webapp.pages.chapter-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.components.gallery :as gallery]
            [webapp.shared.ui.back-button :as back-button]
            ["@mantine/core" :refer [Box Group]]))

(defn chapter-page [route]
  (let [chapter-id (:chapter route)
        chapter @(rf/subscribe [:chapter-by-id chapter-id])
        active-frame-id @(rf/subscribe [:active-frame-id])
        chapter-name (or (some-> chapter :name str/trim not-empty)
                         (some-> chapter :description str/trim not-empty)
                         "Chapter")
        chapter-description (some-> chapter :description str/trim not-empty)
        saga-id (or (:saga-id route) (:sagaId chapter))]
    [:section {:className "frame-page-section"}
     (if chapter
       [:> Box {:className "detail-page chapter-page"}
        [:> Group {:className "detail-controls"
                   :gap "xs"
                   :wrap "wrap"}
         [back-button/back-button
          {:label "Back to saga"
           :on-click #(rf/dispatch [:navigate-saga-page saga-id])}]]
        [:h2.chapter-name chapter-name]
        (when (and chapter-description
                   (not= chapter-description chapter-name))
          [:p.chapter-description chapter-description])
        [gallery/frame-gallery chapter-id "saga" active-frame-id]]
       [:> Box {:className "detail-missing"}
        [:p "Chapter not found."]
        [back-button/back-button
         {:label "Back to index"
          :on-click #(rf/dispatch [:navigate-index])}]])]))
