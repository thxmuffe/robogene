(ns webapp.components.edit-db-item
  (:require ["@mantine/core" :refer [ActionIcon TextInput Textarea]]
            ["react-icons/fa6" :refer [FaCheck FaXmark]]))

(defn stop-propagation! [e]
  (.stopPropagation e))

(defn halt! [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn edit-db-item-actions [opts]
  (let [{:keys [on-submit on-cancel submit-disabled? extra-actions class-name]} opts]
    [:div {:className (or class-name "chapter-edit-actions")}
     [:> ActionIcon
      {:aria-label "Submit"
       :title "Submit"
       :variant "filled"
       :radius "xl"
       :disabled (true? submit-disabled?)
       :onClick on-submit}
      [:> FaCheck]]
     [:> ActionIcon
      {:aria-label "Cancel"
       :title "Cancel"
       :variant "subtle"
       :radius "xl"
       :onClick on-cancel}
      [:> FaXmark]]
     extra-actions]))

(defn edit-db-item [opts]
  (let [{:keys [class-name
                show-name?
                name-value
                on-name-change
                name-props
                description-value
                on-description-change
                description-props
                description-shortcuts?
                on-description-key-down
                stop-description-key-propagation?
                on-submit
                on-cancel
                submit-disabled?
                actions-class
                extra-actions]} opts
        shortcuts? (not (false? description-shortcuts?))
        name-key-down (:onKeyDown name-props)
        on-name-input-key-down (fn [e]
                                 (let [key (.-key e)
                                       escape? (= "Escape" key)
                                       enter? (= "Enter" key)
                                       submit? (and enter? (or (.-metaKey e) (.-ctrlKey e)))]
                                   (cond
                                     escape?
                                     nil

                                     (and shortcuts? submit?)
                                     (do
                                       (halt! e)
                                       (when on-submit
                                         (on-submit e)))

                                     name-key-down
                                     (name-key-down e)

                                     :else
                                     (stop-propagation! e))))
        name-input (merge {:value (or name-value "")
                           :onChange (fn [e]
                                       (when on-name-change
                                         (on-name-change (.. e -target -value))))
                           :onKeyDown on-name-input-key-down}
                          (dissoc (or name-props {}) :onKeyDown))
        description-input (merge {:value (or description-value "")
                                  :onChange (fn [e]
                                              (when on-description-change
                                                (on-description-change (.. e -target -value))))
                                  :onKeyDown (fn [e]
                                               (let [key (.-key e)
                                                     escape? (= "Escape" key)
                                                     enter? (= "Enter" key)
                                                     submit? (and enter? (or (.-metaKey e) (.-ctrlKey e)))]
                                                 (cond
                                                   escape?
                                                   nil

                                                   (and shortcuts? submit?)
                                                   (do
                                                     (halt! e)
                                                     (when on-submit
                                                       (on-submit e)))

                                                   on-description-key-down
                                                   (on-description-key-down e)

                                                   (not (false? stop-description-key-propagation?))
                                                   (stop-propagation! e)

                                                   :else nil)))}
                                 (or description-props {}))]
    [:div {:className (if (seq class-name)
                        (str "edit-db-item " class-name)
                        "edit-db-item")}
     (when show-name?
       [:> TextInput name-input])
     [:div.edit-db-item-description-row
      [:> Textarea description-input]
      [edit-db-item-actions
       {:on-submit on-submit
        :on-cancel on-cancel
        :submit-disabled? submit-disabled?
        :extra-actions extra-actions
        :class-name actions-class}]]]))
