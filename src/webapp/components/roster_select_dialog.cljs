(ns webapp.components.roster-select-dialog
  (:require [webapp.components.popup-dialog :as popup-dialog]
            ["@mantine/core" :refer [TextInput]]))

(defn roster-select-dialog [{:keys [open title search on-search on-close items empty-label]}]
  [popup-dialog/popup-dialog {:open open
                              :on-close on-close
                              :size "72rem"
                              :padding "lg"}
   [:div.roster-select-dialog
    [:h3.roster-select-title (or title "Select roster")]
    [:> TextInput
     {:value (or search "")
      :placeholder "Search rosters..."
      :className "new-chapter-input"
      :onChange #(on-search (.. % -target -value))}]
    (if (seq items)
      (into [:div.roster-select-grid] items)
      [:p.roster-select-empty (or empty-label "No rosters match this search.")])]])
