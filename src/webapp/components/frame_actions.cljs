(ns webapp.components.frame-actions
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            ["@mui/material/Button" :default Button]
            ["@mui/material/Stack" :default Stack]
            ["sweetalert2" :as Swal]))

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
                    (-> (.fire Swal
                              (clj->js {:title "Remove image from this frame?"
                                        :text "The frame and its description will stay."
                                        :icon "warning"
                                        :showCancelButton true
                                        :confirmButtonText "Remove image"
                                        :cancelButtonText "Cancel"
                                        :confirmButtonColor "#20639b"}))
                        (.then (fn [result]
                                 (when (true? (.-isConfirmed result))
                                   (rf/dispatch [:clear-frame-image frameId]))))))}
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
                (-> (.fire Swal
                          (clj->js {:title "Delete this frame?"
                                    :text "This cannot be undone."
                                    :icon "warning"
                                    :showCancelButton true
                                    :confirmButtonText "Delete"
                                    :cancelButtonText "Cancel"
                                    :confirmButtonColor "#8b1e3f"}))
                    (.then (fn [result]
                             (when (true? (.-isConfirmed result))
                               (rf/dispatch [:delete-frame frameId]))))))}
   [:span.btn-icon "!"]
   [:span.btn-hint "Delete"]])

(defn frame-actions-row [frame editable?]
  (when editable?
    [:> Stack {:className "frame-actions"
               :direction "row"
               :spacing 1}
     [frame-action-button frame]
     [clear-image-button frame]
     [delete-frame-button frame]]))
