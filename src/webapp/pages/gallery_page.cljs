(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [reagent.core :as r]
            [webapp.components.gallery :as gallery]
            [webapp.components.sequence :as sequence]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]))

(defn fetch-entity [id]
  @(rf/subscribe [:entity id]))

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
        "Escape" (do (interaction/halt! e)
                     (rf/dispatch [:collapse-current-gallery-chapter]))
        nil))))

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
       [:div.chapter-content
        [sequence/sequence-description-editor
         {:id (:id chapter)
          :title (:title chapter)
          :description (:description chapter)}
         {:on-save-title #(rf/dispatch [:entity-update "chapter" (:id chapter) % (:description chapter)])
          :on-save-description #(rf/dispatch [:entity-update "chapter" (:id chapter) (:title chapter) %])}
         (r/atom false)]
        [gallery/frame-gallery chapter-id "saga"]])]))

(defn gallery-page [{:keys [entity-id]}]
  (r/with-let [key-context* (r/atom nil)
               key-handler (fn [e]
                             (when-let [entities (:entities @key-context*)]
                               (handle-gallery-key-down! entities e)))]
    (.addEventListener js/window "keydown" key-handler)
    (let [entity @(rf/subscribe [:entity entity-id])
          entities @(rf/subscribe [:entities])
          children-ids (:children entity)
          children (map fetch-entity children-ids)]
      (reset! key-context* {:entities entities})
      (cond
        (nil? entity)
        [:div.gallery-page [:p "Loading gallery..."]]

        (empty? children-ids)
        [:div.gallery-page [:p "No sequences found."]]

        (= "saga" (:vanityRole entity))
        [:div.gallery-page.saga-page
         (for [child children
               :when child]
           ^{:key (:id child)}
           [chapter-block child entities])]

        :else
        [:div.gallery-page.roster-page
         (for [child children
               :when child]
           ^{:key (:id child)}
           [sequence/sequence child
            {:children-fetcher fetch-entity
             :add-child-label "Add Item"
             :add-child-fn #(rf/dispatch [:add-frame (:id child) "character"])
             :on-save-title #(rf/dispatch [:entity-update "character" (:id child) % (:description child)])
             :on-save-description #(rf/dispatch [:entity-update "character" (:id child) (:title child) %])}])]))
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
