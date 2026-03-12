(ns webapp.components.waterfall-row
  (:require [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon Menu Tooltip]]
            ["react-icons/fa6" :refer [FaEllipsisVertical]]))

(def inline-action-size 38)
(def inline-action-gap 4)

(defn- action-icon [icon]
  (when icon
    (r/as-element [:> icon])))

(defn- visible-prefix-count [container-width action-count mandatory-count]
  (let [slot-width (+ inline-action-size inline-action-gap)
        capacity (max mandatory-count
                      (int (js/Math.floor (/ (+ (max 0 (or container-width 0)) inline-action-gap)
                                             slot-width))))]
    (if (<= action-count capacity)
      action-count
      (max mandatory-count (dec capacity)))))

(defn waterfall-row
  [{:keys [actions
           class-name
           prefix-content
           mandatory-count
           menu-title
           menu-aria-label
           on-action-pointer-down]
    :or {mandatory-count 0}}]
  (r/with-let [container-el* (r/atom nil)
               container-width* (r/atom 0)
               observer* (atom nil)
               sync-width! (fn []
                             (when-let [el @container-el*]
                               (let [host (or (.-parentElement el) el)]
                                 (reset! container-width* (.-clientWidth host)))))
               set-container-ref! (fn [el]
                                    (when-let [observer @observer*]
                                      (.disconnect observer)
                                      (reset! observer* nil))
                                    (reset! container-el* el)
                                    (when el
                                      (sync-width!)
                                      (let [observer (js/ResizeObserver.
                                                      (fn [_ _]
                                                        (sync-width!)))]
                                        (.observe observer el)
                                        (reset! observer* observer))))]
    (let [actions (vec (or actions []))
          visible-count (if (pos? @container-width*)
                          (visible-prefix-count @container-width* (count actions) mandatory-count)
                          (count actions))
          visible-actions (subvec actions 0 (min visible-count (count actions)))
          overflow-actions (subvec actions (min visible-count (count actions)))]
      [:div {:className (if (seq class-name)
                          (str "waterfall-row " class-name)
                          "waterfall-row")
             :ref set-container-ref!
             :onMouseDownCapture (fn [_]
                                   (when on-action-pointer-down
                                     (on-action-pointer-down)))
             :onPointerDownCapture (fn [_]
                                     (when on-action-pointer-down
                                       (on-action-pointer-down)))}
       [:div.waterfall-row-inline
        prefix-content
        (for [{:keys [id label icon on-select disabled? variant color class-name]} visible-actions]
          ^{:key (str "inline-action-" id)}
          [:> Tooltip {:label label}
           [:> ActionIcon
            {:aria-label label
             :title label
             :size inline-action-size
             :variant (or variant "subtle")
             :color color
             :className class-name
             :radius "xl"
             :disabled (true? disabled?)
             :onClick (fn [e]
                        (interaction/halt! e)
                        (when (and on-select (not disabled?))
                          (on-select e)))}
            (action-icon icon)]])]
       (when (seq overflow-actions)
         [:> Menu {:withinPortal true
                   :position "bottom-end"
                   :shadow "md"}
          [:> (.-Target Menu)
           [:> Tooltip {:label (or menu-title "More actions")}
            [:> ActionIcon
             {:className "waterfall-row-trigger"
              :aria-label (or menu-aria-label menu-title "More actions")
              :title (or menu-title "More actions")
              :size inline-action-size
              :variant "subtle"
              :radius "xl"
              :onMouseDown interaction/prevent!
              :onPointerDown interaction/prevent!}
             [:> FaEllipsisVertical]]]]
          [:> (.-Dropdown Menu)
           (for [{:keys [id label icon on-select disabled? color]} overflow-actions]
             ^{:key (str "overflow-action-" id)}
             [:> (.-Item Menu)
              {:leftSection (action-icon icon)
               :color color
               :disabled (true? disabled?)
               :onClick (fn [e]
                          (interaction/halt! e)
                          (when (and on-select (not disabled?))
                            (on-select e)))}
              label])]])])
    (finally
      (when-let [observer @observer*]
        (.disconnect observer)))))
