(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [webapp.components.sequence :as sequence]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]))

(defn fetch-entity [id]
  @(rf/subscribe [:entity id]))

(defn chapter-block [chapter entities]
  (let [chapter-id (:id chapter)
        collapsed? @(rf/subscribe [:gallery-chapter-collapsed? chapter-id])
        preview-url (model/preview-image-url entities chapter-id)
        title (model/primary-label chapter)]
    [:section {:className (str "chapter-block" (when collapsed? " is-collapsed"))}
     [:div {:className (str "chapter-separator-row" (when collapsed? " is-collapsed"))}
      [:button.chapter-separator-toggle
       {:type "button"
        :aria-label (if collapsed? "Expand chapter" "Collapse chapter")
        :onClick #(rf/dispatch [:toggle-gallery-chapter-collapsed chapter-id])}
       [:span {:className (str "chapter-separator-toggle-triangle"
                               (when collapsed? " is-collapsed"))}]]
      [:button {:type "button"
                :className (str "chapter-separator" (when collapsed? " is-collapsed"))
                :onClick #(rf/dispatch [:toggle-gallery-chapter-collapsed chapter-id])}
       (when collapsed?
         [:div.chapter-separator-preview
          (if (seq preview-url)
            [:img {:className "chapter-separator-preview-image"
                   :src preview-url
                   :alt (str title " preview")}]
            [:div.chapter-separator-preview-placeholder])])
       (when collapsed?
         [:span.chapter-separator-title title])]]
     (when-not collapsed?
       [sequence/sequence
        chapter
        {:children-fetcher fetch-entity
         :child-options-fn (fn [child-entity]
                             {:on-click (fn []
                                          (when (empty? (:children child-entity))
                                            (controls/navigate-frame!
                                             (or (get-in child-entity [:payload :parentId])
                                                 (get-in child-entity [:payload :chapterId])
                                                 (get-in child-entity [:payload :characterId]))
                                             (:id child-entity)
                                             :saga)))})
         :add-child-label "Add Frame"
         :add-child-fn #(rf/dispatch [:add-frame (:id chapter) "saga"])
         :on-save-title #(rf/dispatch [:entity-update "chapter" (:id chapter) % (:description chapter)])
         :on-save-description #(rf/dispatch [:entity-update "chapter" (:id chapter) (:title chapter) %])}])]))

(defn gallery-page [{:keys [entity-id]}]
  (let [entity @(rf/subscribe [:entity entity-id])
        entities @(rf/subscribe [:entities])
        children-ids (:children entity)
        children (map fetch-entity children-ids)]
    (cond
      (nil? entity)
      [:div.gallery-page [:p "Loading gallery..."]]

      (empty? children-ids)
      [:div.gallery-page [:p "No sequences found."]]

      (= "saga" (:vanityRole entity))
      [:div.gallery-page
       (for [child children
             :when child]
         ^{:key (:id child)}
         [chapter-block child entities])]

      :else
      [:div.gallery-page
       (for [child children
             :when child]
         ^{:key (:id child)}
         [sequence/sequence child
          {:children-fetcher fetch-entity
           :add-child-label "Add Item"
           :add-child-fn #(rf/dispatch [:add-frame (:id child) "character"])
           :on-save-title #(rf/dispatch [:entity-update "character" (:id child) % (:description child)])
           :on-save-description #(rf/dispatch [:entity-update "character" (:id child) (:title child) %])}])])))

(defn gallery-page-view []
  (let [route @(rf/subscribe [:route])
        entity-id (or (:entity-id route)
                      (:saga-id route)
                      (:roster-id route))]
    (if entity-id
      [gallery-page {:entity-id entity-id}]
      [:div.gallery-page [:p "No gallery target in route."]])))
