(ns webapp.dialog.popup-dialog
  (:require [clojure.string :as str]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon Modal]]
            ["react-icons/fa6" :refer [FaXmark]]
            ))

(defn dialog-title-bar [{:keys [title on-close close-label class-name]}]
  [:div {:className (str/trim (str "slim-dialog-title-bar " (or class-name "")))}
   [:h3.slim-dialog-title (or title "Dialog")]
   [:> ActionIcon
    {:className "slim-dialog-close"
     :variant "subtle"
     :color "dark"
     :aria-label (or close-label "Close dialog")
     :title (or close-label "Close dialog")
     :onClick on-close}
    [:> FaXmark]]])

(defn slim-dialog-shell [{:keys [title on-close close-label class-name]} & children]
  (into
   [:div {:className (str/trim (str "slim-dialog " (or class-name "")))}
    [dialog-title-bar {:title title
                       :on-close on-close
                       :close-label close-label}]]
   children))

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
