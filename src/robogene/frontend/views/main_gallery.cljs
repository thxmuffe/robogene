(ns robogene.frontend.views.main-gallery
  (:require [re-frame.core :as rf]
            [robogene.frontend.views.frame-view :as frame-view]))

(defn episode-section [episode frame-inputs]
  [:section.episode-block
   [:div.episode-separator]
   [:div.episode-header
    [:h2 (str "Episode " (:episodeNumber episode))]
    [:p.episode-description (:description episode)]
    [:button.btn
     {:type "button"
      :on-click #(rf/dispatch [:add-frame (:episodeId episode)])}
     "Add New Frame"]]
   [:div.gallery
    (for [frame (:frames episode)]
      ^{:key (:frameId frame)}
      [frame-view/frame-view frame (get frame-inputs (:frameId frame) "")])]])

(defn new-episode-form [description]
  [:section.new-episode-panel
   [:h3 "Add New Episode"]
   [:label.dir-label {:for "new-episode-description"} "Episode Theme"]
   [:textarea.direction-input
    {:id "new-episode-description"
     :value (or description "")
     :placeholder "Describe the next episode theme..."
     :on-change #(rf/dispatch [:new-episode-description-changed (.. % -target -value)])}]
   [:button.btn.btn-primary
    {:type "button"
     :on-click #(rf/dispatch [:add-episode])}
    "Add New Episode"]])

(defn main-gallery-page [episodes frame-inputs new-episode-description]
  [:section
   [:h2 "Episodes"]
   (for [episode episodes]
     ^{:key (:episodeId episode)}
     [episode-section episode frame-inputs])
   [new-episode-form new-episode-description]])
