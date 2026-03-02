(ns webapp.components.chapter-actions
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.chapter-menu :as chapter-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]))

(defn chapter-actions [{:keys [chapter-id chapter-name]}]
  (r/with-let [confirm* (r/atom nil)]
    (let [items [{:id :rename-chapter
                  :label "Rename chapter"}
                 {:id :delete-chapter
                  :label "Delete chapter"
                  :confirm {:title "Delete this chapter?"
                            :text "This deletes all frames in this chapter."
                            :confirm-label "Delete chapter"
                            :confirm-color "error"}
                  :dispatch-event [:delete-chapter chapter-id]}]
          selected-item @confirm*]
      [:<>
       [chapter-menu/chapter-menu
        {:title "Chapter actions"
         :aria-label "Chapter actions"
         :button-class "chapter-menu-trigger"
         :items items
         :on-select (fn [item]
                      (if (= :rename-chapter (:id item))
                        (rf/dispatch [:start-chapter-name-edit chapter-id chapter-name])
                        (reset! confirm* item)))}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]])))
