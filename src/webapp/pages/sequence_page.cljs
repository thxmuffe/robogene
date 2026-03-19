(ns webapp.pages.sequence-page
  "Generic sequence page (collection with children)."
  (:require [re-frame.core :as rf]
            [webapp.components.sequence :as sequence]
            [webapp.shared.controls :as controls]))

(defn fetch-child-entity [child-id]
  @(rf/subscribe [:entity child-id]))

(defn sequence-page [{:keys [entity-id]}]
  (let [entity @(rf/subscribe [:entity entity-id])]
    (if (nil? entity)
      [:div.sequence-page
       [:p "Loading sequence..."]]
      [sequence/sequence
       entity
       {:children-fetcher fetch-child-entity
        :child-options-fn (fn [child-entity]
                            {:on-click (fn []
                                         (when (empty? (:children child-entity))
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
        ;; Sequences rendered here are item-level parents (chapter/character/roster).
        ;; Saga/roster-level "add sequence" belongs in gallery, so we only add items here.
        :add-child-label (case (:vanityRole entity)
                           ("chapter" "character" "roster") "Add Item"
                           "Add Item")
        :add-child-fn (fn []
                        (when (#{ "chapter" "character" "roster"} (:vanityRole entity))
                          (rf/dispatch [:add-frame (:id entity)])))
        :on-delete (fn []
                     (when (js/confirm "Delete this sequence?")
                       (rf/dispatch [:entity-delete entity-id])))}])))

(defn sequence-page-view []
  (let [route @(rf/subscribe [:route])]
    (if-let [entity-id (or (:entity-id route)
                           (:chapter route)
                           (:saga-id route)
                           (:roster-id route)
                           (:chapter-id route))]
      [sequence-page {:entity-id entity-id}]
      [:div.sequence-page
       [:p "No sequence specified in route."]])))
