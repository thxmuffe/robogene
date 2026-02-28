(ns webapp.components.confirm-dialog
  (:require [webapp.components.popup-dialog :as popup-dialog]
            ["@mui/material/DialogTitle" :default DialogTitle]
            ["@mui/material/DialogContent" :default DialogContent]
            ["@mui/material/DialogContentText" :default DialogContentText]
            ["@mui/material/DialogActions" :default DialogActions]
            ["@mui/material/Button" :default Button]))

(defn confirm-dialog [{:keys [item on-cancel on-confirm]}]
  (when item
    (let [{:keys [title text confirm-label confirm-color]} (:confirm item)]
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
                    :on-click (fn [_] (on-confirm))}
         confirm-label]]])))
