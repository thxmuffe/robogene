(ns webapp.components.popup-dialog
  (:require [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Modal]]))

(defn popup-dialog [{:keys [open on-close]
                     :or {open false}
                     :as props}
                    & children]
  (into
   [:> Modal (merge (dissoc props :open)
                    {:opened (boolean open)
                     :onClose on-close
                     :withCloseButton false
                     :onClick interaction/stop!
                     :onPointerDown interaction/stop!})]
   children))
