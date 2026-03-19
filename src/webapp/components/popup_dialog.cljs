(ns webapp.components.popup-dialog
  (:require [clojure.string :as str]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Modal]]))

(defn popup-dialog [{:keys [open on-close overlay-props className]
                     :or {open false}
                     :as props}
                    & children]
  (let [modal-class (str/trim (str "app-dialog " (or className "")))]
    (into
     [:> Modal (merge (dissoc props :open :overlay-props :className)
                      {:className modal-class
                       :opened (boolean open)
                       :onClose on-close
                       :withCloseButton false
                       :overlayProps overlay-props
                       :onClick interaction/stop!
                       :onPointerDown interaction/stop!})]
     children)))
