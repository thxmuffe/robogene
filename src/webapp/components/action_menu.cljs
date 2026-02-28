(ns webapp.components.action-menu
  (:require [reagent.core :as r]
            ["@mui/material/IconButton" :default IconButton]
            ["@mui/material/Menu" :default Menu]
            ["@mui/material/MenuItem" :default MenuItem]
            ["@mui/material/Tooltip" :default Tooltip]
            ["@mui/icons-material/MoreVert" :default MoreVertIcon]))

(defn stop-event! [e]
  (.stopPropagation e))

(defn action-menu [{:keys [title aria-label button-class items on-select]}]
  (r/with-let [menu-anchor* (r/atom nil)]
    (let [open-menu! (fn [e]
                       (stop-event! e)
                       (reset! menu-anchor* (.-currentTarget e)))
          close-menu! (fn []
                        (reset! menu-anchor* nil))
          on-item-click (fn [item]
                          (fn [e]
                            (stop-event! e)
                            (close-menu!)
                            (on-select item)))]
      [:<>
       [:> Tooltip {:title (or title "Actions")}
        [:> IconButton
         {:className (or button-class "prompt-actions-trigger")
          :aria-label (or aria-label "Actions")
          :on-click open-menu!}
         [:> MoreVertIcon]]]
       [:> Menu {:anchorEl @menu-anchor*
                 :open (boolean @menu-anchor*)
                 :on-close close-menu!}
        (for [{:keys [id label] :as item} items]
          ^{:key (str "menu-item-" id)}
          [:> MenuItem
           {:on-click (on-item-click item)}
           label])]])))
