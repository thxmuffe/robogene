(ns webapp.pages.search-page
  "Search page: filter any entity and render matching sequences/items."
  (:require [re-frame.core :as rf]
            [webapp.components.sequence :as sequence]
            [webapp.components.item :as item]
            [webapp.shared.search :as search]
            ["@mantine/core" :refer [Box Stack TextInput Text]]))

(def view-id :search-page)

(defn search-page []
  (let [query @(rf/subscribe [:collection-search view-id])
        entities @(rf/subscribe [:entities])
        entity-by-id entities
        filtered (search/search-entities entities query)]
    (js/console.log "search-page entities count" (count entities) "keys" (clj->js (keys entities)) "query" query "results" (count filtered))
    [:> Stack {:gap "md" :className "search-page"}
     [:> Text {:component "h2"
               :fw 600
               :size "lg"}
      "Search"]
     [:> TextInput {:label "Query"
                    :placeholder "Exact match on title first, then description"
                    :variant "filled"
                    :size "sm"
                    :className "collection-search-input"
                    :value query
                    :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
     (when (and (not (empty? (str query)))
                (empty? filtered))
       [:> Text {:color "dimmed"} "No matches"])
     [:> Box {:className "gallery"}
      (for [entity filtered]
        ^{:key (:id entity)}
        [:div.gallery-motion-item
         (if (seq (:children entity))
           [sequence/sequence
            entity
            {:children-fetcher (fn [child-id] (get entity-by-id child-id))}]
           [item/item entity {:clickable? true}])])]]))

(defn search-page-view []
  [search-page])
