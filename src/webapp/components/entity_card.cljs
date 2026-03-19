(ns webapp.components.entity-card
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            ["@mantine/core" :refer [Box Card Image Text Stack]]))

(defn entity-card [{:keys [entity clickable? on-click]}]
  (let [entities @(rf/subscribe [:entities])
        entity-id (:id entity)
        preview-url (model/preview-image-url entities entity-id)
        title (model/primary-label entity)
        subtitle (model/secondary-label entity)
        clickable? (not (false? clickable?))]
    [:> Card
     {:className (str "frame entity-card" (when clickable? " frame-clickable"))
      :onClick on-click}
     [:> Stack {:gap "xs"}
      [:> Box {:className "media-shell entity-card-media"}
       (if preview-url
         [:> Image {:src preview-url
                    :alt title
                    :fit "cover"}]
         [:> Box {:className "placeholder-img"}
          [:div {:className "spinner"}]
          [:div.placeholder-text "No image"]])]
      [:> Box {:className "entity-card-copy"}
       [:> Text {:fw 700 :size "sm" :className "entity-card-title"} title]
       (when subtitle
         [:> Text {:size "sm" :c "dimmed" :className "entity-card-subtitle"} subtitle])]]]))
