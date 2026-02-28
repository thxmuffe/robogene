(ns webapp.components.prompt
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            ["@mui/material/Box" :default Box]
            ["@mui/material/Button" :default Button]
            ["@mui/material/TextField" :default TextField]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Menu" :default Menu]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/icons-material/SendRounded" :default SendRoundedIcon]
            ["@mui/icons-material/MoreVert" :default MoreVertIcon]))

(defn confirm-dialog [state close!]
  (let [{:keys [title text confirm-label confirm-color on-confirm]} @state]
    [:> Dialog {:open (boolean @state)
                :on-close close!}
     [:> DialogTitle (or title "Confirm action")]
     [:> DialogContent
      [:> DialogContentText (or text "")]]
     [:> DialogActions
      [:> Button {:variant "text"
                  :on-click close!}
       "Cancel"]
      [:> Button {:variant "contained"
                  :color (or confirm-color "primary")
                  :on-click (fn []
                              (close!)
                              (when (fn? on-confirm)
                                (on-confirm)))}
       (or confirm-label "Confirm")]]]))

(defn prompt-panel [{:keys [frameId status imageDataUrl error]} frame-input]
  (r/with-let [menu-anchor* (r/atom nil)
               confirm* (r/atom nil)
               touch-start-y* (r/atom nil)]
    (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))
          close-prompt! #(rf/dispatch [:set-frame-actions-open frameId false])
          open-menu! (fn [e]
                       (.stopPropagation e)
                       (reset! menu-anchor* (.-currentTarget e)))
          close-menu! (fn []
                        (reset! menu-anchor* nil))
          open-confirm! (fn [payload]
                          (reset! confirm* payload))
          close-confirm! (fn []
                           (reset! confirm* nil))]
      [:> Box {:className "prompt-panel"
               :on-touch-start (fn [e]
                                 (reset! touch-start-y* (some-> e .-touches (aget 0) .-clientY)))
               :on-touch-end (fn [e]
                               (when-let [start-y @touch-start-y*]
                                 (let [end-y (some-> e .-changedTouches (aget 0) .-clientY)]
                                   (when (and (number? end-y) (> (- end-y start-y) 70))
                                     (close-prompt!))))
                               (reset! touch-start-y* nil))}
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
          :on-key-down (controls/on-frame-editor-keydown frameId busy? true)
          :on-change (controls/on-frame-editor-change frameId true)
          :InputProps #js {:disableUnderline true}}]
        (when (and (seq (or error "")) (not busy?))
          [:div.error-line (str "Last error: " error)])]
       [:> Stack {:className "prompt-controls"
                  :spacing 1}
        [:> Tooltip {:title (if busy? "Generating..." "Generate (Cmd/Ctrl+Enter)")}
         [:> IconButton
          {:className "prompt-generate-btn"
           :aria-label "Generate"
           :disabled busy?
           :on-click (controls/on-frame-send-click frameId busy? true)}
          [:> SendRoundedIcon]]]
        [:> Tooltip {:title "Actions"}
          [:> IconButton
           {:className "prompt-actions-trigger"
            :aria-label "Actions"
            :on-click open-menu!}
           [:> MoreVertIcon]]]
        [:> Menu {:anchorEl @menu-anchor*
                  :open (boolean @menu-anchor*)
                  :on-close close-menu!}
         (when has-image?
           [:> MenuItem
            {:on-click (fn [e]
                         (.stopPropagation e)
                         (close-menu!)
                         (open-confirm! {:title "Remove image from this frame?"
                                         :text "The frame and its description will stay."
                                         :confirm-label "Remove image"
                                         :confirm-color "primary"
                                         :on-confirm #(rf/dispatch [:clear-frame-image frameId])}))}
            "Remove image"])
         [:> MenuItem
          {:on-click (fn [e]
                       (.stopPropagation e)
                       (close-menu!)
                       (open-confirm! {:title "Delete this frame?"
                                       :text "This cannot be undone."
                                       :confirm-label "Delete"
                                       :confirm-color "error"
                                       :on-confirm #(rf/dispatch [:delete-frame frameId])}))}
          "Delete frame"]]]
       [confirm-dialog confirm* close-confirm!]])))
