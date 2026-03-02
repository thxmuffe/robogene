(ns webapp.components.frame-menu
  (:require [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon Menu MenuDropdown MenuItem MenuTarget Tooltip]]
            ["react-icons/fa6" :refer [FaEllipsisVertical]]))

(defn frame-menu [{:keys [title aria-label button-class items on-select]}]
  (r/with-let [menu-open?* (r/atom false)]
    (let [open-menu! (fn [e]
                       (interaction/stop! e)
                       (reset! menu-open?* true))
          close-menu! (fn []
                        (reset! menu-open?* false))
          on-item-click (fn [item]
                          (fn [e]
                            (interaction/stop! e)
                            (close-menu!)
                            (on-select item)))]
      [:> Menu {:opened @menu-open?*
                :onChange #(reset! menu-open?* %)
                :withinPortal true}
       [:> MenuTarget
        [:span
         [:> Tooltip {:label (or title "Actions")}
          [:> ActionIcon
           {:className (or button-class "frame-menu-trigger")
            :aria-label (or aria-label "Actions")
            :variant "subtle"
            :radius "xl"
            :onClick open-menu!}
           [:> FaEllipsisVertical]]]]]
       [:> MenuDropdown
        (for [{:keys [id label] :as item} items]
          ^{:key (str "menu-item-" id)}
          [:> MenuItem
           {:onClick (on-item-click item)}
           label])]])))
