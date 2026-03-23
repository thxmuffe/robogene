(ns webapp.pages.sequence-page
  "Generic sequence page (collection with children)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]
            [webapp.shared.ui.interaction :as interaction]))

(defn fetch-child-entity [child-id]
  @(rf/subscribe [:entity child-id]))

(defn save-entity-title! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:title text}]))

(defn save-entity-description! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:description text}]))

(defn sequence-page [{:keys [entity-id]}]
  (r/with-let [key-handler (fn [e]
                             (when-not (interaction/ignore-global-keydown? e)
                               (when (= "f" (str/lower-case (or (.-key e) "")))
                                 (interaction/halt! e)
                                 (rf/dispatch [:toggle-fullscreen-shortcut]))))]
    (.addEventListener js/window "keydown" key-handler)
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
          :add-child-label (case (:vanityRole entity)
                             ("chapter" "character" "roster") "Add"
                             "New")
          :add-child-fn (fn []
                          (when (#{"chapter" "character" "roster"} (:vanityRole entity))
                            (rf/dispatch [:add-frame
                                          (:id entity)
                                          (if (= "character" (:vanityRole entity))
                                            "character"
                                            "saga")])))
          :on-delete (fn []
                       (when (js/confirm "Delete this sequence?")
                         (rf/dispatch [:entity-delete entity-id])))}]))
    (finally
      (.removeEventListener js/window "keydown" key-handler))))

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
