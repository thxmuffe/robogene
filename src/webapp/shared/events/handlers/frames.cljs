(ns webapp.shared.events.handlers.frames
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.model :as model]
            [webapp.shared.store :as store]
            [webapp.shared.events.sync :as sync]))

(defn deleted-frame-label [frame]
  (let [description (str/trim (or (:description frame) ""))]
    (cond
      (seq description) (str "\"" description "\"")
      :else (or (:frameId frame) "frame"))))

(defn queue-command! [db status-message command]
  (sync/queue-command (store/apply-command-locally db command)
                      status-message
                      command))

(defn queue-entity-patch! [db entity-id patch success-status status-message]
  (let [entity (store/normalize-entity (get-in db [:entities (str entity-id)]))
        role (or (:vanityRole entity) "entity")
        command {:id (sync/next-command-id)
                 :kind :update-entity
                 :payload {:id entity-id
                           :patch patch}
                 :success-status (or success-status
                                     (str (str/capitalize (str role)) " updated."))}]
    (queue-command! db (or status-message (str "Updating " role "...")) command)))

(defn frame-entity [frame-id owner-id owner-type frame-number image-url image-status]
  {:id frame-id
   :vanityRole "frame"
   :title ""
   :description ""
   :children []
   :payload {:parentId owner-id
             :ownerType (or owner-type "saga")
             :frameNumber frame-number
             :imageUrl image-url
             :imageStatus image-status
             :error nil
             :createdAt (.toISOString (js/Date.))}})

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-drafts frame-id] value)))

(rf/reg-event-fx
 :generate-frame
 (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [generator (or (:selected-image-generator db)
                       (:default-image-generator db))]
     (if (str/blank? (or generator ""))
       {:db (assoc db :status "Select an image generator first.")}
       (let [direction (or provided-direction
                           (get-in db [:frame-drafts frame-id])
                           (:description (store/frame-by-id db frame-id))
                           "")
             command {:id (sync/next-command-id)
                      :kind :generate-frame
                      :payload {:frame-id frame-id
                                :direction direction
                                :generator generator
                                :without-roster false}
                      :success-status "Frame request queued."}]
         (sync/queue-command (store/apply-command-locally db command)
                             "Queueing frame..."
                             command))))))

(rf/reg-event-fx
 :generate-frame-without-roster
 (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [generator (or (:selected-image-generator db)
                       (:default-image-generator db))]
     (if (str/blank? (or generator ""))
       {:db (assoc db :status "Select an image generator first.")}
       (let [direction (or provided-direction
                           (get-in db [:frame-drafts frame-id])
                           (:description (store/frame-by-id db frame-id))
                           "")
             command {:id (sync/next-command-id)
                      :kind :generate-frame
                      :payload {:frame-id frame-id
                                :direction direction
                                :generator generator
                                :without-roster true}
                      :success-status "Frame request queued."}]
         (sync/queue-command (store/apply-command-locally db command)
                             "Queueing frame..."
                             command))))))

(rf/reg-event-fx
 :add-frame
 (fn [{:keys [db]} [_ owner-id owner-type]]
   (let [command-id (sync/next-command-id)
         owner-type (or owner-type "saga")
         local-frame-id (str "frame-" command-id)
         local-frame (assoc (store/build-local-frame local-frame-id owner-id owner-type)
                            :frameNumber (store/next-frame-number db owner-id owner-type))
         local-entity (frame-entity local-frame-id
                                    owner-id
                                    owner-type
                                    (:frameNumber local-frame)
                                    nil
                                    "draft")
         command {:id command-id
                  :kind :add-frame
                  :payload {:owner-id owner-id
                            :owner-type owner-type
                            :frame-id local-frame-id
                            :local-entity local-entity
                            :local-frame local-frame}
                  :success-status "Frame added."}]
     (queue-command! db "Adding frame..." command))))

(rf/reg-event-fx
 :upload-chapter-images
 (fn [{:keys [db]} [_ chapter-id image-data-urls]]
   (let [images (vec (or image-data-urls []))
         image-count (count images)]
     (if (or (str/blank? (or chapter-id ""))
             (zero? image-count))
       {:db db}
       {:db db
        :dispatch-n
        (mapv (fn [idx image-data-url]
                (let [frame-id (str "temp-upload-frame-" (.now js/Date) "-" idx)
                      frame-number (+ (store/next-frame-number db chapter-id "saga") idx)
                      local-frame (assoc (store/build-upload-frame db
                                                                  frame-id
                                                                  chapter-id
                                                                  image-data-url)
                                         :frameNumber frame-number)
                      command {:id (sync/next-command-id)
                               :kind :add-frame
                               :payload {:owner-id chapter-id
                                         :owner-type "saga"
                                         :frame-id frame-id
                                         :local-frame local-frame
                                         :local-entity (frame-entity frame-id
                                                                     chapter-id
                                                                     "saga"
                                                                     frame-number
                                                                     image-data-url
                                                                     "uploading")}
                               :success-status "Uploaded image."}]
                  [:queue-command-direct command
                   (str "Uploading " image-count " image" (when (not= 1 image-count) "s") "...")]))
              (range image-count)
              images)}))))

(rf/reg-event-fx
 :delete-frame
 (fn [{:keys [db]} [_ frame-id]]
   (let [frame (store/frame-by-id db frame-id)
         command {:id (sync/next-command-id)
                  :kind :delete-frame
                  :payload {:frame-id frame-id}
                  :success-status (str "Deleted " (deleted-frame-label frame) ".")}]
     (queue-command! db "Deleting frame..." command))))

(rf/reg-event-fx
 :delete-empty-frames
 (fn [{:keys [db]} [_ owner-id owner-type]]
   (let [empty-frame-ids (->> (model/frames-for-owner (:entities db) owner-type owner-id)
                              (filter (fn [frame]
                                        (and (= (or (:ownerType frame) "saga") (str owner-type))
                                             (= (:chapterId frame) owner-id)
                                             (str/blank? (or (:imageUrl frame) "")))))
                              (mapv :frameId))
         frame-count (count empty-frame-ids)]
     {:db db
      :dispatch-n (mapv (fn [frame-id] [:delete-frame frame-id]) empty-frame-ids)
      :status (if (pos? frame-count)
                (str "Deleting " frame-count " empty frame" (when (not= 1 frame-count) "s") "...")
                (:status db))})))

(rf/reg-event-fx
 :clear-frame-image
 (fn [{:keys [db]} [_ frame-id]]
   (queue-entity-patch! db frame-id {:payload {:imageUrl nil}}
                        "Frame image removed."
                        "Removing frame image...")))

(rf/reg-event-fx
 :replace-frame-image
 (fn [{:keys [db]} [_ frame-id image-data-url]]
   (queue-entity-patch! db frame-id {:payload {:imageUrl image-data-url
                                               :imageStatus "uploading"
                                               :error nil}}
                        "Frame image replaced."
                        "Replacing frame image...")))

(rf/reg-event-fx
 :save-frame-description
 (fn [{:keys [db]} [_ frame-id description]]
   (queue-entity-patch! db frame-id {:description description}
                        "Description saved."
                        "Saving description...")))

(rf/reg-event-fx
 :enqueue-add-saga
 (fn [{:keys [db]} [_ name description]]
   (let [command-id (sync/next-command-id)
         local-saga-id (str "placeholder-saga-" command-id)
         local-saga {:sagaId local-saga-id
                     :sagaNumber (store/next-saga-number db)
                     :name name
                     :description (or description "")
                     :createdAt (.toISOString (js/Date.))}
         local-entity {:id local-saga-id
                       :vanityRole "saga"
                       :title name
                       :description (or description "")
                       :children []
                       :payload {:createdAt (:createdAt local-saga)}}
         command {:id command-id
                  :kind :add-saga
                  :payload {:name name
                            :description description
                            :local-entity local-entity
                            :local-saga local-saga}
                  :success-status "Saga created."}]
     (queue-command! db "Creating saga..." command))))

(rf/reg-event-fx
 :enqueue-add-roster
 (fn [{:keys [db]} [_ after-create]]
   (let [command-id (sync/next-command-id)
         local-roster-id (str "placeholder-roster-" command-id)
         local-roster {:rosterId local-roster-id
                       :rosterNumber (store/next-roster-number db)
                       :name (str "Roster " (store/next-roster-number db))
                       :description ""
                       :createdAt (.toISOString (js/Date.))}
         local-entity {:id local-roster-id
                       :vanityRole "roster"
                       :title (or (:name after-create) (:name local-roster))
                       :description (or (:description after-create) "")
                       :children []
                       :payload {:createdAt (:createdAt local-roster)}}
         command {:id command-id
                  :kind :add-roster
                  :payload {:name (:name after-create)
                            :description (:description after-create)
                            :after-create after-create
                            :local-entity local-entity
                            :local-roster local-roster}
                  :success-status "Roster created."}]
     (queue-command! db "Creating roster..." command))))

(rf/reg-event-fx
 :enqueue-add-chapter
 (fn [{:keys [db]} [_ saga-id roster-id name description]]
   (let [command-id (sync/next-command-id)
         local-chapter-id (str "placeholder-chapter-" command-id)
         local-frame-id (str "placeholder-frame-" command-id)
         local-chapter {:chapterId local-chapter-id
                        :sagaId saga-id
                        :rosterId roster-id
                        :rosterIds [roster-id]
                        :chapterNumber (store/next-chapter-number db saga-id)
                        :name name
                        :description (or description "")
                        :createdAt (.toISOString (js/Date.))}
         local-frame (assoc (store/build-local-frame local-frame-id local-chapter-id "saga")
                            :frameNumber (store/next-frame-number db local-chapter-id "saga"))
         local-entity {:id local-chapter-id
                       :vanityRole "chapter"
                       :title name
                       :description (or description "")
                       :children [local-frame-id]
                       :payload {:parentId saga-id
                                 :sagaId saga-id
                                 :rosterId roster-id
                                 :rosterIds [roster-id]
                                 :createdAt (:createdAt local-chapter)}}
         command {:id command-id
                  :kind :add-chapter
                  :payload {:saga-id saga-id
                            :roster-id roster-id
                            :name name
                            :description description
                            :local-entity local-entity
                            :local-chapter local-chapter
                            :local-frame local-frame}
                  :success-status "Chapter created."}]
     (queue-command! db "Creating chapter..." command))))

(rf/reg-event-fx
 :enqueue-add-character
 (fn [{:keys [db]} [_ roster-id name description]]
   (let [command-id (sync/next-command-id)
         local-character-id (str "placeholder-character-" command-id)
         local-frame-id (str "placeholder-frame-" command-id)
         local-character {:characterId local-character-id
                          :rosterId roster-id
                          :characterNumber (store/next-character-number db)
                          :name name
                          :description (or description "")
                          :createdAt (.toISOString (js/Date.))}
         local-frame (assoc (store/build-local-frame local-frame-id local-character-id "character")
                            :frameNumber (store/next-frame-number db local-character-id "character"))
         local-entity {:id local-character-id
                       :vanityRole "character"
                       :title name
                       :description (or description "")
                       :children [local-frame-id]
                       :payload {:parentId roster-id
                                 :rosterId roster-id
                                 :createdAt (:createdAt local-character)}}
         command {:id command-id
                  :kind :add-character
                  :payload {:roster-id roster-id
                            :name name
                            :description description
                            :local-entity local-entity
                            :local-character local-character
                            :local-frame local-frame}
                  :success-status "Character created."}]
     (queue-command! db "Creating character..." command))))

(rf/reg-event-fx
 :update-chapter-roster
 (fn [{:keys [db]} [_ chapter-id roster-id]]
   (let [chapter (get-in db [:entities (str chapter-id)])
         existing (vec (remove str/blank? (or (get-in chapter [:payload :rosterIds]) [])))
         next-roster-ids (vec (cons roster-id (remove #(= % roster-id) existing)))]
     (queue-entity-patch! db chapter-id {:payload {:rosterId roster-id
                                                   :rosterIds next-roster-ids}}
                          "Chapter roster updated."
                          "Updating chapter roster..."))))

(rf/reg-event-fx
 :add-chapter-roster
 (fn [{:keys [db]} [_ chapter-id roster-id]]
   (let [chapter (get-in db [:entities (str chapter-id)])
         existing (vec (remove str/blank? (or (get-in chapter [:payload :rosterIds]) [])))
         next-roster-ids (if (some #(= % roster-id) existing)
                           existing
                           (conj existing roster-id))]
     (queue-entity-patch! db chapter-id {:payload {:rosterId (or (get-in chapter [:payload :rosterId])
                                                                 roster-id)
                                                   :rosterIds next-roster-ids}}
                          "Chapter roster added."
                          "Adding chapter roster..."))))

(rf/reg-event-fx
 :update-entity
 (fn [{:keys [db]} [_ entity-id patch]]
   (queue-entity-patch! db entity-id patch nil nil)))

(rf/reg-event-fx
 :delete-saga
 (fn [{:keys [db]} [_ saga-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-saga
                  :payload {:saga-id saga-id}
                  :success-status "Saga deleted."}]
     (sync/queue-command (store/apply-command-locally db command)
                         "Deleting saga..."
                         command))))

(rf/reg-event-fx
 :delete-chapter
 (fn [{:keys [db]} [_ chapter-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-chapter
                  :payload {:chapter-id chapter-id}
                  :success-status "Chapter deleted."}]
     (sync/queue-command (store/apply-command-locally db command)
                         "Deleting chapter..."
                         command))))

(rf/reg-event-fx
 :delete-character
 (fn [{:keys [db]} [_ character-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-character
                  :payload {:character-id character-id}
                  :success-status "Character deleted."}]
     (sync/queue-command (store/apply-command-locally db command)
                         "Deleting character..."
                         command))))

(rf/reg-event-fx
 :queue-command-direct
 (fn [{:keys [db]} [_ command status-message]]
   (queue-command! db status-message command)))

(defn current-image-url [db frame-id]
  (or (some-> (store/frame-by-id db frame-id) :imageUrl)
      ""))

(rf/reg-event-db
 :frame-image-loaded
 (fn [db [_ frame-id image-url]]
   (if (= image-url (current-image-url db frame-id))
     (update db :image-ui-by-frame-id image-ui/mark-image-loaded frame-id)
     db)))

(rf/reg-event-db
 :frame-image-error
 (fn [db [_ frame-id image-url]]
   (if (= image-url (current-image-url db frame-id))
     (update db :image-ui-by-frame-id image-ui/mark-image-error frame-id)
     db)))
