(ns webapp.components.chapter-actions
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.chapter-menu :as chapter-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]))

(defn chapter-actions [{:keys [entity-id entity-name entity-description entity-label singular-label]}]
  (r/with-let [confirm* (r/atom nil)]
    (let [label (or singular-label "chapter")
          display-name (or entity-name "")
          entity-label (or entity-label "chapter")
          title-case-label (str (str/upper-case (subs entity-label 0 1)) (subs entity-label 1))
          items [{:id :rename-entity
                  :label (str "Edit " label)}
                 {:id :delete-entity
                  :label (str "Delete " label)
                  :confirm {:title (str "Delete this " label "?")
                            :text (str "This deletes all frames in this " label ".")
                            :confirm-label (str "Delete " label)
                            :confirm-color "error"}
                  :dispatch-event [(if (= "character" (str entity-label))
                                     :delete-character
                                     :delete-chapter)
                                   entity-id]}]
          selected-item @confirm*]
      [:<>
       [chapter-menu/chapter-menu
        {:title (str title-case-label " actions")
         :aria-label (str title-case-label " actions")
         :button-class "chapter-menu-trigger"
         :items items
         :on-select (fn [item]
                      (if (= :rename-entity (:id item))
                        (rf/dispatch [:start-entity-edit entity-label entity-id display-name (or entity-description "")])
                        (reset! confirm* item)))}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]])))
