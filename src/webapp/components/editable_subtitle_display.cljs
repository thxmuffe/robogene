(ns webapp.components.editable-subtitle-display
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.row-dropdown-flow :as row-dropdown-flow]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Box Textarea]]
            ["react-icons/fa6" :refer [FaCheck FaEraser FaImage FaPaperPlane FaTrashCan FaXmark]]))

(defn- focus-subtitle! [frame-id]
  (.requestAnimationFrame js/window
                          (fn []
                            (frame-nav/focus-subtitle! frame-id))))

(defn- scroll-subtitle-into-view! [frame-id]
  (.requestAnimationFrame
   js/window
   (fn []
     (some-> (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"] .subtitle-display"))
             (.scrollIntoView #js {:block "nearest"
                                   :inline "nearest"})))))

(defn- enable-editing! [frame-id e]
  (interaction/stop! e)
  (rf/dispatch [:set-frame-actions-open frame-id true])
  (scroll-subtitle-into-view! frame-id))

(defn- close-editing! [frame-id]
  (rf/dispatch [:set-frame-actions-open frame-id false])
  (focus-subtitle! frame-id))

(defn- keep-editing-on-blur? [frame-id]
  (let [active-el (.-activeElement js/document)
        frame-actions-selector (str ".frame-action-buttons[data-frame-id=\"" frame-id "\"]")]
    (or (interaction/closest? active-el frame-actions-selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(def max-subtitle-chars 500)

(defn- clamp-subtitle [text]
  (let [value (subs (or text "") 0 (min (count (or text "")) max-subtitle-chars))]
    value))

(defn editable-subtitle-display [{:keys [frameId description]} editing?]
  (let [skip-blur-revert?* (r/atom false)
        action-pointer-down?* (r/atom false)
        confirm* (r/atom nil)
        upload-open?* (r/atom false)
        seen-cancel-token* (r/atom nil)]
    (fn [{:keys [frameId description]} editing?]
      (let [saved-description (clamp-subtitle description)
            current-input (clamp-subtitle @(rf/subscribe [:frame-draft frameId]))
            cancel-ui-token @(rf/subscribe [:cancel-ui-token])
            subtitle (str/trim (or (when editing? current-input) saved-description ""))
            subtitle-props (cond-> {:className (str "subtitle-display" (when editing? " subtitle-display-editing"))}
                             (not editing?)
                             (assoc :onClick #(enable-editing! frameId %)
                                    :onDoubleClick #(enable-editing! frameId %)))
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
            selected-item @confirm*
            preserve-editing-for-action! (fn []
                                           (reset! action-pointer-down?* true))
            close-without-blur-revert! (fn []
                                         (reset! skip-blur-revert?* true)
                                         (close-editing! frameId))
            actions [{:id :submit
                      :label "Submit"
                      :icon FaCheck
                      :color "lime"
                      :variant "filled"
                      :disabled? (= current-input saved-description)
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (rf/dispatch [:save-frame-description frameId current-input])
                                   (close-without-blur-revert!))}
                     {:id :cancel
                      :label "Cancel"
                      :icon FaXmark
                      :color "gray"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (rf/dispatch [:frame-direction-changed frameId saved-description])
                                   (close-without-blur-revert!))}
                     {:id :generate
                      :label "Generate image"
                      :icon FaPaperPlane
                      :color "indigo"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (rf/dispatch [:save-frame-description frameId current-input])
                                   (close-without-blur-revert!)
                                   (rf/dispatch [:generate-frame frameId current-input]))}
                     {:id :replace-image
                      :label "Replace with own photo"
                      :icon FaImage
                      :color "blue"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! upload-open?* true))}
                     {:id :remove-image
                      :label "Remove image"
                      :icon FaEraser
                      :color "orange"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! confirm* (first menu-items)))}
                     {:id :delete-frame
                      :label "Delete frame"
                      :icon FaTrashCan
                      :color "red"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! confirm* (last menu-items)))}]]
        (when (not= cancel-ui-token @seen-cancel-token*)
          (reset! seen-cancel-token* cancel-ui-token)
          (reset! confirm* nil)
          (reset! upload-open?* false))
        [:> Box subtitle-props
         (if editing?
           [:<>
            [:> Textarea
             {:className "subtitle-display-input"
              :defaultValue current-input
              :data-frame-id frameId
              :autosize true
              :minRows 2
              :maxRows 16
              :maxLength max-subtitle-chars
              :autoFocus true
              :placeholder "Describe this frame..."
              :styles #js {:root #js {:width "100%"}
                           :wrapper #js {:width "100%"}
                           :input #js {:width "100%"}}
              :onFocus (fn [e]
                         (interaction/stop! e)
                         (rf/dispatch [:set-active-frame frameId])
                         (scroll-subtitle-into-view! frameId))
              :onBlur (fn [_]
                        (js/setTimeout
                         (fn []
                           (cond
                             @skip-blur-revert?*
                             (reset! skip-blur-revert?* false)

                             @action-pointer-down?*
                             (reset! action-pointer-down?* false)

                             (keep-editing-on-blur? frameId)
                             nil

                             :else
                             (do
                               (rf/dispatch [:frame-direction-changed frameId saved-description])
                               (close-editing! frameId))))
                         0))
              :onClick interaction/stop!
              :onDoubleClick interaction/stop!
              :onChange (fn [e]
                          (rf/dispatch [:frame-direction-changed frameId
                                        (clamp-subtitle (.. e -target -value))]))
              :onKeyDown (fn [e]
                           (let [key (.-key e)
                                 submit? (and (= "Enter" key)
                                              (or (.-metaKey e) (.-ctrlKey e)))]
                             (cond
                               (= "Escape" key)
                               (do
                                 (interaction/halt! e)
                                 (rf/dispatch [:frame-direction-changed frameId saved-description])
                                 (close-without-blur-revert!))

                               submit?
                               (do
                                 (interaction/halt! e)
                                 (rf/dispatch [:save-frame-description frameId current-input])
                                 (close-without-blur-revert!))

                               :else nil)))}]
            [:div {:className "frame-action-buttons"
                   :data-frame-id frameId
                   :onMouseDown interaction/prevent!
                   :onPointerDown interaction/prevent!
                   :onMouseDownCapture (fn [_]
                                         (preserve-editing-for-action!))
                   :onClick interaction/stop!
                   :onDoubleClick interaction/stop!}
             [row-dropdown-flow/row-dropdown-flow
              {:class-name "frame-action-buttons-row"
               :actions actions
               :mandatory-count 2
               :menu-title "Frame actions"
               :menu-aria-label "Frame actions"
               :on-action-pointer-down preserve-editing-for-action!}]
             [confirm-dialog/confirm-dialog
              {:item selected-item
               :on-cancel #(reset! confirm* nil)
               :on-confirm (fn []
                             (when-let [event (:dispatch-event selected-item)]
                               (rf/dispatch event))
                             (reset! confirm* nil))}]
             [upload-dialog/upload-dialog
              {:open @upload-open?*
               :active-frame-id frameId
               :on-close #(reset! upload-open?* false)
               :on-submit (fn [image-data-url]
                            (rf/dispatch [:replace-frame-image frameId image-data-url]))}]]]
           [:span {:className "subtitle-display-text"
                   :data-frame-id frameId
                   :role "button"
                   :tabIndex 0
                   :title "Click subtitle to edit description"
                   :onFocus #(rf/dispatch [:set-active-frame frameId])
                   :onKeyDown (fn [e]
                                (when (or (= "Enter" (.-key e))
                                          (= " " (.-key e)))
                                  (interaction/prevent! e)
                                  (enable-editing! frameId e)))}
            (if (seq subtitle)
              subtitle
              "Click subtitle to add description")])]))))
