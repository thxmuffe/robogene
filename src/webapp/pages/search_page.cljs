(ns webapp.pages.search-page
  (:require [re-frame.core :as rf]
            [webapp.components.gallery :as gallery]
            [webapp.shared.search :as search]
            ["@mantine/core" :refer [Stack TextInput]]))

(def view-id :search-page)

(defn search-page []
  (let [query @(rf/subscribe [:collection-search view-id])
        entities @(rf/subscribe [:entities])
        filtered (search/search-entities entities query)]
    [:> Stack {:gap "md" :className "search-page"}
     [:> TextInput {:placeholder "Search..."
                    :variant "filled"
                     :size "sm"
                    :className "collection-search-input"
                    :value query
                    :onChange #(rf/dispatch [:collection-search-changed view-id (.. % -target -value)])}]
     [gallery/search-gallery {:entities filtered}]]))

(defn search-page-view []
  [search-page])
