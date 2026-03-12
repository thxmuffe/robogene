(ns webapp.components.db-item
  (:require ["@mantine/core" :refer [TextInput Textarea]]
            ["react-icons/fa6" :refer [FaCheck FaXmark]]
            [webapp.components.waterfall-row :as waterfall-row]))

(defn stop-propagation! [e]
  (.stopPropagation e))

(defn halt! [e]
  (.preventDefault e)
  (.stopPropagation e))

(defn db-item-actions [opts]
  (let [{:keys [on-submit on-cancel submit-disabled? action-items class-name]} opts
        actions (into [{:id :submit
                        :label "Submit"
                        :icon FaCheck
                        :disabled? (true? submit-disabled?)
                        :on-select on-submit}
                       {:id :cancel
                        :label "Cancel"
                        :icon FaXmark
                        :on-select on-cancel}]
                      (or action-items []))]
    [waterfall-row/waterfall-row
     {:class-name (or class-name "chapter-edit-actions")
      :actions actions
      :mandatory-count 2
      :menu-title "More actions"
      :menu-aria-label "More actions"}]))

(defn db-item [opts]
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
                action-items]} opts
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
                        (str "db-item " class-name)
                        "db-item")}
     (when show-name?
       [:> TextInput name-input])
     [:div.db-item-description-row
      [:> Textarea description-input]]
     [db-item-actions
      {:on-submit on-submit
       :on-cancel on-cancel
       :submit-disabled? submit-disabled?
       :action-items action-items
       :class-name actions-class}]]))
