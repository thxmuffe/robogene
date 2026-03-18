(ns webapp.pages.search-page
  (:require [re-frame.core :as rf]
            [webapp.components.sequence :as sequence]
            [webapp.components.item :as item]
            [webapp.shared.search :as search]
            [clojure.string :as str]
            ["@mantine/core" :refer [Box Stack TextInput Text]]))

(def view-id :search-page)

(defn search-page []
  (let [query @(rf/subscribe [:collection-search view-id])
        entities @(rf/subscribe [:entities])
        entity-by-id (fn [id]
                       (let [k (cond
                                 (keyword? id) id
                                 (string? id) (keyword id)
                                 :else id)]
                         (or (get entities id)
                             (get entities k)
                             (when (string? id)
                               (get entities (keyword (str/trim id)))))))
        filtered (search/search-entities entities query)
        result-ids (map :id filtered)]
    (js/console.log "search-page entities count" (count entities)
                    "keys" (clj->js (keys entities))
                    "query" query
                    "results" (count filtered)
                    "ids" (clj->js result-ids))
    [:> Stack {:gap "md" :className "search-page"}
     [:> Text {:component "h2" :fw 600 :size "lg"} "Search"]
     [:> TextInput {:label "Query"
                    :placeholder "Exact or partial match on title first, then description"
                    :variant "filled"
                    :size "sm"
                    :className "collection-search-input"
                    :value query
                    :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
     (when (and (not (empty? (str query))) (empty? filtered))
       [:> Text {:color "dimmed"} "No matches"])
     [:> Box {:className "gallery"}
      (for [entity filtered]
        ^{:key (:id entity)}
        [:div.gallery-motion-item
         (if (seq (:children entity))
           [sequence/sequence entity {:children-fetcher entity-by-id}]
           [item/item entity {:clickable? true}])])]
     (when (seq filtered)
       [:> Text {:color "dimmed" :size "sm"}
        (str "Results: " (clojure.string/join ", " result-ids))])]))

(defn search-page-view []
  [search-page])
