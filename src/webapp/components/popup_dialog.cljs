(ns webapp.components.popup-dialog
  (:require [webapp.shared.ui.interaction :as interaction]
            ["@mui/material/Dialog" :default Dialog]))

(defn popup-dialog [{:keys [open on-close]} & children]
  (into
   [:> Dialog {:open (boolean open)
               :on-close on-close
               :on-click interaction/stop!
               :on-pointer-down interaction/stop!}]
   children))
