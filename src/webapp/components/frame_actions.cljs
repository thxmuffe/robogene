(ns webapp.components.frame-actions
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Stack" :default Stack]
            ["@mui/material/Dialog" :default Dialog]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogActions" :default DialogActions]))

(defonce confirm-state* (r/atom nil))

(defn open-confirm! [payload]
  (reset! confirm-state* payload))

(defn close-confirm! []
  (reset! confirm-state* nil))

(defn confirm-dialog []
  (let [{:keys [title text confirm-label confirm-color on-confirm]} @confirm-state*]
    [:> Dialog {:open (boolean @confirm-state*)
                :on-close close-confirm!}
     [:> DialogTitle (or title "Confirm action")]
     [:> DialogContent
      [:> DialogContentText (or text "")]]
     [:> DialogActions
      [:> Button {:variant "text"
                  :on-click close-confirm!}
       "Cancel"]
      [:> Button {:variant "contained"
                  :color (or confirm-color "primary")
                  :on-click (fn []
                              (close-confirm!)
                              (when (fn? on-confirm)
                                (on-confirm)))}
       (or confirm-label "Confirm")]]]))

(defn frame-action-button [{:keys [frameId status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))
        hint (if busy?
               (if (= status "processing") "Generating..." "Queued...")
               (if has-image? "Regenerate frame" "Generate frame"))
        label (if busy?
                (if (= status "processing") "Working" "Queued")
                (if has-image? "Regen" "Gen"))
        icon (if busy?
               (if (= status "processing") "..." "o")
               (if has-image? "R" "+"))]
    [:> Button
     {:className "btn btn-primary btn-small"
      :variant "contained"
      :color "secondary"
      :size "small"
      :aria-label hint
      :disabled busy?
      :on-mouse-down #(.stopPropagation %)
      :on-click (fn [e]
                  (.stopPropagation e)
                  (rf/dispatch [:generate-frame frameId]))}
     [:span.btn-icon icon]
     [:span.btn-hint label]]))

(defn clear-image-button [{:keys [frameId status imageDataUrl]}]
  (let [busy? (or (= status "queued") (= status "processing"))
        has-image? (not (str/blank? (or imageDataUrl "")))]
    (when has-image?
      [:> Button
       {:className "btn btn-secondary btn-small"
        :variant "contained"
        :color "primary"
        :size "small"
        :aria-label "Remove image"
        :disabled busy?
        :on-click (fn [e]
                    (.stopPropagation e)
                    (open-confirm! {:title "Remove image from this frame?"
                                    :text "The frame and its description will stay."
                                    :confirm-label "Remove image"
                                    :confirm-color "primary"
                                    :on-confirm #(rf/dispatch [:clear-frame-image frameId])}))}
       [:span.btn-icon "x"]
       [:span.btn-hint "Remove"]])))

(defn delete-frame-button [{:keys [frameId]}]
  [:> Button
   {:className "btn btn-danger btn-small"
    :variant "contained"
   :color "error"
    :size "small"
    :aria-label "Delete frame"
    :on-click (fn [e]
                (.stopPropagation e)
                (open-confirm! {:title "Delete this frame?"
                                :text "This cannot be undone."
                                :confirm-label "Delete"
                                :confirm-color "error"
                                :on-confirm #(rf/dispatch [:delete-frame frameId])}))}
   [:span.btn-icon "!"]
   [:span.btn-hint "Delete"]])

(defn frame-actions-row [frame editable?]
  [:<>
   (when editable?
     [:> Stack {:className "frame-actions"
                :direction "row"
                :spacing 1}
      [frame-action-button frame]
      [clear-image-button frame]
      [delete-frame-button frame]])
   [confirm-dialog]])
