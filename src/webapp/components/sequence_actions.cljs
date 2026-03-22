(ns webapp.components.sequence-actions
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [webapp.dialog.confirm-dialog :as confirm-dialog]
            [webapp.dialog.link-entities-dialog :as link-entities-dialog]
            [webapp.dialog.popup-dialog :as popup-dialog]
            [webapp.dialog.upload-dialog :as upload-dialog]
            [webapp.components.waterfall-row :as waterfall-row]
            ["react-icons/fa6" :refer [FaArrowUpRightFromSquare FaBroom FaDownload FaImages FaPlus FaShuffle FaTrashCan]]
            ["@mantine/core" :refer [Button NativeSelect Stack Text]]))

(def vanity-role-options
  [{:value "saga" :label "Saga"}
   {:value "roster" :label "Roster"}
   {:value "chapter" :label "Chapter"}
   {:value "character" :label "Character"}])

(defn- confirm-item [id title text confirm-label on-confirm]
  {:id id
   :confirm {:title title
             :text text
             :confirm-label confirm-label
             :confirm-color "error"}
   :on-confirm on-confirm})

(defn sequence-actions [entity {:keys [cancel-ui-token
                                       link-entities-state
                                       linkable-entities
                                       entities-map
                                       frames
                                       on-open-page
                                       on-set-role
                                       on-delete
                                       on-open-link-entities
                                       on-select-link-entity
                                       on-link-entities-search
                                       on-link-entities-sort
                                       on-link-entities-role-filters
                                       on-close-link-entities-dialog
                                       on-upload-images
                                       on-delete-empty-frames]}]
  (r/with-let [confirm* (r/atom nil)
               seen-cancel-token* (r/atom nil)
               upload-open?* (r/atom false)
               set-role-open?* (r/atom false)
               role-draft* (r/atom nil)]
    (let [entity-id (:id entity)
          role (some-> (:vanityRole entity) str str/lower-case)
          owner-type (if (= role "character") "character" "saga")
          sequence-of-sequence? (contains? #{"saga" "roster"} role)
          empty-frames (filterv (fn [frame]
                                  (str/blank? (or (:imageUrl frame) "")))
                                (or frames []))
          empty-frame-count (count empty-frames)
          frame-sequence? (and (not sequence-of-sequence?)
                               (contains? #{"chapter" "character"} role))
          label (case role
                  "saga" "saga"
                  "chapter" "chapter"
                  "character" "character"
                  "sequence")
          title-case-label (str/capitalize label)
          link-dialog-open? (and (:open? link-entities-state)
                                 (= entity-id (get-in link-entities-state [:target :parent-id])))
          items [{:id :open-page
                  :label "Open page"
                  :icon FaArrowUpRightFromSquare
                  :color "indigo"
                  :on-select (fn [_]
                               (when on-open-page
                                 (on-open-page)))}
                 {:id :set-role
                  :label "Set role"
                  :icon FaShuffle
                  :color "violet"
                  :on-select (fn [_]
                               (reset! role-draft* role)
                               (reset! set-role-open?* true))}
                 {:id :download-sequence
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
                                                 on-delete)))}
                 {:id :link-sequence
                  :label "Link"
                  :icon FaPlus
                  :color "grape"
                  :on-select (fn [_]
                               (when on-open-link-entities
                                 (on-open-link-entities entity-id)))}
                 {:id :upload-images
                  :label "Upload images"
                  :icon FaImages
                  :color "blue"
                  :disabled? (or sequence-of-sequence?
                                 (not= role "chapter"))
                  :on-select (fn [_]
                               (when (and (not sequence-of-sequence?)
                                          (= role "chapter")
                                          on-upload-images)
                                 (reset! upload-open?* true)))}
                 {:id :delete-empty-frames
                  :label "Delete empty frames"
                  :icon FaBroom
                  :color "orange"
                  :disabled? (or sequence-of-sequence?
                                 (not frame-sequence?)
                                 (zero? empty-frame-count))
                  :on-select (fn [_]
                               (when (and (not sequence-of-sequence?)
                                          frame-sequence?
                                          (pos? empty-frame-count))
                                 (reset! confirm* (confirm-item
                                                   :delete-empty-frames
                                                   "Delete empty frames?"
                                                   (str "This deletes " empty-frame-count
                                                        " frame" (when (not= 1 empty-frame-count) "s")
                                                        " without an image in this " label ".")
                                                   "Delete empty frames"
                                                   #(on-delete-empty-frames entity-id owner-type)))))}]
          selected-item @confirm*]
      (when (not= cancel-ui-token @seen-cancel-token*)
        (reset! seen-cancel-token* cancel-ui-token)
        (reset! confirm* nil)
        (reset! upload-open?* false)
        (reset! set-role-open?* false))
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
                       (when-let [on-confirm (:on-confirm selected-item)]
                         (on-confirm))
                       (reset! confirm* nil))}]
       (when (= role "chapter")
         [upload-dialog/upload-dialog
         {:open @upload-open?*
          :on-close #(reset! upload-open?* false)
           :on-submit-many #(when on-upload-images
                              (on-upload-images entity-id %))
           :multiple? true
           :title "Upload images"}])
       [popup-dialog/popup-dialog
        {:open @set-role-open?*
         :on-close #(reset! set-role-open?* false)
         :title "Set role"
         :centered true
         :size "sm"}
        [:> Stack {:gap "sm"}
         [:> Text {:size "sm"}
          "Change how this entity is interpreted in navigation and collection pages."]
         [:> NativeSelect
          {:label "Vanity role"
           :value (or @role-draft* role "")
           :data (clj->js vanity-role-options)
           :onChange #(reset! role-draft* (.. % -target -value))}]
         [:div {:style {:display "flex"
                        :justify-content "flex-end"
                        :gap "8px"}}
          [:> Button
           {:variant "default"
            :onClick #(reset! set-role-open?* false)}
           "Cancel"]
          [:> Button
           {:onClick #(when-let [next-role (some-> @role-draft* str str/trim str/lower-case not-empty)]
                        (when on-set-role
                          (on-set-role next-role))
                        (reset! set-role-open?* false))
            :disabled (or (str/blank? (or @role-draft* ""))
                          (= (some-> @role-draft* str str/trim str/lower-case)
                             role))}
          "Save"]]]
       ]
       [link-entities-dialog/link-entities-dialog
        {:open link-dialog-open?
         :title "Link entities"
         :search (:search link-entities-state)
         :sort (:sort link-entities-state)
         :role-filters (:role-filters link-entities-state)
         :entities linkable-entities
         :entities-map entities-map
         :on-search #(when on-link-entities-search (on-link-entities-search %))
         :on-sort #(when on-link-entities-sort (on-link-entities-sort %))
         :on-role-filters #(when on-link-entities-role-filters (on-link-entities-role-filters %))
         :on-close #(when on-close-link-entities-dialog (on-close-link-entities-dialog))
         :on-select #(when on-select-link-entity (on-select-link-entity %))
         :empty-label "No sequence entities match this search."}]])))
