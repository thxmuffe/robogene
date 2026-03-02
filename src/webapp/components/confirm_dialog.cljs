(ns webapp.components.confirm-dialog
  (:require [reagent.core :as r]
            [webapp.components.popup-dialog :as popup-dialog]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/Button" :default Button]))

(defn confirm-dialog [{:keys [item on-cancel on-confirm]}]
  (r/with-let [submitting* (r/atom false)
               item-id* (r/atom nil)]
    (when item
      (let [{:keys [id confirm]} item
            {:keys [title text confirm-label confirm-color]} confirm]
        (when (not= @item-id* id)
          (reset! item-id* id)
          (reset! submitting* false))
        [popup-dialog/popup-dialog {:open true
                                    :on-close on-cancel}
         [:> DialogTitle title]
         [:> DialogContent
          [:> DialogContentText text]]
         [:> DialogActions
          [:> Button {:variant "text"
                      :on-click (fn [_] (on-cancel))}
           "Cancel"]
          [:> Button {:variant "contained"
                      :color confirm-color
                      :disabled @submitting*
                      :on-click (fn [_]
                                  (when-not @submitting*
                                    (reset! submitting* true)
                                    (on-confirm)))}
           confirm-label]]]))))
