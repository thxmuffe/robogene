(ns webapp.components.roster-button
  (:require ["@mantine/core" :refer [Button]]))

(defn- visible-roster-cells [image-urls]
  (let [image-count (count image-urls)
        single-row? (< image-count 3)
        visible-cell-count (if single-row?
                             (max 1 image-count)
                             4)
        cells (concat image-urls (repeat nil))]
    {:single-row? single-row?
     :visible-cell-count visible-cell-count
     :cells (take visible-cell-count cells)}))

(defn roster-button [{:keys [label description image-urls aria-label title class-name on-click]}]
  (let [{:keys [single-row? visible-cell-count cells]} (visible-roster-cells (vec (take 4 (or image-urls []))))]
    [:> Button
     {:aria-label (or aria-label label "Roster")
      :title (or title label "Roster")
      :variant "default"
      :size "sm"
      :className (str "roster-nav-btn" (when class-name (str " " class-name)))
      :onClick on-click}
     [:span.roster-nav-btn-content
      [:span {:className (str "roster-nav-btn-grid"
                              (when single-row? " is-row")
                              (str " is-count-" visible-cell-count))}
       (for [[idx image-url] (map-indexed vector cells)]
         ^{:key (str "roster-cell-" idx)}
         [:span.roster-nav-btn-cell
          (when image-url
            [:img {:className "roster-nav-btn-cell-image"
                   :src image-url
                   :alt ""}])])]
      [:span.roster-nav-btn-copy
       [:span.roster-nav-btn-label (or label "Roster")]
       (when-let [description* (some-> description str not-empty)]
         [:span.roster-nav-btn-description description*])]]]))
