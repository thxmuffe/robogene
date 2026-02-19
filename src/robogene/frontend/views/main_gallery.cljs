(ns robogene.frontend.views.main-gallery
  (:require [robogene.frontend.views.frame-card :as frame-card]))

(defn main-gallery-page [gallery frame-inputs]
  [:section
   [:h2 "Gallery"]
   [:div.gallery
    (for [frame gallery]
      ^{:key (:frameId frame)}
      [frame-card/frame-card frame (get frame-inputs (:frameId frame) "")])]])
