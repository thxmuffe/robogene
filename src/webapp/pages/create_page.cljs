(ns webapp.pages.create-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.dialog.link-entities-dialog :as link-entities-dialog]
            [webapp.shared.model :as model]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            [webapp.shared.controls :as controls]
            ["@mantine/core" :refer [Button Group Stack Text TextInput Title]]))

(defn- save-entity-title! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:title text}]))

(defn- save-entity-description! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:description text}]))

(defn- attachable-entities [entities entity-id]
  (let [excluded-ids (set (cons entity-id (model/entity-descendant-ids entities entity-id)))]
    (->> (vals entities)
         (filter (fn [candidate]
                   (contains? #{"saga" "roster"}
                              (some-> candidate :vanityRole str str/lower-case))))
         (remove (fn [candidate]
                   (contains? excluded-ids (:id candidate))))
         vec)))

(defn create-page-view []
  (r/with-let [dialog-open?* (r/atom false)
               dialog-search* (r/atom "")
               dialog-sort* (r/atom "title-asc")
               source-url* (r/atom nil)]
    (let [entity-id @(rf/subscribe [:create-entity-id])
          entity @(rf/subscribe [:entity entity-id])
          entities @(rf/subscribe [:entities])
          attachables (when entity
                        (attachable-entities entities entity-id))
          payload-source-url (some-> (get-in entity [:payload :sourceUrl]) str not-empty)]
      (when (nil? entity)
        (rf/dispatch [:create/ensure-sequence]))
      (when (and entity (not= payload-source-url @source-url*))
        (reset! source-url* payload-source-url))
      [:> Stack {:gap "md" :className "search-page"}
       [:div.collection-header
        [:div.chapter-header-body
         [:> Title {:order 2} "Create"]
         [:> Text "Create a new chapter or character using existing image source"]]]
       (when entity
         [:div.collection-search-input
          [:> TextInput {:label "Source link"
                         :placeholder "Paste ChatGPT conversation link"
                         :value (or @source-url* "")
                         :onChange #(reset! source-url* (.. % -target -value))
                         :onBlur #(rf/dispatch [:save-entity
                                                entity-id
                                                {:payload {:sourceUrl (some-> @source-url* str str/trim not-empty)}}])}]])
       (if entity
         [sequence/sequence
          entity
          {:actions-renderer sequence-action-renderer/render-sequence-actions
           :child-options-fn (fn [child-entity]
                               {:on-click (fn []
                                            (when (empty? (:children child-entity))
                                              (controls/navigate-frame!
                                               (model/frame-owner-id child-entity)
                                               (:id child-entity)
                                               :saga)))})
           :on-save-title #(save-entity-title! entity %)
           :on-save-description #(save-entity-description! entity %)
           :add-child-label "Add"
           :add-child-fn #(rf/dispatch [:add-frame entity-id "saga"])}]
         [:div.status "Creating draft sequence..."])
       (when entity
         [:> Group {:justify "center"}
          [:> Button {:variant "filled"
                      :onClick #(reset! dialog-open?* true)}
           "Finnish"]])
       (when entity
         [link-entities-dialog/link-entities-dialog
          {:open @dialog-open?*
           :title "Attach draft sequence"
           :search @dialog-search*
           :sort @dialog-sort*
           :role-filters ["saga" "roster"]
           :entities attachables
           :entities-map entities
           :on-search #(reset! dialog-search* %)
           :on-sort #(reset! dialog-sort* %)
           :on-role-filters (constantly nil)
           :on-close #(do
                        (reset! dialog-open?* false)
                        (reset! dialog-search* "")
                        (reset! dialog-sort* "title-asc"))
           :on-select #(do
                         (rf/dispatch [:save-entity entity-id {:payload {:parentId %}}])
                         (reset! dialog-open?* false))
           :empty-label "No saga or roster available yet."}])])))
