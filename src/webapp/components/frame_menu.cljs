(ns webapp.components.frame-menu
  (:require ["@mantine/core" :refer [ActionIcon Menu]]
            ["react-icons/fa6" :refer [FaEllipsisVertical]]))

(defn frame-menu [{:keys [title aria-label button-class items on-select]}]
  [:> Menu {:withinPortal true
            :position "bottom-end"
            :shadow "md"}
   [:> (.-Target Menu)
    [:> ActionIcon
     {:className (or button-class "frame-menu-trigger")
      :aria-label (or aria-label title "Actions")
      :title (or title "Actions")
      :tabIndex -1
      :variant "subtle"
      :radius "xl"}
     [:> FaEllipsisVertical]]]
   [:> (.-Dropdown Menu)
    (for [{:keys [id label] :as item} items]
      ^{:key (str "menu-item-" id)}
      [:> (.-Item Menu)
       {:onClick (fn [_] (on-select item))}
       label])]])
