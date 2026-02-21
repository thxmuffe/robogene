(ns robogene.frontend.views.main-gallery
  (:require [re-frame.core :as rf]
            [robogene.frontend.views.frame-view :as frame-view]))

(defn episode-section [episode frame-inputs open-frame-actions active-frame-id]
  [:section.episode-block
   [:div.episode-separator]
   [:div.episode-header
    [:p.episode-description (:description episode)]
    [:button.btn
     {:type "button"
      :on-click #(rf/dispatch [:add-frame (:episodeId episode)])}
     "Add New Frame"]]
   [:div.gallery
    (for [frame (:frames episode)]
      ^{:key (:frameId frame)}
      [frame-view/frame-view frame
       (get frame-inputs (:frameId frame) "")
       {:active? (= active-frame-id (:frameId frame))
        :actions-open? (true? (get open-frame-actions (:frameId frame)))}])]])

(defn new-episode-form [description]
  [:section.new-episode-panel
   [:h3 "Add New Episode"]
   [:button.new-episode-close
    {:type "button"
     :on-click #(rf/dispatch [:set-new-episode-panel-open false])}
    "Close"]
   [:label.dir-label {:for "new-episode-description"} "Episode Theme"]
   [:textarea.direction-input
    {:id "new-episode-description"
     :value (or description "")
     :placeholder "Describe the next episode theme..."
     :on-key-down (fn [e]
                    (when (and (= "Enter" (.-key e))
                               (not (.-shiftKey e)))
                      (.preventDefault e)
                      (rf/dispatch [:add-episode])))
     :on-change #(rf/dispatch [:new-episode-description-changed (.. % -target -value)])}]
   [:button.btn.btn-primary
    {:type "button"
     :on-click #(rf/dispatch [:add-episode])}
    "Add New Episode"]])

(defn new-episode-teaser [active-frame-id]
  [:article.new-episode-teaser
   {:class (str "frame frame-clickable"
                (when (= active-frame-id "__new_episode__")
                  " frame-active"))
    :data-frame-id "__new_episode__"
    :role "button"
    :tab-index 0
    :on-mouse-enter #(rf/dispatch [:set-active-frame "__new_episode__"])
    :on-focus #(rf/dispatch [:set-active-frame "__new_episode__"])
    :on-click #(do
                 (rf/dispatch [:set-active-frame "__new_episode__"])
                 (rf/dispatch [:set-new-episode-panel-open true]))
    :on-key-down (fn [e]
                   (when (or (= "Enter" (.-key e))
                             (= " " (.-key e)))
                     (.preventDefault e)
                     (rf/dispatch [:set-active-frame "__new_episode__"])
                     (rf/dispatch [:set-new-episode-panel-open true])))}
   [:div.sparkles]
   [:div.teaser-content
    [:div.teaser-title "Add New Episode"]
    [:div.teaser-sub "Click to start a new adventure"]]])

(defn episode-celebration []
  [:div.episode-celebration
   [:div.rainbow-band.band-1]
   [:div.rainbow-band.band-2]
   [:div.rainbow-band.band-3]
   [:div.rainbow-band.band-4]
   [:div.rainbow-stars "✦ ✧ ✦ ✧ ✦"]])

(defn main-gallery-page [episodes frame-inputs open-frame-actions active-frame-id new-episode-description new-episode-panel-open? show-episode-celebration?]
  [:section
   [:h2 "Episodes"]
   (for [episode episodes]
     ^{:key (:episodeId episode)}
     [episode-section episode frame-inputs open-frame-actions active-frame-id])
   (when show-episode-celebration?
     [episode-celebration])
   (if new-episode-panel-open?
     [new-episode-form new-episode-description]
     [new-episode-teaser active-frame-id])])
