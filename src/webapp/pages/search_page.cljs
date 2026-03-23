(ns webapp.pages.search-page
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.gallery :as gallery]
            ["@mantine/core" :refer [Button Stack TextInput]]))

(def view-id :search-page)

(defn search-page []
  (r/with-let [last-query* (r/atom nil)]
    (let [query @(rf/subscribe [:collection-search view-id])
          result-ids @(rf/subscribe [:search-result-ids])
          loading? @(rf/subscribe [:search-loading?])
          next-cursor @(rf/subscribe [:search-next-cursor])]
      (when (not= query @last-query*)
        (reset! last-query* query)
        (rf/dispatch [:search/request {}]))
      [:> Stack {:gap "md" :className "search-page"}
       [:> TextInput {:placeholder "Search..."
                      :variant "filled"
                      :size "sm"
                      :className "collection-search-input"
                      :value query
                      :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
       [gallery/search-gallery {:entity-ids result-ids}]
       (when (or loading? next-cursor)
         [:> Button {:variant "light"
                     :loading loading?
                     :disabled (or loading? (nil? next-cursor))
                     :onClick #(rf/dispatch [:search/load-more])}
          (if next-cursor "Load more" "Loaded")])])))

(defn search-page-view []
  [search-page])
