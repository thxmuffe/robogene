(ns webapp.pages.gallery-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.components.chapter :as chapter-component]
            [webapp.components.edit-db-item :as edit-db-item]
            [webapp.components.chapter-menu :as chapter-menu]
            [webapp.components.chapter-actions :as chapter-actions]
            ["@mantine/core" :refer [Box Button Group Stack]]))

(defn next-frame-id-for-key [active-frame-id key]
  (case key
    "ArrowLeft" (frame-nav/adjacent-frame-id active-frame-id -1)
    "ArrowRight" (frame-nav/adjacent-frame-id active-frame-id 1)
    "ArrowUp" (frame-nav/nearest-vertical-frame-id active-frame-id :up)
    "ArrowDown" (frame-nav/nearest-vertical-frame-id active-frame-id :down)
    nil))

(defn on-gallery-key-down [{:keys [active-frame-id view-id any-edit-open?]} e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
    (cond
      (= "Escape" key)
      (do
        (interaction/halt! e)
        (cond
          (or any-edit-open?
              (interaction/modal-open?))
          (rf/dispatch [:cancel-open-edit-db-items])

          (= :roster view-id)
          (rf/dispatch [:navigate-saga-page])

          :else
          (rf/dispatch [:cancel-open-edit-db-items])))

      (not (interaction/ignore-global-keydown? e))
      (cond
        (= "f" lower-key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:toggle-fullscreen-shortcut]))

        (#{"ArrowLeft" "ArrowRight" "ArrowUp" "ArrowDown"} key)
        (when active-frame-id
          (when-let [next-id (next-frame-id-for-key active-frame-id key)]
            (interaction/halt! e)
            (rf/dispatch [:set-active-frame next-id])
            (frame-nav/focus-subtitle! next-id)))

        :else nil)

      :else nil)))

(defn on-name-keydown [entity-label entity-id]
  (fn [e]
    (when (= "Enter" (.-key e))
      (interaction/prevent! e)
      (rf/dispatch [:save-entity entity-label entity-id]))))

(defn on-new-item-name-keydown [add-event set-open-event]
  (fn [e]
    (when (= "Enter" (.-key e))
      (interaction/prevent! e)
      (rf/dispatch [set-open-event false])
      (rf/dispatch [add-event]))))

(defn on-new-item-teaser-click [set-open-event]
  (fn [_]
    (controls/activate-frame! controls/new-chapter-frame-id)
    (rf/dispatch [set-open-event true])))

(defn on-new-item-teaser-keydown [set-open-event]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (interaction/prevent! e)
      ((on-new-item-teaser-click set-open-event) e))))

(defn chapter-celebration []
  [:div.chapter-celebration
   [:div.rainbow-band.band-1]
   [:div.rainbow-band.band-2]
   [:div.rainbow-band.band-3]
   [:div.rainbow-band.band-4]
   [:div.rainbow-stars "✦ ✧ ✦ ✧ ✦"]])

(defn collection-section [cfg entity active-frame-id editing-entity-id entity-name-inputs entity-description-inputs]
  (let [{:keys [entity-id-key entity-label entity-singular owner-type]} cfg
        entity-id (entity-id-key entity)
        chapter-entity? (= "chapter" (str entity-label))
        chapter-collapsed? (when chapter-entity?
                             @(rf/subscribe [:chapter-collapsed? entity-id]))
        entity-name (or (:name entity) (:description entity) "")
        entity-description (or (:description entity) "")]
    [:> Box {:component "section" :className "chapter-block"}
     [:div.chapter-separator-row
      (when chapter-entity?
        [:button
         {:type "button"
          :className "chapter-separator-toggle"
          :title (if chapter-collapsed? "Expand chapter" "Collapse chapter")
          :aria-label (if chapter-collapsed? "Expand chapter" "Collapse chapter")
          :aria-expanded (str (not chapter-collapsed?))
          :onClick #(rf/dispatch [:toggle-chapter-collapsed entity-id])}
         [:span {:className (str "chapter-separator-toggle-triangle"
                                 (when chapter-collapsed? " is-collapsed"))}]])
      [:div.chapter-separator]]
     [:> Group {:className "chapter-header"
                :gap "sm"
                :align "center"
                :wrap "wrap"}
      (if (= editing-entity-id entity-id)
        [edit-db-item/edit-db-item
         {:class-name "chapter-edit-db-item"
          :show-name? true
          :name-value (get entity-name-inputs entity-id "")
          :on-name-change #(rf/dispatch [:entity-name-input-changed entity-label entity-id %])
          :name-props {:size "sm"
                       :className "chapter-name-input"
                       :onKeyDown (on-name-keydown entity-label entity-id)}
          :description-value (get entity-description-inputs entity-id "")
          :on-description-change #(rf/dispatch [:entity-description-input-changed entity-label entity-id %])
          :description-props {:autosize true
                              :minRows 2
                              :maxRows 6
                              :className "chapter-description-input"}
          :on-submit #(rf/dispatch [:save-entity entity-label entity-id])
          :on-cancel #(rf/dispatch [:cancel-entity-name-edit entity-label])
          :actions-class "chapter-edit-actions"}]
        [:<>
         [:p.chapter-name entity-name]
         (when (seq (str/trim entity-description))
           [:p.chapter-description entity-description])])
      [chapter-actions/chapter-actions
       {:entity-id entity-id
        :entity-name entity-name
        :entity-description entity-description
        :entity-label entity-label
        :singular-label entity-singular}]]
     (when-not chapter-collapsed?
       [chapter-component/chapter entity-id owner-type active-frame-id])]))

(defn new-entity-form [cfg name description]
  (let [{:keys [add-event set-open-event add-title name-input-placeholder description-input-placeholder name-changed-event description-changed-event]} cfg]
    [:> Box {:component "section"
             :className "new-chapter-panel"}
     [:h3 add-title]
     [edit-db-item/edit-db-item
      {:class-name "new-entity-edit-db-item"
       :show-name? true
       :name-value (or name "")
       :on-name-change #(rf/dispatch [name-changed-event %])
       :name-props {:size "sm"
                    :className "new-chapter-input"
                    :placeholder name-input-placeholder
                    :onKeyDown (on-new-item-name-keydown add-event set-open-event)}
       :description-value (or description "")
       :on-description-change #(rf/dispatch [description-changed-event %])
       :description-props {:autosize true
                           :minRows 3
                           :maxRows 10
                           :className "new-chapter-input"
                           :placeholder description-input-placeholder}
       :on-submit #(do
                     (rf/dispatch [set-open-event false])
                     (rf/dispatch [add-event]))
       :on-cancel #(rf/dispatch [set-open-event false])
       :actions-class "chapter-edit-actions"}]]))

(defn new-entity-teaser [cfg active-frame-id]
  (let [{:keys [set-open-event teaser-title teaser-sub]} cfg]
    [:article.new-chapter-teaser
     {:class (str "frame frame-clickable add-frame-tile"
                  (when (= active-frame-id controls/new-chapter-frame-id)
                    " frame-active"))
      :data-frame-id controls/new-chapter-frame-id
      :role "button"
      :tab-index 0
      :on-focus #(controls/activate-frame! controls/new-chapter-frame-id)
      :on-click (on-new-item-teaser-click set-open-event)
      :on-key-down (on-new-item-teaser-keydown set-open-event)}
     [:div.add-frame-tile-title teaser-title]
     [:div.add-frame-tile-sub teaser-sub]]))

(defn page-header-action [cfg]
  (case (:view-id cfg)
    :saga
    [:> Button
     {:aria-label "Open roster"
      :title "Open roster"
      :variant "default"
      :size "sm"
      :className "roster-nav-btn"
      :onClick #(rf/dispatch [:navigate-roster-page])}
     "Roster"]

    :roster
    (when-let [back-label (:saga-back-label cfg)]
      [back-button/back-button
       {:label back-label
        :on-click #(rf/dispatch [:navigate-saga-page])}])

    nil))

(defn saga-header-content [page-title]
  (let [saga-meta @(rf/subscribe [:saga-meta])
        saga-meta-editing? @(rf/subscribe [:saga-meta-editing?])
        saga-meta-name @(rf/subscribe [:saga-meta-name])
        saga-meta-description @(rf/subscribe [:saga-meta-description])
        current-name (or (some-> (:name saga-meta) str/trim not-empty) page-title "Saga")
        current-description (or (:description saga-meta) "")]
    (if saga-meta-editing?
      [edit-db-item/edit-db-item
       {:class-name "chapter-edit-db-item saga-meta-edit-db-item"
        :show-name? true
        :name-value (or saga-meta-name "")
        :on-name-change #(rf/dispatch [:saga-meta-name-changed %])
        :name-props {:size "sm"
                     :className "chapter-name-input"}
        :description-value (or saga-meta-description "")
        :on-description-change #(rf/dispatch [:saga-meta-description-changed %])
        :description-props {:autosize true
                            :minRows 2
                            :maxRows 6
                            :className "chapter-description-input"}
        :on-submit #(rf/dispatch [:save-saga-meta])
        :on-cancel #(rf/dispatch [:cancel-saga-meta-edit])
        :actions-class "chapter-edit-actions"}]
      [:div.saga-meta-header
       [:div
        [:h2 current-name]
        (when (seq (str/trim current-description))
          [:p.chapter-description current-description])]])))

(defn saga-header-menu []
  [chapter-menu/chapter-menu
   {:title "Saga actions"
    :aria-label "Saga actions"
    :button-class "chapter-menu-trigger"
    :items [{:id :edit-saga
             :label "Edit saga"}]
    :on-select (fn [_]
                 (rf/dispatch [:start-saga-meta-edit]))}])

(defn collection-page [cfg entities active-frame-id form-description panel-open? show-celebration?]
  (r/with-let [context* (r/atom {:active-frame-id nil :view-id nil :any-edit-open? false})
               focused-active-id* (r/atom nil)
               key-handler (fn [e]
                             (on-gallery-key-down @context* e))]
    (.addEventListener js/window "keydown" key-handler)
    (when (not= active-frame-id @focused-active-id*)
      (reset! focused-active-id* active-frame-id)
      (when active-frame-id
        (.requestAnimationFrame js/window
                                (fn []
                                  (frame-nav/focus-subtitle! active-frame-id)))))
      (let [{:keys [view-id page-title page-class editing-id-sub name-inputs-sub description-inputs-sub entity-id-key]} cfg
          editing-entity-id @(rf/subscribe editing-id-sub)
          entity-name-inputs @(rf/subscribe name-inputs-sub)
          entity-description-inputs @(rf/subscribe description-inputs-sub)
          any-frame-actions-open? @(rf/subscribe [:any-frame-actions-open?])
          any-edit-open? (or panel-open?
                             (some? editing-entity-id)
                             any-frame-actions-open?)]
      (reset! context* {:active-frame-id active-frame-id
                        :view-id view-id
                        :any-edit-open? any-edit-open?})
      [:> Stack {:component "section"
                 :className page-class
                 :gap "md"}
      [:> Group {:className "collection-header"
                  :justify "space-between"
                  :align "center"}
        (if (= :saga view-id)
          [:<>
           [saga-header-content page-title]
           [:div.collection-header-actions
            [page-header-action cfg]
            [saga-header-menu]]]
          [:<>
           [:h2 page-title]
           [page-header-action cfg]])]
       (map-indexed (fn [idx entity]
                      ^{:key (or (entity-id-key entity) (str "entity-" idx))}
                      [collection-section cfg entity active-frame-id editing-entity-id entity-name-inputs entity-description-inputs])
                    entities)
       (when (and show-celebration? (= :saga view-id))
         [chapter-celebration])
       [:section.collection-add-region
        [:div.chapter-separator]
        (if panel-open?
          [new-entity-form cfg (:name form-description) (:description form-description)]
          [new-entity-teaser cfg active-frame-id])]])
    (finally
      (.removeEventListener js/window "keydown" key-handler))))

(def saga-config
  {:view-id :saga
   :route-name "robot emperor"
   :page-class "saga-page"
   :entity-label "chapter"
   :entity-singular "chapter"
   :entity-id-key :chapterId
   :owner-type "saga"
   :page-title "Robot Emperor"
   :editing-id-sub [:editing-chapter-id]
   :name-inputs-sub [:chapter-name-inputs]
   :description-inputs-sub [:chapter-description-inputs]
   :name-changed-event :new-chapter-name-changed
   :description-changed-event :new-chapter-description-changed
   :set-open-event :set-new-chapter-panel-open
   :add-event :add-chapter
   :name-input-placeholder "Name this chapter..."
   :description-input-placeholder "Describe aliases, context, or chapter style..."
   :add-title "Add New Chapter"
   :teaser-title "Add New Chapter"
   :teaser-sub "Click to start a new adventure"})

(defn saga-page [saga active-frame-id new-chapter-name new-chapter-description new-chapter-panel-open? show-chapter-celebration?]
  [collection-page saga-config
   saga
   active-frame-id
   {:name new-chapter-name
    :description new-chapter-description}
   new-chapter-panel-open?
   show-chapter-celebration?])
