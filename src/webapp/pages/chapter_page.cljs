(ns webapp.pages.chapter-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.components.chapter :as chapter-component]
            [webapp.shared.ui.back-button :as back-button]
            ["@mantine/core" :refer [Box Group]]))

(defn chapter-page [route]
  (let [chapter-id (:chapter route)
        saga @(rf/subscribe [:saga])
        active-frame-id @(rf/subscribe [:active-frame-id])
        chapter (some (fn [row] (when (= (:chapterId row) chapter-id) row)) saga)
        chapter-name (or (some-> chapter :name str/trim not-empty)
                         (some-> chapter :description str/trim not-empty)
                         "Chapter")
        chapter-description (some-> chapter :description str/trim not-empty)]
    [:section {:className "frame-page-section"}
     (if chapter
       [:> Box {:className "detail-page saga-page"}
        [:> Group {:className "detail-controls"
                   :gap "xs"
                   :wrap "wrap"}
         [back-button/back-button
          {:label "Back to gallery"
           :on-click #(rf/dispatch [:navigate-saga-page])}]]
        [:h2 chapter-name]
        (when (and chapter-description
                   (not= chapter-description chapter-name))
          [:p.chapter-description chapter-description])
        [chapter-component/chapter chapter-id "saga" active-frame-id]]
       [:> Box {:className "detail-missing"}
        [:p "Chapter not found."]
        [back-button/back-button
         {:label "Back to gallery"
          :on-click #(rf/dispatch [:navigate-saga-page])}]])]))
