(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [re-frame.core :as rf]
            [webapp.components.sequence :as sequence]))

(defn fetch-entity [id]
  @(rf/subscribe [:entity id]))

(defn gallery-page [{:keys [entity-id]}]
  (let [entity @(rf/subscribe [:entity entity-id])
        children-ids (:children entity)
        children (map fetch-entity children-ids)]
    (cond
      (nil? entity)
      [:div.gallery-page [:p "Loading gallery..."]]

      (empty? children-ids)
      [:div.gallery-page [:p "No sequences found."]]

      :else
      [:div.gallery-page
       (for [child children]
         ^{:key (:id child)}
         [sequence/sequence child
          {:children-fetcher fetch-entity
           :add-child-label "Add Item"
           :add-child-fn #(rf/dispatch [:add-frame (:id child)])
           :on-save-title #(rf/dispatch [:entity-update (:id child) {:title %}])
           :on-save-description #(rf/dispatch [:entity-update (:id child) {:description %}])}])])))

(defn gallery-page-view []
  (let [route @(rf/subscribe [:route])
        entity-id (or (:entity-id route)
                      (:saga-id route)
                      (:roster-id route))]
    (if entity-id
      [gallery-page {:entity-id entity-id}]
      [:div.gallery-page [:p "No gallery target in route."]])))
