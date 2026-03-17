(ns webapp.pages.item-page
  "Generic item (frame) page."
  (:require [re-frame.core :as rf]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.controls :as controls]
            [webapp.components.item :as item]))

(defn navigate-to-frame [direction]
  (when-let [active-id @(rf/subscribe [:active-frame-id])]
    (when-let [target (frame-nav/adjacent-frame-id active-id (if (= direction :next) 1 -1))]
      (rf/dispatch [:set-active-frame target]))))

(defn item-page [{:keys [item-id]}]
  (let [entity @(rf/subscribe [:entity item-id])
        image-status (get-in entity [:payload :imageStatus])
        image-ui @(rf/subscribe [:frame-image-ui item-id])]
    (if (nil? entity)
      [:div.item-page [:p "Loading item..."]]
      [item/item
       entity
       {:clickable? false
        :image-nav? true
        :image-fit "contain"
        :image-field :imageUrl
        :image-status image-status
        :on-click-edit #(rf/dispatch [:ui-entity-editing-start item-id])
        :on-save #(rf/dispatch [:entity-update item-id {:description %}])
        :on-generate #(rf/dispatch [:post-generate-frame
                                    {:frame-id item-id
                                     :direction nil
                                     :on-success [:entity-update item-id]
                                     :on-failure [:state-failed]}])
        :on-upload #(rf/dispatch [:open-upload-dialog item-id])
        :on-download (fn []
                       (when-let [url (get-in entity [:payload :imageUrl])]
                         (controls/download-image! url (str (:title entity) ".png"))))
        :on-delete #(when (js/confirm "Delete this item?")
                      (rf/dispatch [:entity-delete item-id]))
        :on-nav-left #(navigate-to-frame :prev)
        :on-nav-right #(navigate-to-frame :next)
        :on-image-load #(rf/dispatch [:frame-image-loaded item-id])
        :on-image-error #(rf/dispatch [:frame-image-error item-id])}])))

(defn item-page-view []
  (let [route @(rf/subscribe [:route])]
    (if-let [item-id (or (:frame-id route)
                         (:active-frame-id route)
                         (:entity-id route))]
      [item-page {:item-id item-id}]
      [:div.item-page
       [:p "No item specified in route."]])))
