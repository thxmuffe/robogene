(ns webapp.components.sequence-actions
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.roster-button :as roster-button]
            [webapp.components.roster-select-dialog :as roster-select-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.shared.model :as model]
            ["react-icons/fa6" :refer [FaBroom FaDownload FaImages FaPlus FaTrashCan]]))

(defn- confirm-item [id title text confirm-label dispatch-event]
  {:id id
   :confirm {:title title
             :text text
             :confirm-label confirm-label
             :confirm-color "error"}
   :dispatch-event dispatch-event})

(defn sequence-actions [entity]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)
               upload-open?* (r/atom false)]
    (let [entity-id (:id entity)
          role (some-> (:vanityRole entity) str str/lower-case)
          cancel-ui-token @(rf/subscribe [:cancel-ui-token])
          roster-link-state @(rf/subscribe [:roster-link-state])
          rosters @(rf/subscribe [:rosters])
          owner-type (if (= role "character") "character" "saga")
          frames (when (not= role "saga")
                   @(rf/subscribe [:frames-for-owner owner-type entity-id]))
          empty-frames (filterv (fn [frame]
                                  (str/blank? (or (:imageUrl frame) "")))
                                (or frames []))
          empty-frame-count (count empty-frames)
          frame-sequence? (contains? #{"chapter" "character"} role)
          label (case role
                  "saga" "saga"
                  "chapter" "chapter"
                  "character" "character"
                  "sequence")
          title-case-label (str/capitalize label)
          roster-target (:target roster-link-state)
          roster-search (some-> (:search roster-link-state) str str/lower-case str/trim)
          roster-dialog-open? (and (= role "chapter")
                                   (:open? roster-link-state)
                                   (= entity-id (:chapter-id roster-target)))
          filtered-rosters (if (str/blank? (or roster-search ""))
                             rosters
                             (filterv (fn [roster]
                                        (let [title (some-> (:title roster) str str/lower-case)
                                              description (some-> (:description roster) str str/lower-case)]
                                          (or (str/includes? (or title "") roster-search)
                                              (str/includes? (or description "") roster-search))))
                                      rosters))
          roster-items (mapv (fn [roster]
                               ^{:key (:id roster)}
                               [roster-button/roster-button
                                {:label (model/primary-label roster)
                                 :description (some-> (:description roster) str/trim not-empty)
                                 :class-name "roster-select-option"
                                 :on-click #(rf/dispatch [:select-roster-link (:id roster)])}])
                             filtered-rosters)
          items [{:id :download-sequence
                  :label (str "Download " label)
                  :icon FaDownload
                  :color "teal"
                  :disabled? true}
                 {:id :delete-sequence
                  :label (str "Delete " label)
                  :icon FaTrashCan
                  :color "red"
                  :on-select (fn [_]
                               (reset! confirm* (confirm-item
                                                 :delete-sequence
                                                 (str "Delete this " label "?")
                                                 (if (= role "saga")
                                                   "This deletes all child sequences and frames in this saga."
                                                   (str "This deletes all frames in this " label "."))
                                                 (str "Delete " label)
                                                 [(case role
                                                    "saga" :delete-saga
                                                    "character" :delete-character
                                                    :delete-chapter)
                                                  entity-id])))}
                 {:id :link-sequence
                  :label "Link"
                  :icon FaPlus
                  :color "grape"
                  :disabled? (not= role "chapter")
                  :on-select (fn [_]
                               (when (= role "chapter")
                                 (rf/dispatch [:add-linked-chapter-roster entity-id])))}
                 {:id :upload-images
                  :label "Upload images"
                  :icon FaImages
                  :color "blue"
                  :disabled? (not= role "chapter")
                  :on-select (fn [_]
                               (when (= role "chapter")
                                 (reset! upload-open?* true)))}
                 {:id :delete-empty-frames
                  :label "Delete empty frames"
                  :icon FaBroom
                  :color "orange"
                  :disabled? (or (not frame-sequence?)
                                 (zero? empty-frame-count))
                  :on-select (fn [_]
                               (when (and frame-sequence? (pos? empty-frame-count))
                                 (reset! confirm* (confirm-item
                                                   :delete-empty-frames
                                                   "Delete empty frames?"
                                                   (str "This deletes " empty-frame-count
                                                        " frame" (when (not= 1 empty-frame-count) "s")
                                                        " without an image in this " label ".")
                                                   "Delete empty frames"
                                                   [:delete-empty-frames entity-id owner-type]))))}]
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false))
      [:<>
       [waterfall-row/waterfall-row
        {:class-name "chapter-header-actions-row"
         :actions items
         :action-size 44
         :mandatory-count 2
         :menu-title (str title-case-label " actions")
         :menu-aria-label (str title-case-label " actions")}]
       [confirm-dialog/confirm-dialog
        {:item selected-item
         :on-cancel #(reset! confirm* nil)
         :on-confirm (fn []
                       (when-let [event (:dispatch-event selected-item)]
                         (rf/dispatch event))
                       (reset! confirm* nil))}]
       (when (= role "chapter")
         [upload-dialog/upload-dialog
          {:open @upload-open?*
           :on-close #(reset! upload-open?* false)
           :on-submit-many #(rf/dispatch [:upload-chapter-images entity-id %])
           :multiple? true
           :title "Upload images"}])
       [roster-select-dialog/roster-select-dialog
        {:open roster-dialog-open?
         :title "Select roster"
         :search (:search roster-link-state)
         :on-search #(rf/dispatch [:roster-link-search-changed %])
         :on-close #(rf/dispatch [:close-roster-link-dialog])
         :on-create #(rf/dispatch [:create-roster-link])
         :items roster-items
         :empty-label "No rosters match this search."}]])))
