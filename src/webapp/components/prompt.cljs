(ns webapp.components.prompt
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.components.action-menu :as action-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]
            ["@mui/material/Box" :default Box]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/icons-material/SendRounded" :default SendRoundedIcon]))

(defn prompt-panel [{:keys [frameId error]} frame-input]
  (r/with-let [confirm* (r/atom nil)]
    (let [menu-items [{:id :remove-image
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
        [:> TextField
         {:className "prompt-input"
          :multiline true
          :autoFocus true
          :minRows 2
          :maxRows 14
          :fullWidth true
          :variant "filled"
          :value (or frame-input "")
          :placeholder "Describe this frame..."
          :on-focus controls/on-frame-editor-focus
          :on-key-down (controls/on-frame-editor-keydown frameId)
          :on-change (controls/on-frame-editor-change frameId true)
          :InputProps #js {:disableUnderline true}}]
        (when (seq (or error ""))
          [:div.error-line (str "Last error: " error)])]
       [:> Stack {:className "prompt-controls"
                  :spacing 1}
        [:> Tooltip {:title "Generate (Cmd/Ctrl+Enter)"}
         [:> IconButton
          {:className "prompt-generate-btn"
           :aria-label "Generate"
           :on-click (controls/on-frame-send-click frameId)}
          [:> SendRoundedIcon]]]
        [action-menu/action-menu
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
