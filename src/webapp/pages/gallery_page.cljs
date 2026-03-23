(ns webapp.pages.gallery-page
  "Gallery page: render a sequence of sequences (e.g., saga → chapters, roster → characters)."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.sequence :as sequence]
            [webapp.shared.sequence-action-renderer :as sequence-action-renderer]
            [webapp.shared.controls :as controls]
            [webapp.shared.model :as model]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]))

(defn fetch-entity [id]
  @(rf/subscribe [:entity id]))

(defn save-entity-title! [entity-id text]
  (rf/dispatch [:save-entity entity-id {:title text}]))

(defn save-entity-description! [entity-id text]
  (rf/dispatch [:save-entity entity-id {:description text}]))

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

(defn open-active-frame! []
  (when-let [frame-id @(rf/subscribe [:active-frame-id])]
    (when-let [frame-entity @(rf/subscribe [:entity frame-id])]
      (let [owner-id (model/frame-owner-id frame-entity)
            owner-entity @(rf/subscribe [:entity owner-id])]
      (controls/navigate-frame!
       owner-id
       frame-id
       (case (model/entity-role owner-entity)
         "character" :roster
         :saga))))))

(defn handle-gallery-key-down! [e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
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
                    (open-active-frame!))
        nil)
      (when (= "f" lower-key)
        (interaction/halt! e)
        (rf/dispatch [:toggle-fullscreen-shortcut])))))

(defn child-sequence-block [child-id collapsed? on-toggle owner-type add-child-label]
  (let [child-sequence @(rf/subscribe [:entity child-id])
        preview-url @(rf/subscribe [:entity-preview-url child-id])]
    (when child-sequence
      (let [sequence-id (model/entity-id child-sequence)
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
              :on-save-title #(save-entity-title! sequence-id %)
              :on-save-description #(save-entity-description! sequence-id %)
              :add-child-label add-child-label
              :add-child-fn #(rf/dispatch [:add-frame sequence-id owner-type])}]])]))))

(defn gallery-page [{:keys [entity-id]}]
  (r/with-let [collapsed-sequence-ids* (r/atom nil)
               collapse-owner-id* (r/atom nil)
               title-editing-atom (r/atom false)
               description-editing-atom (r/atom false)
               key-handler handle-gallery-key-down!]
    (.addEventListener js/window "keydown" key-handler)
    (let [entity @(rf/subscribe [:entity entity-id])
          children-ids @(rf/subscribe [:entity-children-ids entity-id])
          child-sequence-ids (set children-ids)]
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
      (let [effective-collapsed-ids (or @collapsed-sequence-ids* child-sequence-ids #{})
            role (some-> (:vanityRole entity) str)
            page-class (case role
                         "roster" "roster-page"
                         "saga" "saga-page"
                         "gallery-page")
            child-owner-type (if (= role "roster") "character" "saga")
            add-child-label (if (= role "roster") "Add" "Add New Frame")]
        (cond
          (nil? entity)
          [:div.gallery-page [:p "Loading gallery..."]]

          (contains? #{"saga" "roster"} role)
          [:div {:className (str "gallery-page " page-class)}
           [sequence/sequence-description-editor
            entity
            {:on-save-title #(save-entity-title! entity-id %)
             :on-save-description #(save-entity-description! entity-id %)
             :actions-renderer sequence-action-renderer/render-sequence-actions}
            title-editing-atom
            description-editing-atom]
           (if (seq children-ids)
             (for [child-id children-ids]
               ^{:key child-id}
               [child-sequence-block child-id
                (contains? effective-collapsed-ids child-id)
                #(toggle-collapsed! collapsed-sequence-ids* effective-collapsed-ids child-id)
                child-owner-type
                add-child-label])
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
