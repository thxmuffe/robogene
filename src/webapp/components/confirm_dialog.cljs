(ns webapp.components.confirm-dialog
  (:require [reagent.core :as r]
            [webapp.components.popup-dialog :as popup-dialog]
            ["@mantine/core" :refer [Button Group]]))

(defn confirm-button-color [confirm-color]
  (case confirm-color
    "error" "red"
    "secondary" "orange"
    "primary" "blue"
    "blue"))

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
         [:h3.confirm-dialog-title title]
         [:p.confirm-dialog-text text]
         [:> Group {:justify "flex-end" :mt "md"}
          [:> Button {:variant "subtle"
                      :onClick (fn [_] (on-cancel))}
           "Cancel"]
          [:> Button {:variant "filled"
                      :color (confirm-button-color confirm-color)
                      :disabled @submitting*
                      :onClick (fn [_]
                                 (when-not @submitting*
                                   (reset! submitting* true)
                                   (on-confirm)))}
           confirm-label]]]))))
