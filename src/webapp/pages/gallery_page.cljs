(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]))

(defn fetch-entity [id]
  @(rf/subscribe [:entity id]))

(defn save-entity-title! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:title text}]))

(defn save-entity-description! [entity text]
  (rf/dispatch [:save-entity (:id entity) {:description text}]))

(defn toggle-collapsed! [collapsed-sequence-ids* effective-collapsed-ids sequence-id]
  (let [sequence-id (model/entity-id {:id sequence-id})
        collapsed-ids (or effective-collapsed-ids #{})]
    (reset! collapsed-sequence-ids*
            (if (contains? collapsed-ids sequence-id)
              (disj collapsed-ids sequence-id)
              (conj collapsed-ids sequence-id)))))

(defn navigate-active-frame! [direction]
  (when-let [active-id @(rf/subscribe [:active-frame-id])]
    (when-let [target-id (frame-nav/adjacent-frame-id active-id (if (= direction :next) 1 -1))]
      (rf/dispatch [:set-active-frame target-id])
      (rf/dispatch [:scroll-frame-into-view target-id]))))

(defn navigate-active-frame-vertical! [direction]
  (when-let [active-id @(rf/subscribe [:active-frame-id])]
    (when-let [target-id (frame-nav/nearest-vertical-frame-id active-id direction)]
      (rf/dispatch [:set-active-frame target-id])
      (rf/dispatch [:scroll-frame-into-view target-id]))))

(defn open-active-frame! [entities]
  (when-let [frame-id @(rf/subscribe [:active-frame-id])]
    (when-let [frame-entity (get entities frame-id)]
      (controls/navigate-frame!
       (model/frame-owner-id frame-entity)
       frame-id
       (case (model/entity-role (get entities (model/frame-owner-id frame-entity)))
         "character" :roster
         :saga)))))

(defn handle-gallery-key-down! [entities e]
  (let [key (or (.-key e) "")]
    (when-not (interaction/ignore-global-keydown? e)
      (case key
        "ArrowLeft" (do (interaction/halt! e)
                        (navigate-active-frame! :prev))
        "ArrowRight" (do (interaction/halt! e)
                         (navigate-active-frame! :next))
        "ArrowUp" (do (interaction/halt! e)
                      (navigate-active-frame-vertical! :up))
        "ArrowDown" (do (interaction/halt! e)
                        (navigate-active-frame-vertical! :down))
        "Enter" (do (interaction/halt! e)
                    (open-active-frame! entities))
        nil))))

(defn child-sequence-block [child-sequence entities collapsed? on-toggle]
  (let [sequence-id (model/entity-id child-sequence)
        preview-url (model/preview-image-url entities sequence-id)
        title (model/primary-label child-sequence)]
    [:section {:className (str "sequence-group" (when collapsed? " is-collapsed"))}
     [:div {:className (str "sequence-box-row" (when collapsed? " is-collapsed"))}
      [:button.sequence-box-toggle
       {:type "button"
        :aria-label (if collapsed? "Expand sequence" "Collapse sequence")
        :onClick on-toggle}
       [:span {:className (str "sequence-box-toggle-triangle"
                               (when collapsed? " is-collapsed"))}]]
      [:button {:type "button"
                :className (str "sequence-box" (when collapsed? " is-collapsed"))
                :onClick on-toggle}
       (when collapsed?
         [:div.sequence-box-preview
          (if (seq preview-url)
            [:img {:className "sequence-box-preview-image"
                   :src preview-url
                   :alt (str title " preview")}]
            [:div.sequence-box-preview-placeholder])])
       (when collapsed?
         [:span.sequence-box-title title])]]
     (when-not collapsed?
       [:div.sequence-group-content
        [sequence/sequence
         child-sequence
         {:children-fetcher fetch-entity
          :actions-renderer sequence-action-renderer/render-sequence-actions
          :on-save-title #(save-entity-title! child-sequence %)
          :on-save-description #(save-entity-description! child-sequence %)
          :add-child-label "Add New Frame"
          :add-child-fn #(rf/dispatch [:add-frame sequence-id "saga"])}]])]))

(defn gallery-page [{:keys [entity-id]}]
  (r/with-let [key-context* (r/atom nil)
               collapsed-sequence-ids* (r/atom nil)
               collapse-owner-id* (r/atom nil)
               title-editing-atom (r/atom false)
               description-editing-atom (r/atom false)
               key-handler (fn [e]
                             (when-let [entities (:entities @key-context*)]
                               (handle-gallery-key-down! entities e)))]
    (.addEventListener js/window "keydown" key-handler)
    (let [entity @(rf/subscribe [:entity entity-id])
          entities @(rf/subscribe [:entities])
          children-ids (:children entity)
          children (map fetch-entity children-ids)
          child-sequence-ids (->> children
                                  (keep model/entity-id)
                                  set)]
      (cond
        (not= @collapse-owner-id* entity-id)
        (do
          (reset! collapse-owner-id* entity-id)
          (when (seq child-sequence-ids)
            (reset! collapsed-sequence-ids* child-sequence-ids)))

        (and (nil? @collapsed-sequence-ids*) (seq child-sequence-ids))
        (reset! collapsed-sequence-ids* child-sequence-ids)

        :else
        (let [filtered-collapsed (set (filter child-sequence-ids (or @collapsed-sequence-ids* #{})))]
          (when (not= filtered-collapsed @collapsed-sequence-ids*)
            (reset! collapsed-sequence-ids* filtered-collapsed))))
      (reset! key-context* {:entities entities})
      (let [effective-collapsed-ids (or @collapsed-sequence-ids* child-sequence-ids #{})]
        (cond
          (nil? entity)
          [:div.gallery-page [:p "Loading gallery..."]]

          (= "saga" (:vanityRole entity))
          [:div.gallery-page.saga-page
           [sequence/sequence-description-editor
            entity
            {:on-save-title #(save-entity-title! entity %)
             :on-save-description #(save-entity-description! entity %)
             :actions-renderer sequence-action-renderer/render-sequence-actions}
            title-editing-atom
            description-editing-atom]
           (if (seq children-ids)
             (for [child children
                   :when child]
               ^{:key (:id child)}
               [child-sequence-block child
                entities
                (contains? effective-collapsed-ids (model/entity-id child))
                #(toggle-collapsed! collapsed-sequence-ids* effective-collapsed-ids (model/entity-id child))])
             [:p.gallery-empty-message "No sequences found."])]

          :else
          [:div.gallery-page.roster-page
           [sequence/sequence-description-editor
            entity
            {:on-save-title #(save-entity-title! entity %)
             :on-save-description #(save-entity-description! entity %)
             :actions-renderer sequence-action-renderer/render-sequence-actions}
            title-editing-atom
            description-editing-atom]
           (if (seq children-ids)
             (for [child children
                   :when child]
               ^{:key (:id child)}
               [sequence/sequence child
                {:children-fetcher fetch-entity
                 :actions-renderer sequence-action-renderer/render-sequence-actions
                 :add-child-label "New"
                 :add-child-fn #(rf/dispatch [:add-frame (:id child) "character"])
                 :on-save-title #(save-entity-title! child %)
                 :on-save-description #(save-entity-description! child %)}])
             [:p.gallery-empty-message "No sequences found."])])))
    (finally
      (.removeEventListener js/window "keydown" key-handler))))

(defn gallery-page-view []
  (let [route @(rf/subscribe [:route])
        entity-id (or (:entity-id route)
                      (:saga-id route)
                      (:roster-id route))]
    (if entity-id
      [gallery-page {:entity-id entity-id}]
      [:div.gallery-page [:p "No gallery target in route."]])))
