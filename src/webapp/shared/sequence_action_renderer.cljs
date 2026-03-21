(ns webapp.shared.sequence-action-renderer
  (:require [re-frame.core :as rf]
            [webapp.components.sequence-actions :as sequence-actions]))

(defn render-sequence-actions [entity]
  (let [entity-id (:id entity)
        role (some-> (:vanityRole entity) str str/lower-case)
        owner-type (if (= role "character") "character" "saga")
        cancel-ui-token @(rf/subscribe [:cancel-ui-token])
        roster-link-state @(rf/subscribe [:roster-link-state])
        rosters @(rf/subscribe [:rosters])
        frames (when (not= role "saga")
                 @(rf/subscribe [:frames-for-owner owner-type entity-id]))]
    [sequence-actions/sequence-actions
     entity
     {:cancel-ui-token cancel-ui-token
      :roster-link-state roster-link-state
      :rosters rosters
      :frames frames
      :on-delete #(rf/dispatch [(case role
                                  "saga" :delete-saga
                                  "character" :delete-character
                                  :delete-chapter)
                                entity-id])
      :on-open-roster-link #(rf/dispatch [:add-linked-chapter-roster %])
      :on-select-roster #(rf/dispatch [:select-roster-link %])
      :on-roster-search #(rf/dispatch [:roster-link-search-changed %])
      :on-close-roster-dialog #(rf/dispatch [:close-roster-link-dialog])
      :on-create-roster #(rf/dispatch [:create-roster-link])
      :on-upload-images #(rf/dispatch [:upload-chapter-images %1 %2])
      :on-delete-empty-frames #(rf/dispatch [:delete-empty-frames %1 %2])}]))
