(ns webapp.components.chapter-actions
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.chapter-menu :as chapter-menu]
            [webapp.components.confirm-dialog :as confirm-dialog]))

(defn chapter-actions [{:keys [entity-id entity-name entity-description entity-label singular-label]}]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)]
    (let [label (or singular-label "chapter")
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          display-name (or entity-name "")
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
                              :label "Open chapter page"})
                       true
                       (conj {:id :rename-entity
                              :label (str "Edit " label)}))
          items (cond-> base-items
                  (pos? empty-frame-count)
                  (conj {:id :delete-empty-frames
                         :label "Delete empty frames"
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
       [:div.chapter-header-actions
        [chapter-menu/chapter-menu
         {:title (str title-case-label " actions")
          :aria-label (str title-case-label " actions")
          :button-class "chapter-menu-trigger"
          :items items
          :on-select (fn [item]
                       (case (:id item)
                         :open-chapter-page
                         (rf/dispatch [:navigate-chapter-page entity-id])
                         :rename-entity
                         (rf/dispatch [:start-entity-edit entity-label entity-id display-name (or entity-description "")])
                         (reset! confirm* item)))}]]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]])))
