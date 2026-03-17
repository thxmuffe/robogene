(ns webapp.pages.entity-gallery-page
  "Generic gallery page using unified entity model.
   Renders a sequence (saga, roster, chapter, character) and its children (chapters, characters, frames)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.components.sequence :as sequence]
            [webapp.components.db-text :as db-text]
            ["@mantine/core" :refer [Box Stack Button]]))

(defn fetch-child-entity [child-id]
  "Fetch a child entity from the state given its ID."
  @(rf/subscribe [:entity child-id]))

(defn entity-gallery-page [{:keys [entity-id parent-vanity-role]}]
  "Render a gallery page for a given entity (saga, roster, chapter, character).
   
   Props:
   - entity-id: The UUID of the entity to display
   - parent-vanity-role: Optional hint about entity type (for styling/behavior)"
  (let [entity @(rf/subscribe [:entity entity-id])
        children @(rf/subscribe [:entity-children entity-id])
        editing? @(rf/subscribe [:entity-editing? entity-id])]
    
    (if (nil? entity)
      [:div.entity-gallery-page
       [:p "Loading entity..."]]
      
      [sequence/sequence
       entity
       {:children-fetcher fetch-child-entity
        :child-options-fn (fn [child-entity]
                            {:on-click (fn [e]
                                        (when (not (:children child-entity))
                                          ;; For leaf items (frames), navigate to detail page
                                          (controls/navigate-frame! 
                                            (:id child-entity)
                                            (get-in child-entity [:payload :chapterId])
                                            (case (:vanityRole child-entity)
                                              "frame" :saga
                                              :roster))))})
        
        :on-save-title (fn [text]
                        (rf/dispatch [:entity-update entity-id {:title text}]))
        
        :on-save-description (fn [text]
                              (rf/dispatch [:entity-update entity-id {:description text}]))
        
        :add-child-label (case (:vanityRole entity)
                           "saga" "Add Chapter"
                           "chapter" "Add Frame"
                           "roster" "Add Character"
                           "character" "Add Image"
                           "Add Item")
        
        :add-child-fn (fn []
                       (case (:vanityRole entity)
                         "saga" (rf/dispatch [:add-chapter (:id entity)])
                         "chapter" (rf/dispatch [:add-frame (:id entity)])
                         "roster" (rf/dispatch [:add-character (:id entity)])
                         "character" (rf/dispatch [:add-frame (:id entity)])
                         nil))
        
        :on-delete (fn []
                    (when (js/confirm "Delete this entity?")
                      (rf/dispatch [:entity-delete entity-id])))}])))

(defn entity-gallery-page-view []
  "Main page component that extracts entity-id from route."
  (let [route @(rf/subscribe [:route])]
    (if-let [entity-id (or (:entity-id route)
                          (:saga-id route)
                          (:roster-id route)
                          (:chapter-id route))]
      [entity-gallery-page {:entity-id entity-id}]
      [:div.entity-gallery-page
       [:p "No entity specified in route."]])))
