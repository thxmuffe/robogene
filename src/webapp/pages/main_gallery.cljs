(ns webapp.pages.main-gallery
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.components.chapter :as chapter-component]
            [webapp.components.chapter-actions :as chapter-actions]
            ["@mantine/core" :refer [ActionIcon Box Button Group Stack TextInput Textarea]]
            ["react-icons/fa6" :refer [FaArrowLeft FaCheck FaGear FaXmark]]))

(defn next-frame-id-for-key [active-frame-id key]
  (case key
    "ArrowLeft" (frame-nav/adjacent-frame-id active-frame-id -1)
    "ArrowRight" (frame-nav/adjacent-frame-id active-frame-id 1)
    "ArrowUp" (frame-nav/nearest-vertical-frame-id active-frame-id :up)
    "ArrowDown" (frame-nav/nearest-vertical-frame-id active-frame-id :down)
    nil))

(defn on-gallery-key-down [active-frame-id e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
    (when-not (or (interaction/modal-open?)
                  (interaction/menu-open?)
                  (interaction/editable-target? (.-target e)))
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

        :else nil))))

(defn on-name-keydown [entity-label entity-id]
  (fn [e]
    (when (= "Enter" (.-key e))
      (interaction/prevent! e)
      (rf/dispatch [:save-entity-name entity-label entity-id]))))

(defn on-new-item-submit [add-event set-open-event]
  (fn [e]
    (interaction/prevent! e)
    (rf/dispatch [set-open-event false])
    (rf/dispatch [add-event])))

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

(defn collection-section [cfg entity frame-inputs open-frame-actions active-frame-id editing-entity-id entity-name-inputs]
  (let [entity-id ((:entity-id-key cfg) entity)
        entity-name (:description entity)]
    [:> Box {:component "section" :className "chapter-block"}
     [:div.chapter-separator]
     [:> Group {:className "chapter-header"
                :gap "sm"
                :align "center"
                :wrap "wrap"}
      (if (= editing-entity-id entity-id)
        [:<>
         [:> TextInput
          {:size "sm"
           :value (get entity-name-inputs entity-id "")
           :onKeyDown (on-name-keydown (:entity-label cfg) entity-id)
           :onChange #(rf/dispatch [:entity-name-input-changed (:entity-label cfg) entity-id (.. % -target -value)])}]
         [:> ActionIcon
          {:aria-label "Save name"
           :title "Save name"
           :variant "filled"
           :radius "xl"
           :onClick #(rf/dispatch [:save-entity-name (:entity-label cfg) entity-id])}
          [:> FaCheck]]
         [:> ActionIcon
          {:aria-label "Cancel name editing"
           :title "Cancel name editing"
           :variant "subtle"
           :radius "xl"
           :onClick #(rf/dispatch [:cancel-entity-name-edit (:entity-label cfg)])}
          [:> FaXmark]]]
        [:p.chapter-description entity-name])
      [chapter-actions/chapter-actions
       {:entity-id entity-id
        :entity-name entity-name
        :entity-label (:entity-label cfg)
        :singular-label (:entity-singular cfg)}]]
     [chapter-component/chapter entity-id (:owner-type cfg) frame-inputs open-frame-actions active-frame-id]]))

(defn new-entity-form [cfg description]
  [:> Box {:component "form"
           :className "new-chapter-panel"
           :onSubmit (on-new-item-submit (:add-event cfg) (:set-open-event cfg))}
   [:h3 (:add-title cfg)]
   [:> ActionIcon
    {:className "new-chapter-close"
     :aria-label "Close"
     :variant "transparent"
     :onClick #(rf/dispatch [(:set-open-event cfg) false])}
    [:> FaXmark]]
   [:label.dir-label {:for (:input-id cfg)} (:input-label cfg)]
   [:> Textarea
    {:id (:input-id cfg)
     :autosize true
     :minRows 3
     :maxRows 10
     :className "new-chapter-input"
     :value (or description "")
     :placeholder (:input-placeholder cfg)
     :onChange #(rf/dispatch [(:description-changed-event cfg) (.. % -target -value)])}]
   [:> Button
    {:className "new-chapter-submit"
     :type "submit"
     :variant "filled"
     :color "orange"}
    (:add-submit-label cfg)]])

(defn new-entity-teaser [cfg active-frame-id]
  [:article.new-chapter-teaser
   {:class (str "frame frame-clickable add-frame-tile"
                (when (= active-frame-id controls/new-chapter-frame-id)
                  " frame-active"))
    :data-frame-id controls/new-chapter-frame-id
    :role "button"
    :tab-index 0
    :on-mouse-enter (controls/on-frame-activate controls/new-chapter-frame-id)
    :on-focus (controls/on-frame-activate controls/new-chapter-frame-id)
    :on-click (on-new-item-teaser-click (:set-open-event cfg))
    :on-key-down (on-new-item-teaser-keydown (:set-open-event cfg))}
   [:div.add-frame-tile-title (:teaser-title cfg)]
   [:div.add-frame-tile-sub (:teaser-sub cfg)]])

(defn collection-page [cfg entities frame-inputs open-frame-actions active-frame-id form-description panel-open? show-celebration?]
  (r/with-let [context* (r/atom {:active-frame-id nil})
               focused-active-id* (r/atom nil)
               key-handler (fn [e]
                             (on-gallery-key-down (:active-frame-id @context*) e))]
    (.addEventListener js/window "keydown" key-handler)
    (reset! context* {:active-frame-id active-frame-id})
    (when (not= active-frame-id @focused-active-id*)
      (reset! focused-active-id* active-frame-id)
      (when active-frame-id
        (.requestAnimationFrame js/window
                                (fn []
                                  (frame-nav/focus-subtitle! active-frame-id)))))
    (let [editing-entity-id @(rf/subscribe (:editing-id-sub cfg))
          entity-name-inputs @(rf/subscribe (:name-inputs-sub cfg))]
      [:> Stack {:component "section"
                 :gap "md"}
       [:> Group {:justify "space-between" :align "center"}
        [:h2 (:page-title cfg)]
        (cond
          (= :saga (:view-id cfg))
          [:> ActionIcon
           {:aria-label "Open character settings"
            :title "Open character settings"
            :variant "filled"
            :radius "xl"
            :onClick #(rf/dispatch [:navigate-characters-page])}
           [:> FaGear]]

          (= :characters (:view-id cfg))
          [:> Button
           {:variant "default"
            :size "sm"
            :leftSection (r/as-element [:> FaArrowLeft])
            :onClick #(rf/dispatch [:navigate-saga-page])}
           "Back to Saga_page"]

          :else nil)]
       (map-indexed (fn [idx entity]
                      ^{:key (or ((:entity-id-key cfg) entity) (str "entity-" idx))}
                      [collection-section cfg entity frame-inputs open-frame-actions active-frame-id editing-entity-id entity-name-inputs])
                    entities)
       (when (and show-celebration? (= :saga (:view-id cfg)))
         [chapter-celebration])
       (if panel-open?
         [new-entity-form cfg form-description]
         [new-entity-teaser cfg active-frame-id])])
    (finally
      (.removeEventListener js/window "keydown" key-handler))))

(def saga-config
  {:view-id :saga
   :entity-label "chapter"
   :entity-singular "chapter"
   :entity-id-key :chapterId
   :owner-type "saga"
   :page-title "Saga_page"
   :editing-id-sub [:editing-chapter-id]
   :name-inputs-sub [:chapter-name-inputs]
   :description-changed-event :new-chapter-description-changed
   :set-open-event :set-new-chapter-panel-open
   :add-event :add-chapter
   :input-id "new-chapter-description"
   :input-label "Chapter Theme"
   :input-placeholder "Describe the next chapter theme..."
   :add-title "Add New Chapter"
   :add-submit-label "Add New Chapter"
   :teaser-title "Add New Chapter"
   :teaser-sub "Click to start a new adventure"})

(defn saga-page [saga frame-inputs open-frame-actions active-frame-id new-chapter-description new-chapter-panel-open? show-chapter-celebration?]
  [collection-page saga-config
   saga
   frame-inputs
   open-frame-actions
   active-frame-id
   new-chapter-description
   new-chapter-panel-open?
   show-chapter-celebration?])
