(ns webapp.shared.controls
  (:require [re-frame.core :as rf]))

(def new-chapter-frame-id "__new_chapter__")

(defn activate-frame! [frame-id]
  (rf/dispatch [:set-active-frame frame-id]))

(defn navigate-frame!
  ([chapter-id frame-id]
   (navigate-frame! chapter-id frame-id nil))
  ([chapter-id frame-id from-page]
  (activate-frame! frame-id)
  (rf/dispatch [:navigate-frame chapter-id frame-id from-page])))

(defn open-new-chapter-panel! []
  (activate-frame! new-chapter-frame-id)
  (rf/dispatch [:set-new-chapter-panel-open true]))

(defn register-global-listeners! []
  (.addEventListener js/window "focus"
                     (fn [_]
                       (rf/dispatch [:force-refresh])))
  (.addEventListener js/window "hashchange"
                     (fn [_]
                       (rf/dispatch [:hash-changed (.-hash js/location)])))
  (.addEventListener js/document "visibilitychange"
                     (fn [_]
                       (when-not (.-hidden js/document)
                         (rf/dispatch [:force-refresh])))))

(rf/reg-fx
 :scroll-frame-into-view
 (fn [frame-id]
   (when (seq (or frame-id ""))
     (when-let [el (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"]"))]
       (.scrollIntoView el #js {:behavior "smooth"
                                :block (if (= frame-id "__new_chapter__") "center" "nearest")
                                :inline "nearest"})))))
