(ns webapp.shared.ui.back-button
  (:require ["@mantine/core" :refer [Button]]))

(defn back-button [{:keys [label on-click]}]
  [:> Button
   {:variant "default"
    :size "sm"
    :className "back-nav-btn"
    :onClick on-click}
   (or label "Back")])
