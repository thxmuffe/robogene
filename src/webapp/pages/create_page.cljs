(ns webapp.pages.create-page
  (:require [webapp.components.sequence :as sequence]
            [webapp.components.upload-images :as upload-images]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            ["@mantine/core" :refer [Stack Text]]))

(defn create-page-view []
  (let [entity {:id "create-page"
                :vanityRole "chapter"
                :title ""
                :description ""
                :children []
                :payload {}}]
    [:> Stack {:gap "md" :className "search-page"}
     [:div.collection-search-input
      [:> Text {:size "sm" :mt "xs"}
       "Pro tip: open a chat and save page, saving the images on your hard drive. Then use \"upload images\" sequence action"]]
     [upload-images/upload-images
      {:open true
       :multiple? true
       :title "Upload images"
       :on-close (fn [] nil)
       :on-submit (fn [_] nil)
       :on-submit-many (fn [_] nil)}]
     [sequence/sequence
      entity
      {:actions-renderer sequence-action-renderer/render-sequence-actions
       :add-child-label "Add"
       :add-child-fn (fn [] nil)}]]))
