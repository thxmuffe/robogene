(ns webapp.components.frame-action-buttons
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.frame-menu :as frame-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            ["@mantine/core" :refer [ActionIcon Tooltip]]
            ["react-icons/fa6" :refer [FaCheck FaPaperPlane FaXmark]]))

(defn frame-action-buttons
  [{:keys [frame-id submit-disabled? on-submit on-cancel on-generate]}]
  (r/with-let [confirm* (r/atom nil)
               upload-open?* (r/atom false)
               seen-cancel-token* (r/atom nil)]
    (let [cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          menu-items [{:id :remove-image
                       :label "Remove image"
                       :confirm {:title "Remove image from this frame?"
                                 :text "The frame and its description will stay."
                                 :confirm-label "Remove image"
                                 :confirm-color "primary"}
                       :dispatch-event [:clear-frame-image frame-id]}
                      {:id :replace-image
                       :label "Replace with own photo"}
                      {:id :delete-frame
                       :label "Delete frame"
                       :confirm {:title "Delete this frame?"
                                 :text "This cannot be undone."
                                 :confirm-label "Delete"
                                 :confirm-color "error"}
                       :dispatch-event [:delete-frame frame-id]}]
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false))
      [:<>
       [:div {:className "frame-action-buttons"
              :data-frame-id frame-id
              :onMouseDown interaction/prevent!
              :onPointerDown interaction/prevent!
              :onClick interaction/stop!
              :onDoubleClick interaction/stop!}
        [:> ActionIcon
         {:aria-label "Submit"
          :title "Submit"
          :tabIndex -1
          :variant "filled"
          :radius "xl"
          :disabled (true? submit-disabled?)
          :onClick (fn [e]
                     (interaction/halt! e)
                     (on-submit))}
         [:> FaCheck]]
        [:> ActionIcon
         {:aria-label "Cancel"
          :title "Cancel"
          :tabIndex -1
          :variant "subtle"
          :radius "xl"
          :onClick (fn [e]
                     (interaction/halt! e)
                     (on-cancel))}
         [:> FaXmark]]
        [:> Tooltip {:label "Generate image"}
         [:> ActionIcon
          {:className "description-editor-generate-btn"
           :aria-label "Generate"
           :tabIndex -1
           :variant "filled"
           :radius "xl"
           :onClick (fn [e]
                      (interaction/halt! e)
                      (on-generate))}
          [:> FaPaperPlane]]]
        [frame-menu/frame-menu
         {:title "Actions"
          :button-class "description-editor-actions-trigger"
          :items menu-items
          :on-select (fn [item]
                       (if (= :replace-image (:id item))
                         (reset! upload-open?* true)
                         (reset! confirm* item)))}]]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]
       [upload-dialog/upload-dialog
        {:open @upload-open?*
         :on-close #(reset! upload-open?* false)
         :on-submit (fn [image-data-url]
                      (rf/dispatch [:replace-frame-image frame-id image-data-url]))}]])))
