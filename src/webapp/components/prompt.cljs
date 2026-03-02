(ns webapp.components.prompt
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.frame-menu :as frame-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]
            ["@mantine/core" :refer [ActionIcon Box Stack Textarea Tooltip]]
            ["react-icons/fa6" :refer [FaPaperPlane]]))

(defn on-editor-focus [e]
  (interaction/stop! e))

(defn on-editor-change [frame-id]
  (fn [e]
    (rf/dispatch [:frame-direction-changed frame-id (.. e -target -value)])))

(defn prompt-panel [{:keys [frameId error]} frame-input]
  (r/with-let [confirm* (r/atom nil)]
    (let [submit! (fn []
                    (rf/dispatch [:set-frame-actions-open frameId false])
                    (rf/dispatch [:generate-frame frameId]))
          close-prompt! (fn [e]
                          (interaction/halt! e)
                          (rf/dispatch [:set-frame-actions-open frameId false]))
          on-send-click (fn [e]
                          (interaction/halt! e)
                          (submit!))
          on-editor-key-down (fn [e]
                               (let [key (.-key e)
                                     escape? (= "Escape" key)
                                     enter? (= "Enter" key)
                                     submit? (and enter? (or (.-metaKey e) (.-ctrlKey e)))]
                                 (cond
                                   escape?
                                   (close-prompt! e)
                                   submit?
                                   (do
                                     (interaction/halt! e)
                                     (submit!))
                                   :else
                                   (interaction/stop! e))))
          menu-items [{:id :remove-image
                       :label "Remove image"
                       :confirm {:title "Remove image from this frame?"
                                 :text "The frame and its description will stay."
                                 :confirm-label "Remove image"
                                 :confirm-color "primary"}
                       :dispatch-event [:clear-frame-image frameId]}
                      {:id :delete-frame
                       :label "Delete frame"
                       :confirm {:title "Delete this frame?"
                                 :text "This cannot be undone."
                                 :confirm-label "Delete"
                                 :confirm-color "error"}
                       :dispatch-event [:delete-frame frameId]}]
          selected-item @confirm*]
      [:> Box {:className "prompt-panel"}
       [:> Box {:className "prompt-main"}
        [:> Textarea
         {:className "prompt-input"
          :autosize true
          :minRows 3
          :maxRows 12
          :styles #js {:root #js {:width "100%" :height "100%"}
                       :wrapper #js {:height "100%"}
                       :input #js {:height "100%" :minHeight "100%" :maxHeight "none"}}
          :autoFocus true
          :value (or frame-input "")
          :placeholder "Describe this frame..."
          :onFocus on-editor-focus
          :onKeyDown on-editor-key-down
          :onChange (on-editor-change frameId)}]
        (when (seq (or error ""))
          [:div.error-line (str "Last error: " error)])]
       [:> Stack {:className "prompt-controls"
                  :gap "xs"}
        [:> Tooltip {:label "Generate (Cmd/Ctrl+Enter)"}
         [:> ActionIcon
          {:className "prompt-generate-btn"
           :aria-label "Generate"
           :variant "filled"
           :radius "xl"
           :onClick on-send-click}
          [:> FaPaperPlane]]]
        [frame-menu/frame-menu
         {:title "Actions"
          :button-class "prompt-actions-trigger"
          :items menu-items
          :on-select #(reset! confirm* %)}]
        [confirm-dialog/confirm-dialog
         {:item selected-item
          :on-cancel #(reset! confirm* nil)
          :on-confirm (fn []
                        (when-let [event (:dispatch-event selected-item)]
                          (rf/dispatch event))
                        (reset! confirm* nil))}]]])))
