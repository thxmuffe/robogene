(ns webapp.pages.gallery-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.components.chapter :as chapter-component]
            [webapp.components.db-text :as db-text]
            [webapp.components.chapter-actions :as chapter-actions]
            [webapp.components.waterfall-row :as waterfall-row]
            ["@mantine/core" :refer [Box Button Group Stack TextInput Textarea]]))

(defn next-frame-id-for-key [active-frame-id key]
  (case key
    "ArrowLeft" (frame-nav/adjacent-frame-id active-frame-id -1)
    "ArrowRight" (frame-nav/adjacent-frame-id active-frame-id 1)
    "ArrowUp" (frame-nav/nearest-vertical-frame-id active-frame-id :up)
    "ArrowDown" (frame-nav/nearest-vertical-frame-id active-frame-id :down)
    nil))

(defn on-gallery-key-down [{:keys [active-frame-id view-id any-edit-open? current-gallery-chapter-id]} e]
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

          (and (= :saga view-id)
               current-gallery-chapter-id)
          (rf/dispatch [:collapse-current-gallery-chapter])

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

(defn- keep-editor-open? [selector]
  (let [active-el (.-activeElement js/document)]
    (or (interaction/closest? active-el selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(defn- ordered-character-frames [frames character-id]
  (->> (or frames [])
       (filter (fn [frame]
                 (and (= "character" (str (or (:ownerType frame) "saga")))
                      (= (:chapterId frame) character-id))))
       (sort-by (fn [frame]
                  [(or (:frameNumber frame) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt frame) "")
                   (or (:frameId frame) "")]))))

(defn- roster-button-image-urls [roster frames]
  (let [character-ids (->> (or roster [])
                           (keep :characterId)
                           (take 4))
        urls-by-character (mapv (fn [character-id]
                                  (->> (ordered-character-frames frames character-id)
                                       (keep (fn [frame]
                                               (some-> (:imageUrl frame) str/trim not-empty)))
                                       (take 3)
                                       vec))
                                character-ids)
        max-depth (apply max 0 (map count urls-by-character))]
    (->> (for [depth (range max-depth)
               urls urls-by-character
               :let [url (nth urls depth nil)]
               :when url]
           url)
         (take 4)
         vec)))

(defn roster-nav-button []
  (let [roster @(rf/subscribe [:roster])
        frames @(rf/subscribe [:gallery-items])
        image-urls (roster-button-image-urls roster frames)
        image-count (count image-urls)
        single-row? (< image-count 3)
        visible-cell-count (if single-row?
                             (max 1 image-count)
                             4)
        cells (concat image-urls (repeat nil))]
    [:> Button
     {:aria-label "Open roster"
      :title "Open roster"
      :variant "default"
      :size "sm"
      :className "roster-nav-btn"
      :onClick #(rf/dispatch [:navigate-roster-page])}
     [:span.roster-nav-btn-content
      [:span {:className (str "roster-nav-btn-grid"
                              (when single-row? " is-row")
                              (str " is-count-" visible-cell-count))}
       (for [[idx image-url] (map-indexed vector (take visible-cell-count cells))]
         ^{:key (str "roster-cell-" idx)}
         [:span.roster-nav-btn-cell
          (when image-url
            [:img {:className "roster-nav-btn-cell-image"
                   :src image-url
                   :alt ""}])])]
      [:span.roster-nav-btn-label "Roster"]]]))

(defn collection-section [cfg entity active-frame-id editing-entity-id]
  (let [{:keys [entity-id-key entity-label entity-singular owner-type]} cfg
        entity-id (entity-id-key entity)
        chapter-entity? (= "chapter" (str entity-label))
        chapter-collapsed? (when chapter-entity?
                             @(rf/subscribe [:gallery-chapter-collapsed? entity-id]))
        entity-name (or (:name entity) (:description entity) "")
        entity-description (or (:description entity) "")
        editing? (= editing-entity-id entity-id)
        name-inputs @(rf/subscribe [(if (= "character" (str entity-label))
                                      :character-name-inputs
                                      :chapter-name-inputs)])
        description-inputs @(rf/subscribe [(if (= "character" (str entity-label))
                                             :character-description-inputs
                                             :chapter-description-inputs)])
        current-name (if editing?
                       (get name-inputs entity-id entity-name)
                       entity-name)
        current-description (if editing?
                              (get description-inputs entity-id entity-description)
                              entity-description)
        editor-selector (str "[data-entity-editor-id=\"" entity-id "\"]")
        frames (when chapter-entity?
                 @(rf/subscribe [:frames-for-chapter entity-id]))
        collapsed-preview-frame (first frames)
        collapsed-preview-image-url (:imageUrl collapsed-preview-frame)]
    [:> Box {:component "section"
             :className (str "chapter-block" (when chapter-collapsed? " is-collapsed"))}
     [:div {:className (str "chapter-separator-row" (when chapter-collapsed? " is-collapsed"))}
      (when chapter-entity?
        [:button
         {:type "button"
          :className "chapter-separator-toggle"
          :title (if chapter-collapsed? "Expand chapter" "Collapse chapter")
          :aria-label (if chapter-collapsed? "Expand chapter" "Collapse chapter")
          :aria-expanded (str (not chapter-collapsed?))
          :onClick #(rf/dispatch [:toggle-gallery-chapter-collapsed entity-id])}
         [:span {:className (str "chapter-separator-toggle-triangle"
                                 (when chapter-collapsed? " is-collapsed"))}]])
      (if chapter-entity?
        [:button
         {:type "button"
          :className (str "chapter-separator" (when chapter-collapsed? " is-collapsed"))
          :title (if chapter-collapsed? "Expand chapter" "Collapse chapter")
         :aria-label (if chapter-collapsed? "Expand chapter" "Collapse chapter")
          :aria-expanded (str (not chapter-collapsed?))
          :onClick #(rf/dispatch [:toggle-gallery-chapter-collapsed entity-id])}
         (when chapter-collapsed?
           [:<>
            [:span.chapter-separator-preview
             (if (seq (str/trim (or collapsed-preview-image-url "")))
               [:img {:className "chapter-separator-preview-image"
                      :src collapsed-preview-image-url
                      :alt (str entity-name " first frame preview")}]
               [:span.chapter-separator-preview-placeholder])]
            [:span.chapter-separator-title entity-name]])]
        [:div.chapter-separator])]
     (if (and chapter-entity? chapter-collapsed?)
       nil
       [:div.chapter-content
        [:div.chapter-header
         {:data-entity-editor-id entity-id}
         [db-text/db-text
          {:id (str entity-label "-" entity-id "-title")
           :value current-name
           :editing? editing?
           :class-name "chapter-header-body"
           :display-class-name "chapter-name"
           :editing-class-name "chapter-db-item"
           :input-class-name "chapter-name-input"
           :placeholder (str "Name this " entity-singular "...")
           :on-open-edit #(rf/dispatch [:start-entity-edit entity-label entity-id entity-name entity-description])
           :on-close-edit #(rf/dispatch [:cancel-entity-name-edit entity-label])
           :on-change #(rf/dispatch [:entity-name-input-changed entity-label entity-id %])
           :on-save (fn [_]
                      (rf/dispatch [:save-entity entity-label entity-id]))
           :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
         [db-text/db-text
          {:id (str entity-label "-" entity-id "-desc")
           :value current-description
           :editing? editing?
           :multiline? true
           :class-name "chapter-header-body"
           :display-class-name "chapter-description"
           :editing-class-name "chapter-db-item"
           :input-class-name "chapter-description-input"
           :placeholder ""
           :min-rows 2
           :max-rows 6
           :on-open-edit #(rf/dispatch [:start-entity-edit entity-label entity-id entity-name entity-description])
           :on-close-edit #(rf/dispatch [:cancel-entity-name-edit entity-label])
           :on-change #(rf/dispatch [:entity-description-input-changed entity-label entity-id %])
           :on-save (fn [_]
                      (rf/dispatch [:save-entity entity-label entity-id]))
           :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
         [chapter-actions/chapter-actions
          {:entity-id entity-id
           :entity-label entity-label
           :singular-label entity-singular}]]
        [chapter-component/chapter entity-id owner-type active-frame-id]])]))

(defn new-entity-form [cfg name description]
  (let [{:keys [add-event set-open-event add-title name-input-placeholder description-input-placeholder name-changed-event description-changed-event]} cfg]
    [:> Box {:component "section"
             :className "new-chapter-panel"}
     [:h3 add-title]
     [:> TextInput
      {:className "new-chapter-input"
       :value (or name "")
       :placeholder name-input-placeholder
       :onChange #(rf/dispatch [name-changed-event (.. % -target -value)])
       :onKeyDown (on-new-item-name-keydown add-event set-open-event)}]
     [:> Textarea
      {:className "new-chapter-input"
       :value (or description "")
       :autosize true
       :minRows 3
       :maxRows 10
       :placeholder description-input-placeholder
       :onChange #(rf/dispatch [description-changed-event (.. % -target -value)])}]
     [:div.chapter-edit-actions
      [:> Button
       {:className "new-chapter-submit"
        :onClick #(do
                    (rf/dispatch [set-open-event false])
                    (rf/dispatch [add-event]))}
       "Submit"]
      [:> Button
       {:variant "default"
        :className "new-chapter-submit"
        :onClick #(rf/dispatch [set-open-event false])}
       "Cancel"]]]))

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
    [roster-nav-button]

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
        current-description (or (:description saga-meta) "")
        edit-name (if saga-meta-editing? saga-meta-name current-name)
        edit-description (if saga-meta-editing? saga-meta-description current-description)
        editor-selector "[data-saga-meta-editor='true']"]
    [:div.saga-meta-header
     {:data-saga-meta-editor "true"}
     [db-text/db-text
      {:id "saga-meta-title"
       :value (or edit-name "")
       :editing? saga-meta-editing?
       :class-name "saga-meta-db-item"
       :display-class-name "chapter-name saga-title"
       :editing-class-name "chapter-db-item saga-meta-db-item"
       :input-class-name "chapter-name-input"
       :display-as :h2
       :placeholder "Name this saga..."
       :on-open-edit #(rf/dispatch [:start-saga-meta-edit])
       :on-close-edit #(rf/dispatch [:cancel-saga-meta-edit])
       :on-change #(rf/dispatch [:saga-meta-name-changed %])
       :on-save (fn [_]
                  (rf/dispatch [:save-saga-meta]))
       :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
     [db-text/db-text
      {:id "saga-meta-desc"
       :value (or edit-description "")
       :editing? saga-meta-editing?
       :multiline? true
       :class-name "saga-meta-db-item"
       :display-class-name "chapter-description"
       :editing-class-name "chapter-db-item saga-meta-db-item"
       :input-class-name "chapter-description-input"
       :display-as :p
       :placeholder ""
       :min-rows 2
       :max-rows 6
       :on-open-edit #(rf/dispatch [:start-saga-meta-edit])
       :on-close-edit #(rf/dispatch [:cancel-saga-meta-edit])
       :on-change #(rf/dispatch [:saga-meta-description-changed %])
       :on-save (fn [_]
                  (rf/dispatch [:save-saga-meta]))
       :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
     [waterfall-row/waterfall-row
      {:class-name "chapter-header-actions-row saga-header-actions-row"
       :prefix-content [page-header-action {:view-id :saga}]
       :actions []
       :menu-title "Saga actions"
       :menu-aria-label "Saga actions"}]]))

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
      (let [{:keys [view-id page-title page-class editing-id-sub entity-id-key]} cfg
          editing-entity-id @(rf/subscribe editing-id-sub)
          saga-meta-editing? @(rf/subscribe [:saga-meta-editing?])
          gallery-items @(rf/subscribe [:gallery-items])
          any-frame-actions-open? @(rf/subscribe [:any-frame-actions-open?])
          current-gallery-chapter-id (some (fn [frame]
                                             (when (= (:frameId frame) active-frame-id)
                                               (:chapterId frame)))
                                           gallery-items)
          any-edit-open? (or panel-open?
                             (some? editing-entity-id)
                             saga-meta-editing?
                             any-frame-actions-open?)]
      (reset! context* {:active-frame-id active-frame-id
                        :view-id view-id
                        :any-edit-open? any-edit-open?
                        :current-gallery-chapter-id current-gallery-chapter-id})
      [:> Stack {:component "section"
                 :className page-class
                 :gap "md"}
      [:> Group {:className "collection-header"
                  :justify "center"
                  :align "center"}
        (if (= :saga view-id)
          [saga-header-content page-title]
          [:<>
           [:h2 page-title]
           [page-header-action cfg]])]
       (map-indexed (fn [idx entity]
                      ^{:key (or (entity-id-key entity) (str "entity-" idx))}
                      [collection-section cfg entity active-frame-id editing-entity-id])
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
