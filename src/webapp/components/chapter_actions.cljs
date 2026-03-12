(ns webapp.components.chapter-actions
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.components.confirm-dialog :as confirm-dialog]
            ["react-icons/fa6" :refer [FaArrowUpRightFromSquare FaBroom FaTrashCan]]))

(defn chapter-actions [{:keys [entity-id entity-label singular-label]}]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)]
    (let [label (or singular-label "chapter")
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          entity-label (or entity-label "chapter")
          owner-type (if (= "character" (str entity-label)) "character" "saga")
          frames @(rf/subscribe [:frames-for-owner owner-type entity-id])
          empty-frames (filterv (fn [frame]
                                  (str/blank? (or (:imageUrl frame) "")))
                                frames)
          empty-frame-count (count empty-frames)
          title-case-label (str (str/upper-case (subs entity-label 0 1)) (subs entity-label 1))
          base-items (cond-> []
                       (= "chapter" (str entity-label))
                       (conj {:id :open-chapter-page
                              :label "Open chapter page"
                              :icon "navigate"
                              :color "indigo"}))
          items (cond-> base-items
                  (pos? empty-frame-count)
                  (conj {:id :delete-empty-frames
                         :label "Delete empty frames"
                         :icon "broom"
                         :color "orange"
                         :confirm {:title "Delete empty frames?"
                                   :text (str "This deletes " empty-frame-count
                                              " frame" (when (not= 1 empty-frame-count) "s")
                                              " without an image in this " label ".")
                                   :confirm-label "Delete empty frames"
                                   :confirm-color "error"}
                         :dispatch-event [:delete-empty-frames entity-id owner-type]})
                  true
                  (conj {:id :delete-entity
                         :label (str "Delete " label)
                         :icon "trash"
                         :color "red"
                         :confirm {:title (str "Delete this " label "?")
                                   :text (str "This deletes all frames in this " label ".")
                                   :confirm-label (str "Delete " label)
                                   :confirm-color "error"}
                         :dispatch-event [(if (= "character" (str entity-label))
                                            :delete-character
                                            :delete-chapter)
                                          entity-id]}))
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil))
      [:<>
       [waterfall-row/waterfall-row
        {:class-name "chapter-header-actions-row"
         :actions (mapv (fn [item]
                          (assoc item
                                 :icon (case (:icon item)
                                         "navigate" FaArrowUpRightFromSquare
                                         "broom" FaBroom
                                         "trash" FaTrashCan
                                         nil)
                                 :on-select (fn [_]
                                              (case (:id item)
                                                :open-chapter-page
                                                (rf/dispatch [:navigate-chapter-page entity-id])
                                                (reset! confirm* item)))))
                        items)
         :mandatory-count 0
         :menu-title (str title-case-label " actions")
         :menu-aria-label (str title-case-label " actions")}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]])))
