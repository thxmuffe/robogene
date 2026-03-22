(ns webapp.shared.sequence-action-renderer
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.components.sequence-actions :as sequence-actions]
            [webapp.shared.model :as model]))

(defn render-sequence-actions [entity]
  (let [entity-id (:id entity)
        role (some-> (:vanityRole entity) str str/lower-case)
        owner-type (if (= role "character") "character" "saga")
        cancel-ui-token @(rf/subscribe [:cancel-ui-token])
        entities @(rf/subscribe [:entities])
        link-entities-state @(rf/subscribe [:link-entities-state])
        excluded-ids (set (cons entity-id (model/entity-descendant-ids entities entity-id)))
        linkable-entities (->> (vals entities)
                               (filter (fn [candidate]
                                         (contains? #{"saga" "roster" "chapter" "character"}
                                                    (some-> candidate :vanityRole str str/lower-case))))
                               (remove (fn [candidate]
                                         (contains? excluded-ids (:id candidate))))
                               vec)
        frames (when (not= role "saga")
                 @(rf/subscribe [:frames-for-owner owner-type entity-id]))]
    [sequence-actions/sequence-actions
     entity
     {:cancel-ui-token cancel-ui-token
      :link-entities-state link-entities-state
      :linkable-entities linkable-entities
      :entities-map entities
      :frames frames
      :on-open-page #(let [hash (model/route-hash-for-entity entity)
                           href (str (.-origin js/location)
                                     (.-pathname js/location)
                                     hash)]
                       (.open js/window href "_blank" "noopener,noreferrer"))
      :on-set-role #(rf/dispatch [:change-entity-role entity-id %])
      :on-delete #(rf/dispatch [(case role
                                  "saga" :delete-saga
                                  "character" :delete-character
                                  :delete-chapter)
                                entity-id])
      :on-open-link-entities #(rf/dispatch [:open-link-entities-dialog {:parent-id %}])
      :on-select-link-entity #(rf/dispatch [:select-link-entity %])
      :on-link-entities-search #(rf/dispatch [:link-entities-search-changed %])
      :on-link-entities-sort #(rf/dispatch [:link-entities-sort-changed %])
      :on-link-entities-role-filters #(rf/dispatch [:link-entities-role-filters-changed %])
      :on-close-link-entities-dialog #(rf/dispatch [:close-link-entities-dialog])
      :on-upload-images #(rf/dispatch [:upload-chapter-images %1 %2])
      :on-delete-empty-frames #(rf/dispatch [:delete-empty-frames %1 %2])}]))
