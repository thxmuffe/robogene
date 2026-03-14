(ns webapp.pages.gallery-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.components.gallery :as gallery]
            [webapp.components.db-text :as db-text]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.components.waterfall-row :as waterfall-row]
            ["@mantine/core" :refer [Box Button Group Pagination Stack TextInput Textarea]]
            ["react-icons/fa6" :refer [FaArrowUpRightFromSquare FaBroom FaImages FaTrashCan]]))

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

(defn entity-actions [{:keys [entity-id entity-label singular-label]}]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)
               upload-open?* (r/atom false)]
    (let [label (or singular-label "chapter")
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          entity-label (or entity-label "chapter")
          owner-type (if (= "character" (str entity-label)) "character" "saga")
          frames (when (not= "saga" (str entity-label))
                   @(rf/subscribe [:frames-for-owner owner-type entity-id]))
          empty-frames (filterv (fn [frame]
                                  (str/blank? (or (:imageUrl frame) "")))
                                (or frames []))
          empty-frame-count (count empty-frames)
          title-case-label (str (str/upper-case (subs entity-label 0 1)) (subs entity-label 1))
          items (cond-> []
                  (= "saga" (str entity-label))
                  (conj {:id :open-saga-page
                         :label "Open saga page"
                         :icon FaArrowUpRightFromSquare
                         :color "indigo"
                         :on-select (fn [_]
                                      (rf/dispatch [:navigate-saga-page entity-id]))})
                  (= "chapter" (str entity-label))
                  (conj {:id :open-chapter-page
                         :label "Open chapter page"
                         :icon FaArrowUpRightFromSquare
                         :color "indigo"
                         :on-select (fn [_]
                                      (rf/dispatch [:navigate-chapter-page entity-id]))}
                        {:id :upload-images
                         :label "Upload images"
                         :icon FaImages
                         :color "blue"
                         :on-select (fn [_]
                                      (reset! upload-open?* true))})
                  (and (not= "saga" (str entity-label))
                       (pos? empty-frame-count))
                  (conj {:id :delete-empty-frames
                         :label "Delete empty frames"
                         :icon FaBroom
                         :color "orange"
                         :on-select (fn [_]
                                      (reset! confirm* {:title "Delete empty frames?"
                                                        :text (str "This deletes " empty-frame-count
                                                                   " frame" (when (not= 1 empty-frame-count) "s")
                                                                   " without an image in this " label ".")
                                                        :confirm-label "Delete empty frames"
                                                        :confirm-color "error"
                                                        :dispatch-event [:delete-empty-frames entity-id owner-type]}))})
                  true
                  (conj {:id :delete-entity
                         :label (str "Delete " label)
                         :icon FaTrashCan
                         :color "red"
                         :on-select (fn [_]
                                      (reset! confirm* {:title (str "Delete this " label "?")
                                                        :text (if (= "saga" (str entity-label))
                                                                "This deletes all chapters and frames in this saga."
                                                                (str "This deletes all frames in this " label "."))
                                                        :confirm-label (str "Delete " label)
                                                        :confirm-color "error"
                                                        :dispatch-event [(case (str entity-label)
                                                                           "saga" :delete-saga
                                                                           "character" :delete-character
                                                                           :delete-chapter)
                                                                         entity-id]}))}))
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false))
      [:<>
       [waterfall-row/waterfall-row
        {:class-name "chapter-header-actions-row"
         :actions items
         :mandatory-count 0
         :menu-title (str title-case-label " actions")
         :menu-aria-label (str title-case-label " actions")}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]
       (when (= "chapter" (str entity-label))
         [upload-dialog/upload-dialog
          {:open @upload-open?*
           :on-close #(reset! upload-open?* false)
           :on-submit-many #(rf/dispatch [:upload-chapter-images entity-id %])
           :multiple? true
           :title "Upload images"}])])))

(defn collection-section [cfg entity active-frame-id editing-entity-id]
  (let [{:keys [entity-id-key entity-label entity-singular owner-type]} cfg
        entity-id (entity-id-key entity)
        chapter-entity? (= "chapter" (str entity-label))
        chapter-collapsed? (when chapter-entity?
                             @(rf/subscribe [:gallery-chapter-collapsed? entity-id]))
        entity-name (or (:name entity) (:description entity) "")
        entity-description (or (:description entity) "")
        editing? (= editing-entity-id entity-id)
        name-inputs @(rf/subscribe [(case (str entity-label)
                                      "saga" :saga-name-inputs
                                      "character" :character-name-inputs
                                      :chapter-name-inputs)])
        description-inputs @(rf/subscribe [(case (str entity-label)
                                             "saga" :saga-description-inputs
                                             "character" :character-description-inputs
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
           :value entity-name
           :editing? editing?
           :multiline? true
           :class-name "chapter-header-body"
           :display-class-name "chapter-name"
           :editing-class-name "chapter-db-item"
           :input-class-name "chapter-name-input"
           :placeholder (str "Name this " entity-singular "...")
           :min-rows 1
           :max-rows 3
           :on-open-edit #(rf/dispatch [:start-entity-edit entity-label entity-id entity-name entity-description])
           :on-close-edit #(rf/dispatch [:cancel-entity-name-edit entity-label])
           :on-change #(rf/dispatch [:entity-name-input-changed entity-label entity-id %])
           :on-save (fn [next-name]
                      (rf/dispatch [:save-entity entity-label entity-id next-name current-description]))
           :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
         [db-text/db-text
          {:id (str entity-label "-" entity-id "-desc")
           :value entity-description
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
           :on-save (fn [next-description]
                      (rf/dispatch [:save-entity entity-label entity-id current-name next-description]))
           :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
         [entity-actions
          {:entity-id entity-id
           :entity-label entity-label
           :singular-label entity-singular}]]
        (case (str entity-label)
          "saga" [gallery/chapter-preview-gallery entity-id]
          [gallery/frame-gallery entity-id owner-type active-frame-id])])]))

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
  (let [route @(rf/subscribe [:route])]
    (case (:view-id cfg)
      :saga
      [roster-nav-button]

      :roster
      (when-let [back-label (:saga-back-label cfg)]
        [back-button/back-button
         {:label back-label
          :on-click #(rf/dispatch [:navigate-saga-page (:saga-id route)])}])

      nil)))

(defn searchable-text [entity]
  (str/lower-case
   (str (or (:name entity) "")
        "\n"
        (or (:description entity) ""))))

(defn filter-entities [entities search]
  (let [needle (some-> (or search "") str str/trim str/lower-case)]
    (if (str/blank? (or needle ""))
      (vec entities)
      (->> (or entities [])
           (filter (fn [entity]
                     (str/includes? (searchable-text entity) needle)))
           vec))))

(defn paged-entities [entities page per-page]
  (let [safe-page (max 1 (or page 1))
        safe-per-page (max 1 (or per-page 12))
        total (count entities)
        page-count (max 1 (int (js/Math.ceil (/ total safe-per-page))))
        current-page (min safe-page page-count)
        start (* (dec current-page) safe-per-page)]
    {:items (->> entities
                 (drop start)
                 (take safe-per-page)
                 vec)
     :page current-page
     :page-count page-count}))

(defn saga-header-content [saga]
  (let [saga-id (:sagaId saga)
        editing-saga-id @(rf/subscribe [:editing-saga-id])
        name-inputs @(rf/subscribe [:saga-name-inputs])
        description-inputs @(rf/subscribe [:saga-description-inputs])
        current-name (if (= editing-saga-id saga-id)
                       (get name-inputs saga-id (:name saga))
                       (:name saga))
        current-description (if (= editing-saga-id saga-id)
                              (get description-inputs saga-id (:description saga))
                              (:description saga))
        editor-selector "[data-saga-meta-editor='true']"]
    [:div.saga-meta-header
     {:data-saga-meta-editor "true"}
     [db-text/db-text
      {:id "saga-meta-title"
       :value (or current-name "")
       :editing? (= editing-saga-id saga-id)
       :class-name "saga-meta-db-item"
       :display-class-name "chapter-name saga-title saga-meta-text"
       :editing-class-name "chapter-db-item saga-meta-db-item"
       :input-class-name "chapter-name-input saga-title-input saga-meta-input"
       :display-as :h2
       :placeholder "Name this saga..."
       :on-open-edit #(rf/dispatch [:start-entity-edit "saga" saga-id (:name saga) (:description saga)])
       :on-close-edit #(rf/dispatch [:cancel-entity-name-edit "saga"])
       :on-change #(rf/dispatch [:entity-name-input-changed "saga" saga-id %])
       :on-save (fn [next-name]
                  (rf/dispatch [:save-entity "saga" saga-id next-name current-description]))
       :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
     [db-text/db-text
      {:id "saga-meta-desc"
       :value (or current-description "")
       :editing? (= editing-saga-id saga-id)
       :multiline? true
       :class-name "saga-meta-db-item"
       :display-class-name "chapter-description saga-meta-text"
       :editing-class-name "chapter-db-item saga-meta-db-item"
       :input-class-name "chapter-description-input saga-meta-input"
       :display-as :p
       :placeholder ""
       :min-rows 2
       :max-rows 6
       :on-open-edit #(rf/dispatch [:start-entity-edit "saga" saga-id (:name saga) (:description saga)])
       :on-close-edit #(rf/dispatch [:cancel-entity-name-edit "saga"])
       :on-change #(rf/dispatch [:entity-description-input-changed "saga" saga-id %])
       :on-save (fn [next-description]
                  (rf/dispatch [:save-entity "saga" saga-id current-name next-description]))
       :keep-editing-on-blur? #(keep-editor-open? editor-selector)}]
     [:div.saga-header-actions-row
      [page-header-action {:view-id :saga}]]]))

(defn chapter-preview-image-url [frames chapter-id]
  (some->> (or frames [])
           (filter (fn [frame]
                     (and (= "saga" (str (or (:ownerType frame) "saga")))
                          (= (:chapterId frame) chapter-id))))
           (sort-by (fn [frame]
                      [(or (:frameNumber frame) js/Number.MAX_SAFE_INTEGER)
                       (or (:createdAt frame) "")
                       (or (:frameId frame) "")]))
           (keep (fn [frame]
                   (some-> (:imageUrl frame) str/trim not-empty)))
           first))

(defn chapter-search-text [chapter saga frames]
  (str/lower-case
   (str (or (:name chapter) "")
        "\n"
        (or (:description chapter) "")
        "\n"
        (or (:name saga) "")
        "\n"
        (or (:description saga) "")
        "\n"
        (str/join
         "\n"
         (->> (or frames [])
              (filter (fn [frame]
                        (and (= "saga" (str (or (:ownerType frame) "saga")))
                             (= (:chapterId frame) (:chapterId chapter)))))
              (map #(or (:description %) ""))
              (remove str/blank?))))))

(defn index-entries [sagas chapters frames search]
  (let [saga-by-id (into {} (map (fn [saga] [(:sagaId saga) saga]) (or sagas [])))
        saga-entries (map (fn [saga]
                            {:kind :saga
                             :id (:sagaId saga)
                             :name (or (some-> (:name saga) str/trim not-empty) "Saga")
                             :description (or (:description saga) "")
                             :search-text (searchable-text saga)
                             :sort-key [0
                                        (or (:sagaNumber saga) js/Number.MAX_SAFE_INTEGER)
                                        0
                                        (or (:createdAt saga) "")
                                        (or (:sagaId saga) "")]})
                          (or sagas []))
        chapter-entries (map (fn [chapter]
                               (let [saga (get saga-by-id (:sagaId chapter))]
                                 {:kind :chapter
                                  :id (:chapterId chapter)
                                  :saga-id (:sagaId chapter)
                                  :saga-name (or (some-> saga :name str/trim not-empty) "Saga")
                                  :name (or (some-> (:name chapter) str/trim not-empty) "Chapter")
                                  :description (or (:description chapter) "")
                                  :image-url (chapter-preview-image-url frames (:chapterId chapter))
                                  :search-text (chapter-search-text chapter saga frames)
                                  :sort-key [1
                                             (or (:sagaNumber saga) js/Number.MAX_SAFE_INTEGER)
                                             (or (:chapterNumber chapter) js/Number.MAX_SAFE_INTEGER)
                                             (or (:createdAt chapter) "")
                                             (or (:chapterId chapter) "")]}))
                             (or chapters []))
        entries (->> (concat saga-entries chapter-entries)
                     (sort-by :sort-key)
                     vec)
        needle (some-> (or search "") str str/trim str/lower-case)]
    (if (str/blank? (or needle ""))
      entries
      (->> entries
           (filter (fn [entry]
                     (str/includes? (:search-text entry) needle)))
           vec))))

(defn index-card [entry]
  (let [{:keys [kind id saga-id saga-name name description image-url]} entry
        chapter? (= :chapter kind)
        click-handler (fn []
                        (if chapter?
                          (rf/dispatch [:navigate-chapter-page id])
                          (rf/dispatch [:navigate-saga-page id])))]
    [:article
     {:className (str "index-card" (when chapter? " is-chapter"))
      :role "button"
      :tabIndex 0
      :onClick click-handler
      :onKeyDown (fn [e]
                   (when (or (= "Enter" (.-key e))
                             (= " " (.-key e)))
                     (interaction/prevent! e)
                     (click-handler)))}
     [:div.index-card-media
      (if (and chapter? (seq (or image-url "")))
        [:img {:className "index-card-image"
               :src image-url
               :alt (str name " preview")}]
        [:div.index-card-placeholder])]
     [:div.index-card-body
      [:div.index-card-topline
       [:span.index-card-kind (if chapter? "Chapter" "Saga")]
       (when chapter?
         [:span.index-card-saga saga-name])]
      [:h3.index-card-title name]
      (when (seq (str/trim (or description "")))
        [:p.index-card-description description])]
     [:div.index-card-actions
      [entity-actions
       {:entity-id id
        :entity-label (if chapter? "chapter" "saga")
        :singular-label (if chapter? "chapter" "saga")}]]]))

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
      (let [{:keys [view-id page-title page-class editing-id-sub entity-id-key search-placeholder empty-label header-saga]} cfg
          editing-entity-id @(rf/subscribe editing-id-sub)
          search @(rf/subscribe [:collection-search view-id])
          current-page @(rf/subscribe [:collection-page view-id])
          per-page @(rf/subscribe [:collection-per-page view-id])
          gallery-items @(rf/subscribe [:gallery-items])
          any-frame-actions-open? @(rf/subscribe [:any-frame-actions-open?])
          current-gallery-chapter-id (some (fn [frame]
                                             (when (= (:frameId frame) active-frame-id)
                                               (:chapterId frame)))
                                           gallery-items)
          filtered-entities (filter-entities entities search)
          {:keys [items page page-count]} (paged-entities filtered-entities current-page per-page)
          any-edit-open? (or panel-open?
                             (some? editing-entity-id)
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
        (if (and (= :saga view-id) header-saga)
          [saga-header-content header-saga]
          [:<>
           [:h2 page-title]
           [page-header-action cfg]])]
       [:> TextInput
        {:value (or search "")
         :placeholder search-placeholder
         :className "new-chapter-input"
         :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
       (if (seq items)
         (map-indexed (fn [idx entity]
                      ^{:key (or (entity-id-key entity) (str "entity-" idx))}
                      [collection-section cfg entity active-frame-id editing-entity-id])
                    items)
         [:p.chapter-description empty-label])
       (when (> page-count 1)
         [:> Pagination
          {:value page
           :total page-count
           :onChange #(rf/dispatch [:collection-page-selected view-id %])}])
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
   :teaser-sub "Click to start a new adventure"
   :search-placeholder "Search chapters..."
   :empty-label "No chapters match this search."})

(defn index-page [sagas chapters frames new-saga-name new-saga-description new-saga-panel-open?]
  (let [search @(rf/subscribe [:collection-search :index])
        current-page @(rf/subscribe [:collection-page :index])
        per-page @(rf/subscribe [:collection-per-page :index])
        entries (index-entries sagas chapters frames search)
        {:keys [items page page-count]} (paged-entities entries current-page per-page)]
    [:> Stack {:component "section"
               :className "saga-page index-page"
               :gap "md"}
     [:> Group {:className "collection-header"
                :justify "center"
                :align "center"}
      [:h2 "Index"]]
     [:> TextInput
      {:value (or search "")
       :placeholder "Search sagas, chapters, and subtitles..."
       :className "new-chapter-input"
       :onChange #(rf/dispatch [:collection-search-changed :index (.. % -target -value)])}]
     (if (seq items)
       [:section.index-grid
        (map-indexed (fn [idx entry]
                       ^{:key (or (:id entry) (str "index-entry-" idx))}
                       [index-card entry])
                     items)]
       [:p.chapter-description "No sagas or chapters match this search."])
     (when (> page-count 1)
       [:> Pagination
        {:value page
         :total page-count
         :onChange #(rf/dispatch [:collection-page-selected :index %])}])
     [:section.collection-add-region
      (if new-saga-panel-open?
        [new-entity-form {:add-event :add-saga
                          :set-open-event :set-new-saga-panel-open
                          :add-title "Create New Saga"
                          :name-input-placeholder "Name this saga..."
                          :description-input-placeholder "Describe the story, tone, or setup..."
                          :name-changed-event :new-saga-name-changed
                          :description-changed-event :new-saga-description-changed}
         new-saga-name
         new-saga-description]
        [new-entity-teaser {:set-open-event :set-new-saga-panel-open
                            :teaser-title "Create New Saga"
                            :teaser-sub "Start a new saga"}
         nil])]]))

(defn saga-page [selected-saga chapters active-frame-id new-chapter-name new-chapter-description new-chapter-panel-open? show-chapter-celebration?]
  [collection-page (assoc saga-config
                          :page-title (or (:name selected-saga) "Saga")
                          :header-saga selected-saga)
   chapters
   active-frame-id
   {:name new-chapter-name
    :description new-chapter-description}
   new-chapter-panel-open?
   show-chapter-celebration?])
