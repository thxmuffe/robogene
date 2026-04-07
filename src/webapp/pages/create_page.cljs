(ns webapp.pages.create-page
  (:require [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            ["@mantine/core" :refer [Stack TextInput]]))

(defn create-page-view []
  (r/with-let [source-url* (r/atom "")]
    (let [entity {:id "create-page-sequence"
                  :title ""
                  :description ""
                  :children []
                  :vanityRole "chapter"
                  :payload {}}]
      [:> Stack {:gap "md" :className "search-page"}
       [:div.collection-search-input
        [:> TextInput {:value @source-url*
                       :placeholder ""
                       :onChange #(reset! source-url* (.. % -target -value))}]]
       [sequence/sequence
        entity
        {:actions-renderer sequence-action-renderer/render-sequence-actions
         :add-child-label "Add"
         :add-child-fn (fn [] nil)}]])))
