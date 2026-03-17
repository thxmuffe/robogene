(ns webapp.pages.search-page
  "Search page: filter any entity and render matching sequences/items."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.components.item :as item]
            ["@mantine/core" :refer [Box Stack Textarea]]))

(defn- matches? [q {:keys [title description vanityRole payload]}]
  (let [q (str/lower-case (str q))
        haystack (str/lower-case
                  (str title " " description " "
                       vanityRole " "
                       (when-let [txt (:text payload)] txt)))]
    (str/includes? haystack q)))

(defn search-page []
  (let [query* (r/atom "")
        entities @(rf/subscribe [:entities])
        entity-by-id entities
        all-entities (vals entities)
        filtered (if (str/blank? @query*)
                   []
                   (->> all-entities
                        (filter #(matches? @query* %))
                        (take 200)))]
    [:> Stack {:gap "md" :className "search-page"}
     [:> Textarea {:label "Search"
                   :placeholder "Search by title, description, or type…"
                   :autosize true
                   :minRows 2
                   :value @query*
                   :onChange #(reset! query* (.. % -target -value))}]
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
