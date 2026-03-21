(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [clojure.set :as set]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
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

(defn toggle-collapsed! [collapsed-chapter-ids* effective-collapsed-ids chapter-id]
  (let [chapter-id (model/entity-id {:id chapter-id})
        collapsed-ids (or effective-collapsed-ids #{})]
    (reset! collapsed-chapter-ids*
            (if (contains? collapsed-ids chapter-id)
              (disj collapsed-ids chapter-id)
              (conj collapsed-ids chapter-id)))))

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
       (or (get-in frame-entity [:payload :parentId])
           (get-in frame-entity [:payload :chapterId])
           (get-in frame-entity [:payload :characterId]))
       frame-id
       (case (model/entity-role (get entities (or (get-in frame-entity [:payload :parentId])
                                                  (get-in frame-entity [:payload :chapterId])
                                                  (get-in frame-entity [:payload :characterId]))))
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

(defn chapter-block [chapter entities collapsed? on-toggle]
  (let [chapter-id (model/entity-id chapter)
        preview-url (model/preview-image-url entities chapter-id)
        title (model/primary-label chapter)]
    [:section {:className (str "chapter-block" (when collapsed? " is-collapsed"))}
     [:div {:className (str "chapter-separator-row sequence-box-row" (when collapsed? " is-collapsed"))}
      [:button.chapter-separator-toggle.sequence-box-toggle
       {:type "button"
        :aria-label (if collapsed? "Expand chapter" "Collapse chapter")
        :onClick on-toggle}
       [:span {:className (str "chapter-separator-toggle-triangle sequence-box-toggle-triangle"
                               (when collapsed? " is-collapsed"))}]]
      [:button {:type "button"
                :className (str "chapter-separator sequence-box" (when collapsed? " is-collapsed"))
                :onClick on-toggle}
       (when collapsed?
         [:div.chapter-separator-preview.sequence-box-preview
          (if (seq preview-url)
            [:img {:className "chapter-separator-preview-image sequence-box-preview-image"
                   :src preview-url
                   :alt (str title " preview")}]
            [:div.chapter-separator-preview-placeholder.sequence-box-preview-placeholder])])
       (when collapsed?
         [:span.chapter-separator-title.sequence-box-title title])]]
     (when-not collapsed?
       [:div.chapter-content
        [sequence/sequence
         chapter
         {:children-fetcher fetch-entity
          :on-save-title #(save-entity-title! chapter %)
          :on-save-description #(save-entity-description! chapter %)
          :add-child-label "Add New Frame"
          :add-child-fn #(rf/dispatch [:add-frame chapter-id "saga"])}]])]))

(defn gallery-page [{:keys [entity-id]}]
  (r/with-let [key-context* (r/atom nil)
               collapsed-chapter-ids* (r/atom nil)
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
          chapter-ids (->> children
                           (keep model/entity-id)
                           set)]
      (cond
        (not= @collapse-owner-id* entity-id)
        (do
          (reset! collapse-owner-id* entity-id)
          (when (seq chapter-ids)
            (reset! collapsed-chapter-ids* chapter-ids)))

        (and (nil? @collapsed-chapter-ids*) (seq chapter-ids))
        (reset! collapsed-chapter-ids* chapter-ids)

        :else
        (let [filtered-collapsed (set (filter chapter-ids (or @collapsed-chapter-ids* #{})))]
          (when (not= filtered-collapsed @collapsed-chapter-ids*)
            (reset! collapsed-chapter-ids* filtered-collapsed))))
      (reset! key-context* {:entities entities})
      (let [effective-collapsed-ids (or @collapsed-chapter-ids* chapter-ids #{})]
      (cond
        (nil? entity)
        [:div.gallery-page [:p "Loading gallery..."]]

        (empty? children-ids)
        [:div.gallery-page [:p "No sequences found."]]

        (= "saga" (:vanityRole entity))
        [:div.gallery-page.saga-page
         [sequence/sequence-description-editor
          entity
          {:on-save-title #(save-entity-title! entity %)
           :on-save-description #(save-entity-description! entity %)}
         title-editing-atom
         description-editing-atom]
         (for [child children
               :when child]
           ^{:key (:id child)}
            [chapter-block child
            entities
            (contains? effective-collapsed-ids (model/entity-id child))
            #(toggle-collapsed! collapsed-chapter-ids* effective-collapsed-ids (model/entity-id child))])]

        :else
        [:div.gallery-page.roster-page
         [sequence/sequence-description-editor
          entity
          {:on-save-title #(save-entity-title! entity %)
           :on-save-description #(save-entity-description! entity %)}
          title-editing-atom
          description-editing-atom]
         (for [child children
               :when child]
           ^{:key (:id child)}
           [sequence/sequence child
            {:children-fetcher fetch-entity
             :add-child-label "Add Item"
             :add-child-fn #(rf/dispatch [:add-frame (:id child) "character"])
             :on-save-title #(save-entity-title! child %)
             :on-save-description #(save-entity-description! child %)}])])))
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
