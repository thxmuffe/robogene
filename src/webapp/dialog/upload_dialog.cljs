(ns webapp.dialog.upload-dialog
  (:require [reagent.core :as r]
            [webapp.components.upload-images :as upload-images]
            [webapp.dialog.popup-dialog :as popup-dialog]
            ["@mantine/core" :refer [ActionIcon]]
            ["react-icons/fa6" :refer [FaXmark]]))

(defn upload-dialog [{:keys [open on-close active-frame-id] :as props}]
  (r/with-let [spotlight-style* (r/atom nil)
               was-open?* (r/atom false)]
    (let [sync-spotlight! (fn []
                            (if-not (seq (or active-frame-id ""))
                              (reset! spotlight-style* nil)
                              (if-let [frame-el (.querySelector js/document (str ".frame[data-frame-id=\"" active-frame-id "\"]"))]
                                (let [rect (.getBoundingClientRect frame-el)
                                      x (+ (.-left rect) (/ (.-width rect) 2))
                                      y (+ (.-top rect) (/ (.-height rect) 2))
                                      radius (max 44 (min 88 (* 0.18 (max (.-width rect) (.-height rect)))))]
                                  (reset! spotlight-style*
                                          {:--spotlight-x (str x "px")
                                           :--spotlight-y (str y "px")
                                           :--spotlight-radius (str radius "px")}))
                                (reset! spotlight-style* nil))))]
      (when (and open (not @was-open?*))
        (sync-spotlight!)
        (js/requestAnimationFrame
         (fn []
           (sync-spotlight!)
           (js/setTimeout sync-spotlight! 60))))
      (reset! was-open?* open)
      [popup-dialog/popup-dialog
       {:open open
        :on-close on-close
        :className "upload-dialog-modal"
        :overlay-props {:className (when @spotlight-style* "popup-dialog-overlay-spotlight")
                        :style @spotlight-style*}
        :size "auto"
        :padding 0}
       [:div {:style {:position "relative"}}
        [:> ActionIcon
         {:className "upload-dialog-close"
          :aria-label "Close upload dialog"
          :title "Close upload dialog"
          :variant "subtle"
          :radius "xl"
          :onClick on-close}
         [:> FaXmark]]
        [upload-images/upload-images props]]])))
