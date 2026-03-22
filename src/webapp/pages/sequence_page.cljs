(ns webapp.pages.sequence-page
  "Generic sequence page (collection with children)."
  (:require [re-frame.core :as rf]
            [webapp.components.sequence :as sequence]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]))

(defn fetch-child-entity [child-id]
  @(rf/subscribe [:entity child-id]))

(defn save-entity-title! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:title text}]))

(defn save-entity-description! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:description text}]))

(defn sequence-page [{:keys [entity-id]}]
  (let [entity @(rf/subscribe [:entity entity-id])]
    (if (nil? entity)
      [:div.sequence-page
       [:p "Loading sequence..."]]
      [sequence/sequence
       entity
       {:children-fetcher fetch-child-entity
        :actions-renderer sequence-action-renderer/render-sequence-actions
        :child-options-fn (fn [child-entity]
                            {:on-click (fn []
                                        (when (empty? (:children child-entity))
                                          (controls/navigate-frame!
                                            (model/frame-owner-id child-entity)
                                            (:id child-entity)
                                            (case (:vanityRole entity)
                                              "character" :roster
                                              :saga))))})
        :on-save-title #(save-entity-title! entity %)
        :on-save-description #(save-entity-description! entity %)
        ;; Sequences rendered here are item-level parents (chapter/character/roster).
        ;; Saga/roster-level "add sequence" belongs in gallery, so we only add items here.
        :add-child-label (case (:vanityRole entity)
                           ("chapter" "character" "roster") "New"
                           "New")
        :add-child-fn (fn []
                        (when (#{ "chapter" "character" "roster"} (:vanityRole entity))
                          (rf/dispatch [:add-frame
                                        (:id entity)
                                        (if (= "character" (:vanityRole entity))
                                          "character"
                                          "saga")])))
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
