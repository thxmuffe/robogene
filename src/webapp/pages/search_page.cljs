(ns webapp.pages.search-page
  (:require [re-frame.core :as rf]
            [webapp.components.gallery :as gallery]
            ["@mantine/core" :refer [Stack TextInput]]))

(def view-id :search-page)

(defn search-page []
  (let [query @(rf/subscribe [:collection-search view-id])
        result-ids @(rf/subscribe [:search-result-ids query])]
    [:> Stack {:gap "md" :className "search-page"}
     [:> TextInput {:placeholder "Search..."
                    :variant "filled"
                     :size "sm"
                    :className "collection-search-input"
                    :value query
                    :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
     [gallery/search-gallery {:entity-ids result-ids}]]))

(defn search-page-view []
  [search-page])
