(ns webapp.components.popup-dialog
  (:require ["@mui/material/Dialog" :default Dialog]))

(defn stop-event! [e]
  (.stopPropagation e))

(defn popup-dialog [{:keys [open on-close]} & children]
  (into
   [:> Dialog {:open (boolean open)
               :on-close on-close
               :on-click stop-event!
               :on-pointer-down stop-event!}]
   children))
