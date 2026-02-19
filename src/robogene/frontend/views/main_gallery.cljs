(ns robogene.frontend.views.main-gallery
  (:require [robogene.frontend.views.frame-view :as frame-view]))

(defn main-gallery-page [gallery frame-inputs]
  [:section
   [:h2 "Gallery"]
   [:div.gallery
    (for [frame gallery]
      ^{:key (:frameId frame)}
      [frame-view/frame-view frame (get frame-inputs (:frameId frame) "")])]])
