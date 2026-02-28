(ns webapp.pages.main-gallery
  (:require [re-frame.core :as rf]
            [webapp.shared.controls :as controls]
            [webapp.components.frames :as frames]
            ["@mui/material/Button" :default Button]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/Box" :default Box]
            ["@mui/icons-material/Close" :default CloseIcon]))

(defn chapter-section [chapter frame-inputs open-frame-actions active-frame-id]
  [:> Box {:component "section" :className "chapter-block"}
   [:div.chapter-separator]
   [:> Stack {:className "chapter-header"
              :direction "row"
              :spacing 1.5
              :alignItems "center"
              :flexWrap "wrap"}
    [:p.chapter-description (:description chapter)]
    [:> Button
     {:variant "contained"
      :color "primary"
      :size "small"
      :on-click #(rf/dispatch [:add-frame (:chapterId chapter)])}
     "Add New Frame"]]
   [frames/chapter-frames (:chapterId chapter) frame-inputs open-frame-actions active-frame-id]])

(defn new-chapter-form [description]
  [:section.new-chapter-panel
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
     :on-key-down controls/on-new-chapter-form-keydown
     :on-change #(rf/dispatch [:new-chapter-description-changed (.. % -target -value)])}]
   [:> Button
    {:className "new-chapter-submit"
     :variant "contained"
     :color "secondary"
     :on-click #(rf/dispatch [:add-chapter])}
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
    :on-click controls/on-new-chapter-teaser-click
    :on-key-down controls/on-new-chapter-teaser-keydown}
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

(defn main-gallery-page [chapters frame-inputs open-frame-actions active-frame-id new-chapter-description new-chapter-panel-open? show-chapter-celebration?]
  [:> Stack {:component "section" :spacing 2}
   [:h2 "Chapters"]
   (map-indexed (fn [idx chapter]
                  ^{:key (or (:chapterId chapter) (str "chapter-" idx))}
                  [chapter-section chapter frame-inputs open-frame-actions active-frame-id])
                chapters)
   (when show-chapter-celebration?
     [chapter-celebration])
   (if new-chapter-panel-open?
     [new-chapter-form new-chapter-description]
     [new-chapter-teaser active-frame-id])])
