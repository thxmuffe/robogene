(ns webapp.components.sequence
  "Generic Sequence component - renders a collection of child entities.
   A Sequence is any entity with children (saga, chapter, character, roster, etc).
   Styling is applied via vanityRole CSS classes."
  (:refer-clojure :exclude [sequence])
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.item :as item]
            [webapp.components.db-text :as db-text]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.shared.ui.interaction :as interaction]
            ["react-icons/fa6" :refer [FaCamera FaDownload FaEraser FaTrashCan FaWandMagic FaWandMagicSparkles]]
            ["@mantine/core" :refer [Box Card]]))

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

(defn sequence-gallery
  "Render a grid of child entities (items or sequences).
   
   Props:
   - entity-id: Parent entity ID
   - children-ids: Array of child entity IDs
   - child-data-fn: (child-id) -> entity map for rendering each child
   - add-child-label: Text for 'add new' tile (optional)
   - add-child-fn: () -> dispatch action to add child (optional)
   - child-options-fn: (child-entity) -> options map for rendering (optional)"
  [{:keys [entity-id children-ids child-data-fn add-child-label add-child-fn child-options-fn]}]
  (let [children-data (map child-data-fn children-ids)
        get-child-options (or child-options-fn (constantly {}))]
    [:> Box {:className "gallery"}
     (map-indexed
       (fn [idx child-entity]
         ^{:key (or (:id child-entity) (str "child-" idx))}
         [:div.gallery-motion-item
          {:style (gallery-motion-style (:id child-entity))}
          (if (seq (:children child-entity))
            [sequence child-entity (get-child-options child-entity)]
            [item/item child-entity (get-child-options child-entity)])])
       children-data)
     
     (when add-child-fn
       [:div.gallery-motion-item
        {:style (gallery-motion-style (str entity-id "-add-tile"))}
        [:article.add-frame-tile
         {:className "frame frame-clickable add-frame-tile"
          :role "button"
          :tabIndex 0
          :aria-label (or add-child-label "Add item")
          :onClick add-child-fn
          :onKeyDown (fn [e]
                       (when (or (= "Enter" (.-key e))
                                 (= " " (.-key e)))
                         (.preventDefault e)
                         (add-child-fn)))}
         [:div.add-frame-tile-title (or add-child-label "Add item")]
         [:div.add-frame-tile-sub "Create new item in this sequence"]]])]))

(defn sequence-description-editor
  "Editable title and description fields with actions."
  [entity options editing-atom]
  (let [{:keys [on-save-title on-save-description]} options
        title (or (:title entity) "")
        description (or (:description entity) "")]
    [:div.chapter-header
     [db-text/db-text
      {:id (str (:id entity) "-title")
       :value title
       :editing? @editing-atom
       :multiline? false
       :class-name "chapter-header-body"
       :display-class-name "chapter-name"
       :editing-class-name "chapter-db-item"
       :input-class-name "chapter-name-input"
       :placeholder "Sequence title..."
       :on-open-edit #(reset! editing-atom true)
       :on-close-edit #(reset! editing-atom false)
       :on-save (when on-save-title
                  (fn [text]
                    (reset! editing-atom false)
                    (on-save-title text)))}]
     
     [db-text/db-text
      {:id (str (:id entity) "-description")
       :value description
       :editing? @editing-atom
       :multiline? true
       :class-name "chapter-header-body"
       :display-class-name "chapter-description"
       :editing-class-name "chapter-db-item"
       :input-class-name "chapter-description-input"
       :placeholder "Add description..."
       :max-chars 500
       :min-rows 2
       :max-rows 8
       :on-open-edit #(reset! editing-atom true)
       :on-close-edit #(reset! editing-atom false)
       :on-save (when on-save-description
                  (fn [text]
                    (reset! editing-atom false)
                    (on-save-description text)))}]
     
     (when @editing-atom
       [:div.chapter-header-controls
        [waterfall-row/waterfall-row
         {:class-name "chapter-header-actions-row"
          :actions (vec (filter
                          some?
                          [{:id "delete" :label "Delete" :icon FaTrashCan :color "red" :on-select (:on-delete options)}]))
          :mandatory-count 1}]])]))

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
   (r/with-let [editing-atom (r/atom false)]
     (let [children-ids (or children [])
           child-data-fn (or (:children-fetcher options) (constantly {}))
           child-options-fn (:child-options-fn options)
           add-child-label (:add-child-label options)
           add-child-fn (:add-child-fn options)]
       [:div.sequence
        {:className (str "sequence-" (str/lower-case (or vanityRole "generic")))}
        
        [:div.sequence-header-container
         [sequence-description-editor 
          {:id id :title title :description description}
          options
          editing-atom]]
        
        (when (seq children-ids)
          [sequence-gallery
           {:entity-id id
            :children-ids children-ids
            :child-data-fn child-data-fn
            :child-options-fn child-options-fn
            :add-child-label add-child-label
            :add-child-fn add-child-fn}])]))))
