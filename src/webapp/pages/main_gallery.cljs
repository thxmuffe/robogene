(ns webapp.pages.main-gallery
  (:require [re-frame.core :as rf]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.chapter :as chapter-component]
            [webapp.components.chapter-actions :as chapter-actions]
            ["@mantine/core" :refer [ActionIcon Box Button Group Stack TextInput Textarea]]
            ["react-icons/fa6" :refer [FaCheck FaXmark]]))

(defn submit-new-chapter! []
  (rf/dispatch [:add-chapter]))

(defn on-new-chapter-submit [e]
  (interaction/prevent! e)
  (rf/dispatch [:set-new-chapter-panel-open false])
  (submit-new-chapter!))

(defn on-new-chapter-teaser-click [_]
  (controls/open-new-chapter-panel!))

(defn on-new-chapter-teaser-keydown [e]
  (when (or (= "Enter" (.-key e))
            (= " " (.-key e)))
    (interaction/prevent! e)
    (controls/open-new-chapter-panel!)))

(defn on-chapter-name-keydown [chapter-id]
  (fn [e]
    (when (= "Enter" (.-key e))
      (interaction/prevent! e)
      (rf/dispatch [:save-chapter-name chapter-id]))))

(defn chapter-section [chapter frame-inputs open-frame-actions active-frame-id editing-chapter-id chapter-name-inputs]
  [:> Box {:component "section" :className "chapter-block"}
   [:div.chapter-separator]
   [:> Group {:className "chapter-header"
              :gap "sm"
              :align "center"
              :wrap "wrap"}
    (if (= editing-chapter-id (:chapterId chapter))
      [:<>
       [:> TextInput
        {:size "sm"
         :value (get chapter-name-inputs (:chapterId chapter) "")
         :onKeyDown (on-chapter-name-keydown (:chapterId chapter))
         :onChange #(rf/dispatch [:chapter-name-input-changed (:chapterId chapter) (.. % -target -value)])}]
       [:> ActionIcon
        {:aria-label "Save chapter name"
         :title "Save chapter name"
         :variant "filled"
         :radius "xl"
         :onClick #(rf/dispatch [:save-chapter-name (:chapterId chapter)])}
        [:> FaCheck]]
       [:> ActionIcon
        {:aria-label "Cancel chapter name editing"
         :title "Cancel chapter name editing"
         :variant "subtle"
         :radius "xl"
         :onClick #(rf/dispatch [:cancel-chapter-name-edit])}
        [:> FaXmark]]]
      [:> Button
       {:variant "default"
        :size "sm"}
       (:description chapter)])
    [chapter-actions/chapter-actions
     {:chapter-id (:chapterId chapter)
      :chapter-name (:description chapter)}]
    [:> Button
     {:variant "filled"
      :size "sm"
      :onClick #(rf/dispatch [:add-frame (:chapterId chapter)])}
     "Add New Frame"]]
   [chapter-component/chapter (:chapterId chapter) frame-inputs open-frame-actions active-frame-id]])

(defn new-chapter-form [description]
  [:> Box {:component "form"
           :className "new-chapter-panel"
           :onSubmit on-new-chapter-submit}
   [:h3 "Add New Chapter"]
   [:> ActionIcon
    {:className "new-chapter-close"
     :aria-label "Close"
     :variant "transparent"
     :onClick #(rf/dispatch [:set-new-chapter-panel-open false])}
    [:> FaXmark]]
   [:label.dir-label {:for "new-chapter-description"} "Chapter Theme"]
   [:> Textarea
    {:id "new-chapter-description"
     :autosize true
     :minRows 3
     :maxRows 10
     :className "new-chapter-input"
     :value (or description "")
     :placeholder "Describe the next chapter theme..."
     :onChange #(rf/dispatch [:new-chapter-description-changed (.. % -target -value)])}]
   [:> Button
    {:className "new-chapter-submit"
     :type "submit"
     :variant "filled"
     :color "orange"}
    "Add New Chapter"]])

(defn new-chapter-teaser [active-frame-id]
  [:article.new-chapter-teaser
   {:class (str "frame frame-clickable"
                (when (= active-frame-id controls/new-chapter-frame-id)
                  " frame-active"))
    :data-frame-id controls/new-chapter-frame-id
    :role "button"
    :tab-index 0
    :on-mouse-enter (controls/on-frame-activate controls/new-chapter-frame-id)
    :on-focus (controls/on-frame-activate controls/new-chapter-frame-id)
    :on-click on-new-chapter-teaser-click
    :on-key-down on-new-chapter-teaser-keydown}
   [:div.sparkles]
   [:div.teaser-content
    [:div.teaser-title "Add New Chapter"]
    [:div.teaser-sub "Click to start a new adventure"]]])

(defn chapter-celebration []
  [:div.chapter-celebration
   [:div.rainbow-band.band-1]
   [:div.rainbow-band.band-2]
   [:div.rainbow-band.band-3]
   [:div.rainbow-band.band-4]
   [:div.rainbow-stars "✦ ✧ ✦ ✧ ✦"]])

(defn main-gallery-page [saga frame-inputs open-frame-actions active-frame-id new-chapter-description new-chapter-panel-open? show-chapter-celebration?]
  (let [editing-chapter-id @(rf/subscribe [:editing-chapter-id])
        chapter-name-inputs @(rf/subscribe [:chapter-name-inputs])]
    [:> Stack {:component "section" :gap "md"}
     [:h2 "Saga"]
     (map-indexed (fn [idx chapter]
                    ^{:key (or (:chapterId chapter) (str "chapter-" idx))}
                    [chapter-section chapter frame-inputs open-frame-actions active-frame-id editing-chapter-id chapter-name-inputs])
                  saga)
     (when show-chapter-celebration?
       [chapter-celebration])
     (if new-chapter-panel-open?
       [new-chapter-form new-chapter-description]
       [new-chapter-teaser active-frame-id])]))
