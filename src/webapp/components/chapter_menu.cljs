(ns webapp.components.chapter-menu
  (:require [webapp.components.frame-menu :as frame-menu]))

(defn chapter-menu [{:keys [title aria-label button-class items on-select]}]
  [frame-menu/frame-menu
   {:title (or title "Chapter actions")
    :aria-label (or aria-label "Chapter actions")
    :button-class (or button-class "chapter-menu-trigger")
    :items items
    :on-select on-select}])
