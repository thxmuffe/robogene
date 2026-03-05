(ns webapp.components.frame-description-editor
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.components.edit-db-item :as edit-db-item]
            [webapp.components.frame-menu :as frame-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            ["@mantine/core" :refer [ActionIcon Box Tooltip]]
            ["react-icons/fa6" :refer [FaPaperPlane]]))

(defn on-editor-focus [e]
  (interaction/stop! e))

(defn frame-description-editor [{:keys [frameId error description]} frame-input]
  (r/with-let [confirm* (r/atom nil)
               upload-open?* (r/atom false)
               seen-cancel-token* (r/atom nil)
               seen-error* (r/atom nil)]
    (let [saved-description (or description "")
          current-input (or frame-input "")
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          description-dirty? (not= current-input saved-description)
          focus-subtitle! (fn []
                            (.requestAnimationFrame js/window
                                                    (fn []
                                                      (frame-nav/focus-subtitle! frameId))))
          close-editor! (fn []
                          (rf/dispatch [:set-frame-actions-open frameId false])
                          (focus-subtitle!))
          submit-generate! (fn []
                             (close-editor!)
                             (rf/dispatch [:generate-frame frameId]))
          submit-save! (fn []
                         (rf/dispatch [:save-frame-description frameId current-input])
                         (close-editor!))
          cancel-edit! (fn []
                         (rf/dispatch [:frame-direction-changed frameId saved-description])
                         (close-editor!))
          on-send-click (fn [e]
                          (interaction/halt! e)
                          (submit-generate!))
          menu-items [{:id :remove-image
                       :label "Remove image"
                       :confirm {:title "Remove image from this frame?"
                                 :text "The frame and its description will stay."
                                 :confirm-label "Remove image"
                                 :confirm-color "primary"}
                       :dispatch-event [:clear-frame-image frameId]}
                      {:id :replace-image
                       :label "Replace with own photo"}
                      {:id :delete-frame
                       :label "Delete frame"
                       :confirm {:title "Delete this frame?"
                                 :text "This cannot be undone."
                                 :confirm-label "Delete"
                                 :confirm-color "error"}
                      :dispatch-event [:delete-frame frameId]}]
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false))
      (when (and (seq (or error ""))
                 (not= error @seen-error*))
        (reset! seen-error* error)
        (js/console.error "[robogene] frame generation error"
                          (clj->js {:frameId frameId
                                    :error error})))
      [:> Box {:className "description-editor-panel"}
       [:div.description-editor-resize-handle
        {:role "separator"
         :aria-orientation "horizontal"
         :aria-label "Description editor splitter"}]
       [:> Box {:className "description-editor-main"}
        [edit-db-item/edit-db-item
         {:class-name "description-editor-form"
          :show-name? false
          :description-value current-input
          :on-description-change #(rf/dispatch [:frame-direction-changed frameId %])
          :description-props {:className "description-editor-input"
                              :rows 3
                              :autoFocus true
                              :placeholder "Describe this frame..."
                              :styles #js {:root #js {:width "100%" :height "100%"}
                                           :wrapper #js {:height "100%"}
                                           :input #js {:height "100%" :minHeight "100%" :maxHeight "none"}}
                              :onFocus on-editor-focus}
          :description-shortcuts? true
          :on-description-key-down interaction/stop!
          :on-submit (fn [_] (submit-save!))
          :on-cancel (fn [_] (cancel-edit!))
          :submit-disabled? (not description-dirty?)
          :actions-class "chapter-edit-actions description-editor-actions"
           :extra-actions
           [:<>
           [:> Tooltip {:label "Generate image"}
            [:> ActionIcon
             {:className "description-editor-generate-btn"
              :aria-label "Generate"
              :variant "filled"
              :radius "xl"
              :onClick on-send-click}
             [:> FaPaperPlane]]]
           [frame-menu/frame-menu
            {:title "Actions"
             :button-class "description-editor-actions-trigger"
             :items menu-items
             :on-select (fn [item]
                          (if (= :replace-image (:id item))
                            (reset! upload-open?* true)
                            (reset! confirm* item)))}]]}]
        ]
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
                      (rf/dispatch [:replace-frame-image frameId image-data-url]))}]])))
