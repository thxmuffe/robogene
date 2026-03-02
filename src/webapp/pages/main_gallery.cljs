(ns webapp.pages.main-gallery
  (:require [re-frame.core :as rf]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.chapter :as chapter-component]
            [webapp.components.chapter-actions :as chapter-actions]
            ["@mui/material/Button" :default Button]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/Box" :default Box]
            ["@mui/icons-material/Close" :default CloseIcon]
            ["@mui/icons-material/Check" :default CheckIcon]))

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
   [:> Stack {:className "chapter-header"
              :direction "row"
              :spacing 1.5
              :alignItems "center"
              :flexWrap "wrap"}
    (if (= editing-chapter-id (:chapterId chapter))
      [:<>
       [:> TextField
        {:size "small"
         :value (get chapter-name-inputs (:chapterId chapter) "")
         :on-key-down (on-chapter-name-keydown (:chapterId chapter))
         :on-change #(rf/dispatch [:chapter-name-input-changed (:chapterId chapter) (.. % -target -value)])}]
       [:> IconButton
        {:aria-label "Save chapter name"
         :title "Save chapter name"
         :on-click #(rf/dispatch [:save-chapter-name (:chapterId chapter)])}
        [:> CheckIcon]]
       [:> IconButton
        {:aria-label "Cancel chapter name editing"
         :title "Cancel chapter name editing"
         :on-click #(rf/dispatch [:cancel-chapter-name-edit])}
        [:> CloseIcon]]]
      [:> Button
       {:variant "outlined"
        :size "small"}
       (:description chapter)])
    [chapter-actions/chapter-actions
     {:chapter-id (:chapterId chapter)
      :chapter-name (:description chapter)}]
    [:> Button
     {:variant "contained"
      :color "primary"
      :size "small"
      :on-click #(rf/dispatch [:add-frame (:chapterId chapter)])}
     "Add New Frame"]]
   [chapter-component/chapter (:chapterId chapter) frame-inputs open-frame-actions active-frame-id]])

(defn new-chapter-form [description]
  [:> Box {:component "form"
           :className "new-chapter-panel"
           :on-submit on-new-chapter-submit}
   [:h3 "Add New Chapter"]
   [:> IconButton
    {:className "new-chapter-close"
     :aria-label "Close"
     :on-click #(rf/dispatch [:set-new-chapter-panel-open false])}
    [:> CloseIcon]]
   [:label.dir-label {:for "new-chapter-description"} "Chapter Theme"]
   [:> TextField
   {:id "new-chapter-description"
     :multiline true
     :minRows 3
     :maxRows 10
     :fullWidth true
     :className "new-chapter-input"
     :value (or description "")
     :placeholder "Describe the next chapter theme..."
     :on-change #(rf/dispatch [:new-chapter-description-changed (.. % -target -value)])}]
   [:> Button
    {:className "new-chapter-submit"
     :type "submit"
     :variant "contained"
     :color "secondary"}
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
  [:> Stack {:component "section" :spacing 2}
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
