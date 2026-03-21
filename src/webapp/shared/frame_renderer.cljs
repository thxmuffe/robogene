(ns webapp.shared.frame-renderer
  (:require [re-frame.core :as rf]
            [webapp.components.frame :as frame]))

(defn render-frame
  ([frame-row]
   [render-frame frame-row {}])
  ([frame-row options]
   (let [frame-id (:frameId frame-row)
         editable? @(rf/subscribe [:frame-edit-open? frame-id])
         current-input @(rf/subscribe [:frame-draft frame-id])]
     [frame/frame
      frame-row
      (merge
       {:editable? editable?
        :current-input current-input
        :on-image-load #(rf/dispatch [:frame-image-loaded frame-id (:imageUrl frame-row)])
        :on-image-error #(rf/dispatch [:frame-image-error frame-id (:imageUrl frame-row)])
        :on-open-edit #(do
                         (rf/dispatch [:set-frame-actions-open frame-id true])
                         (rf/dispatch [:set-active-frame frame-id]))
        :on-close-edit #(rf/dispatch [:set-frame-actions-open frame-id false])
        :on-description-change #(rf/dispatch [:frame-direction-changed frame-id %])
        :on-save-description #(rf/dispatch [:save-frame-description frame-id %])
        :on-focus #(rf/dispatch [:set-active-frame frame-id])
        :on-generate #(rf/dispatch [:generate-frame frame-id current-input])
        :on-generate-without-roster #(rf/dispatch [:generate-frame-without-roster frame-id current-input])
        :on-replace-image #(rf/dispatch [:replace-frame-image frame-id %])
        :on-delete-frame #(rf/dispatch [:delete-frame frame-id])
        :on-clear-image #(rf/dispatch [:clear-frame-image frame-id])}
       options)])))
