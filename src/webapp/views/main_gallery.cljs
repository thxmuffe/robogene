(ns webapp.views.main-gallery
  (:require [re-frame.core :as rf]
            [webapp.views.frame-view :as frame-view]))

(defn chapter-section [chapter frame-inputs open-frame-actions active-frame-id]
  [:section.chapter-block
   [:div.chapter-separator]
   [:div.chapter-header
    [:p.chapter-description (:description chapter)]
    [:button.btn
     {:type "button"
      :on-click #(rf/dispatch [:add-frame (:chapterId chapter)])}
     "Add New Frame"]]
   [:div.gallery
    (for [frame (:frames chapter)]
      ^{:key (:frameId frame)}
      [frame-view/frame-view frame
       (get frame-inputs (:frameId frame) "")
       {:active? (= active-frame-id (:frameId frame))
        :actions-open? (true? (get open-frame-actions (:frameId frame)))}])]])

(defn new-chapter-form [description]
  [:section.new-chapter-panel
   [:h3 "Add New Chapter"]
   [:button.new-chapter-close
    {:type "button"
     :on-click #(rf/dispatch [:set-new-chapter-panel-open false])}
    "Close"]
   [:label.dir-label {:for "new-chapter-description"} "Chapter Theme"]
   [:textarea.direction-input
    {:id "new-chapter-description"
     :value (or description "")
     :placeholder "Describe the next chapter theme..."
     :on-key-down (fn [e]
                    (when (and (= "Enter" (.-key e))
                               (not (.-shiftKey e)))
                      (.preventDefault e)
                      (rf/dispatch [:add-chapter])))
     :on-change #(rf/dispatch [:new-chapter-description-changed (.. % -target -value)])}]
   [:button.btn.btn-primary
    {:type "button"
     :on-click #(rf/dispatch [:add-chapter])}
    "Add New Chapter"]])

(defn new-chapter-teaser [active-frame-id]
  [:article.new-chapter-teaser
   {:class (str "frame frame-clickable"
                (when (= active-frame-id "__new_chapter__")
                  " frame-active"))
    :data-frame-id "__new_chapter__"
    :role "button"
    :tab-index 0
    :on-mouse-enter #(rf/dispatch [:set-active-frame "__new_chapter__"])
    :on-focus #(rf/dispatch [:set-active-frame "__new_chapter__"])
    :on-click #(do
                 (rf/dispatch [:set-active-frame "__new_chapter__"])
                 (rf/dispatch [:set-new-chapter-panel-open true]))
    :on-key-down (fn [e]
                   (when (or (= "Enter" (.-key e))
                             (= " " (.-key e)))
                     (.preventDefault e)
                     (rf/dispatch [:set-active-frame "__new_chapter__"])
                     (rf/dispatch [:set-new-chapter-panel-open true])))}
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
  [:section
   [:h2 "Chapters"]
   (for [chapter chapters]
     ^{:key (:chapterId chapter)}
     [chapter-section chapter frame-inputs open-frame-actions active-frame-id])
   (when show-chapter-celebration?
     [chapter-celebration])
   (if new-chapter-panel-open?
     [new-chapter-form new-chapter-description]
     [new-chapter-teaser active-frame-id])])
