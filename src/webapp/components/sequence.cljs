(ns webapp.components.sequence
  "Generic Sequence component - renders a collection of child entities.
   A Sequence is any entity with children (saga, chapter, character, roster, etc).
   Styling is applied via vanityRole CSS classes."
  (:refer-clojure :exclude [sequence])
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.frame :as frame]
            [webapp.components.item :as item]
            [webapp.components.db-text :as db-text]
            [webapp.components.roster-button :as roster-button]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.roster-select-dialog :as roster-select-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.shared.model :as model]
            ["react-icons/fa6" :refer [FaBroom FaDownload FaImages FaPlus FaTrashCan]]
            ["@mantine/core" :refer [Box]]))

(defn- seeded-unit [seed n]
  "Deterministic pseudo-random value based on seed and index."
  (let [x (* (+ seed (* 97 n)) 12.9898)
        s (js/Math.sin x)]
    (- (* (- s (js/Math.floor s)) 2) 1)))

(defn- gallery-motion-style
  "Generate deterministic animation values for gallery item based on entity ID."
  [seed-key]
  (let [seed (reduce (fn [acc ch] (+ acc (int ch))) 0 (str (or seed-key "")))
        y-scale (+ 0.28 (* 0.96 (js/Math.abs (seeded-unit seed 1))))
        x-scale (+ 0.22 (* 0.84 (js/Math.abs (seeded-unit seed 2))))
        rot-scale (* (+ 0.2 (* 0.56 (js/Math.abs (seeded-unit seed 3))))
                     (if (neg? (seeded-unit seed 4)) -1 1))
        float-offset (* (+ 4.0 (* 18.0 (js/Math.abs (seeded-unit seed 5))))
                        (if (neg? (seeded-unit seed 6)) -1 1))
        settle-ms (+ 30 (js/Math.floor (* 240 (js/Math.abs (seeded-unit seed 7)))))
        x-bias (* 0.34 (seeded-unit seed 8))
        y-bias (* 0.4 (seeded-unit seed 9))
        rot-bias (* 0.26 (seeded-unit seed 10))]
    #js {"--gallery-motion-y-scale" y-scale
         "--gallery-motion-x-scale" x-scale
         "--gallery-motion-y-bias" y-bias
         "--gallery-motion-x-bias" x-bias
         "--gallery-motion-rot-scale" rot-scale
         "--gallery-motion-rot-bias" rot-bias
         "--gallery-motion-float-x" (str float-offset "px")
         "--gallery-motion-pointer-weight" "1"
         "--gallery-motion-duration" (str settle-ms "ms")}))

(declare sequence)

(defn- confirm-item [id title text confirm-label dispatch-event]
  {:id id
   :confirm {:title title
             :text text
             :confirm-label confirm-label
             :confirm-color "error"}
   :dispatch-event dispatch-event})

(defn sequence-actions [entity]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)
               upload-open?* (r/atom false)]
    (let [entity-id (:id entity)
          role (some-> (:vanityRole entity) str str/lower-case)
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          roster-link-state @(rf/subscribe [:roster-link-state])
          rosters @(rf/subscribe [:rosters])
          owner-type (if (= role "character") "character" "saga")
          frames (when (not= role "saga")
                   @(rf/subscribe [:frames-for-owner owner-type entity-id]))
          empty-frames (filterv (fn [frame]
                                  (str/blank? (or (:imageUrl frame) "")))
                                (or frames []))
          empty-frame-count (count empty-frames)
          frame-sequence? (contains? #{"chapter" "character"} role)
          label (case role
                  "saga" "saga"
                  "chapter" "chapter"
                  "character" "character"
                  "sequence")
          title-case-label (str/capitalize label)
          roster-target (:target roster-link-state)
          roster-search (some-> (:search roster-link-state) str str/lower-case str/trim)
          roster-dialog-open? (and (= role "chapter")
                                   (:open? roster-link-state)
                                   (= entity-id (:chapter-id roster-target)))
          filtered-rosters (if (str/blank? (or roster-search ""))
                             rosters
                             (filterv (fn [roster]
                                        (let [title (some-> (:title roster) str str/lower-case)
                                              description (some-> (:description roster) str str/lower-case)]
                                          (or (str/includes? (or title "") roster-search)
                                              (str/includes? (or description "") roster-search))))
                                      rosters))
          roster-items (mapv (fn [roster]
                               ^{:key (:id roster)}
                               [roster-button/roster-button
                                {:label (model/primary-label roster)
                                 :description (some-> (:description roster) str/trim not-empty)
                                 :class-name "roster-select-option"
                                 :on-click #(rf/dispatch [:select-roster-link (:id roster)])}])
                             filtered-rosters)
          items [{:id :download-sequence
                  :label (str "Download " label)
                  :icon FaDownload
                  :color "teal"
                  :disabled? true}
                 {:id :delete-sequence
                  :label (str "Delete " label)
                  :icon FaTrashCan
                  :color "red"
                  :on-select (fn [_]
                               (reset! confirm* (confirm-item
                                                 :delete-sequence
                                                 (str "Delete this " label "?")
                                                 (if (= role "saga")
                                                   "This deletes all child sequences and frames in this saga."
                                                   (str "This deletes all frames in this " label "."))
                                                 (str "Delete " label)
                                                 [(case role
                                                    "saga" :delete-saga
                                                    "character" :delete-character
                                                    :delete-chapter)
                                                  entity-id])))}
                 {:id :link-sequence
                  :label "Link"
                  :icon FaPlus
                  :color "grape"
                  :disabled? (not= role "chapter")
                  :on-select (fn [_]
                               (when (= role "chapter")
                                 (rf/dispatch [:add-linked-chapter-roster entity-id])))}
                 {:id :upload-images
                  :label "Upload images"
                  :icon FaImages
                  :color "blue"
                  :disabled? (not= role "chapter")
                  :on-select (fn [_]
                               (when (= role "chapter")
                                 (reset! upload-open?* true)))}
                 {:id :delete-empty-frames
                  :label "Delete empty frames"
                  :icon FaBroom
                  :color "orange"
                  :disabled? (or (not frame-sequence?)
                                 (zero? empty-frame-count))
                 :on-select (fn [_]
                               (when (and frame-sequence? (pos? empty-frame-count))
                                 (reset! confirm* (confirm-item
                                                   :delete-empty-frames
                                                   "Delete empty frames?"
                                                   (str "This deletes " empty-frame-count
                                                        " frame" (when (not= 1 empty-frame-count) "s")
                                                        " without an image in this " label ".")
                                                   "Delete empty frames"
                                                   [:delete-empty-frames entity-id owner-type]))))}]
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false))
      [:<>
       [waterfall-row/waterfall-row
        {:class-name "chapter-header-actions-row"
         :actions items
         :action-size 44
         :mandatory-count 2
         :menu-title (str title-case-label " actions")
         :menu-aria-label (str title-case-label " actions")}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]
       (when (= role "chapter")
         [upload-dialog/upload-dialog
          {:open @upload-open?*
           :on-close #(reset! upload-open?* false)
           :on-submit-many #(rf/dispatch [:upload-chapter-images entity-id %])
           :multiple? true
           :title "Upload images"}])
       [roster-select-dialog/roster-select-dialog
        {:open roster-dialog-open?
         :title "Select roster"
         :search (:search roster-link-state)
         :on-search #(rf/dispatch [:roster-link-search-changed %])
         :on-close #(rf/dispatch [:close-roster-link-dialog])
         :on-create #(rf/dispatch [:create-roster-link])
         :items roster-items
         :empty-label "No rosters match this search."}]])))

(defn sequence-gallery
  "Render a grid of child entities (items or sequences).
   
   Props:
   - entity-id: Parent entity ID
   - children-ids: Array of child entity IDs
   - child-data-fn: (child-id) -> entity map for rendering each child
   - add-child-label: Text for 'add new' tile (optional)
   - add-child-fn: () -> dispatch action to add child (optional)
   - child-options-fn: (child-entity) -> options map for rendering (optional)"
  [{:keys [entity-id role children-ids child-data-fn add-child-label add-child-fn child-options-fn]}]
  (let [children-data (map child-data-fn children-ids)
        get-child-options (or child-options-fn (constantly {}))
        frame-sequence? (and (seq children-data)
                             (every? model/frame-entity? children-data))
        active-frame-id (when frame-sequence?
                          @(rf/subscribe [:active-frame-id]))
        add-tile-title (or add-child-label
                           (if (= role "character")
                             "Add"
                             "Add"))
        add-tile-subtitle (if (= role "character")
                            "Create the next image for this character"
                            "Create the next frame in this sequence")]
    [:> Box {:className "gallery sequence-gallery-grid"}
     (map-indexed
       (fn [idx child-entity]
         ^{:key (or (:id child-entity) (str "child-" idx))}
         [:div.gallery-motion-item
          {:style (gallery-motion-style (:id child-entity))}
          (if (seq (:children child-entity))
            [sequence child-entity (get-child-options child-entity)]
            (if frame-sequence?
              [frame/frame (model/frame-row child-entity)
               (merge {:active? (= active-frame-id (:id child-entity))}
                      (get-child-options child-entity))]
              [item/item child-entity (get-child-options child-entity)]))])
       children-data)
     
     (when add-child-fn
       [:div.gallery-motion-item.add-frame-slot
        {:style (gallery-motion-style (str entity-id "-add-tile"))}
        [:article.add-frame-tile
         {:className "frame frame-clickable add-frame-tile"
          :role "button"
          :tabIndex 0
          :aria-label add-tile-title
          :onClick add-child-fn
          :onKeyDown (fn [e]
                       (when (or (= "Enter" (.-key e))
                                 (= " " (.-key e)))
                         (.preventDefault e)
                         (add-child-fn)))}
         [:div.add-frame-tile-title add-tile-title]
         [:div.add-frame-tile-sub add-tile-subtitle]]])]))

(defn sequence-description-editor
  "Editable title and description fields with actions."
  [entity options title-editing-atom description-editing-atom]
  (let [{:keys [on-save-title on-save-description show-actions?]} options
        show-actions? (not= false show-actions?)
        title (or (:title entity) "")
        description (or (:description entity) "")]
    [:div.chapter-header
     [:div.chapter-header-main
      [:div.chapter-header-copy
       [db-text/db-text
        {:id (str (:id entity) "-title")
         :value title
         :editing? @title-editing-atom
         :multiline? false
         :class-name "chapter-header-body"
         :display-class-name "chapter-name"
         :editing-class-name "chapter-db-item"
         :input-class-name "chapter-name-input"
         :placeholder "Sequence title..."
         :on-open-edit #(reset! title-editing-atom true)
         :on-close-edit #(reset! title-editing-atom false)
         :on-save (when on-save-title
                    (fn [text]
                      (reset! title-editing-atom false)
                      (on-save-title text)))}]
       [db-text/db-text
        {:id (str (:id entity) "-description")
         :value description
         :editing? @description-editing-atom
         :multiline? true
         :class-name "chapter-header-body"
         :display-class-name "chapter-description"
         :editing-class-name "chapter-db-item"
         :input-class-name "chapter-description-input"
         :placeholder "Add description..."
         :max-chars 500
         :min-rows 2
         :max-rows 8
         :on-open-edit #(reset! description-editing-atom true)
         :on-close-edit #(reset! description-editing-atom false)
         :on-save (when on-save-description
                    (fn [text]
                      (reset! description-editing-atom false)
                      (on-save-description text)))}]]
      (when show-actions?
        [:div.chapter-header-controls
         [sequence-actions entity]])]]))

(defn sequence
  "Generic sequence component for rendering a collection entity.
   
   Entity:
   - :id UUID
   - :title Title text
   - :description Description text
   - :children [child-ids] Array of child entity IDs
   - :vanityRole Type hint (\"saga\", \"chapter\", \"character\", \"roster\") for styling
   - :payload Map with flexible fields
   
   Options:
   - :children-fetcher (child-id) -> entity map to render each child
   - :add-child-fn () -> dispatch to create child
   - :add-child-label Text for add child tile
   - :on-save-title (text) -> handler for title save
   - :on-save-description (text) -> handler for description save
   - :on-delete () -> handler for delete
   - :on-click-edit () -> called when entering edit mode"
  ([entity]
   [sequence entity {}])
  ([{:keys [id title description children vanityRole payload]} options]
   (r/with-let [title-editing-atom (r/atom false)
                description-editing-atom (r/atom false)]
     (let [children-ids (or children [])
           child-data-fn (or (:children-fetcher options) (constantly {}))
           child-options-fn (:child-options-fn options)
           add-child-label (:add-child-label options)
           add-child-fn (:add-child-fn options)]
       [:div.sequence
        {:className (str "sequence-" (str/lower-case (or vanityRole "generic")))}
        
        [:div.sequence-header-container
         [sequence-description-editor 
          {:id id
           :title title
           :description description
           :vanityRole vanityRole
           :children children
           :payload payload}
          options
          title-editing-atom
          description-editing-atom]]
        
       (when (seq children-ids)
          [sequence-gallery
           {:entity-id id
            :role (some-> vanityRole str str/lower-case)
            :children-ids children-ids
            :child-data-fn child-data-fn
            :child-options-fn child-options-fn
            :add-child-label add-child-label
            :add-child-fn add-child-fn}])]))))
