(ns webapp.shared.events.handlers.frames
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.store :as store]
            [webapp.shared.events.sync :as sync]))

(defn deleted-frame-label [frame]
  (let [description (str/trim (or (:description frame) ""))]
    (cond
      (seq description) (str "\"" description "\"")
      :else (or (:frameId frame) "frame"))))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-drafts frame-id] value)))

(rf/reg-event-fx
 :generate-frame
 (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [direction (or provided-direction
                       (get-in db [:frame-drafts frame-id])
                       (:description (store/frame-by-id db frame-id))
                       "")
         command {:id (sync/next-command-id)
                  :kind :generate-frame
                  :payload {:frame-id frame-id
                            :direction direction
                            :without-roster false}
                  :success-status "Frame request queued."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Queueing frame..."
                         command))))

(rf/reg-event-fx
 :generate-frame-without-roster
 (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [direction (or provided-direction
                       (get-in db [:frame-drafts frame-id])
                       (:description (store/frame-by-id db frame-id))
                       "")
         command {:id (sync/next-command-id)
                  :kind :generate-frame
                  :payload {:frame-id frame-id
                            :direction direction
                            :without-roster true}
                  :success-status "Frame request queued."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Queueing frame..."
                         command))))

(rf/reg-event-fx
 :add-frame
 (fn [{:keys [db]} [_ owner-id owner-type]]
   (let [command-id (sync/next-command-id)
         owner-type (or owner-type "saga")
         optimistic-frame-id (str "frame-" command-id)
         optimistic-frame (assoc (store/optimistic-frame optimistic-frame-id owner-id owner-type)
                                 :frameNumber (store/next-frame-number db owner-id owner-type))
         command {:id command-id
                  :kind :add-frame
                  :payload {:owner-id owner-id
                            :owner-type owner-type
                            :frame-id optimistic-frame-id
                            :optimistic-frame optimistic-frame}
                  :success-status "Frame added."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Adding frame..."
                         command))))

(rf/reg-event-fx
 :upload-chapter-images
 (fn [{:keys [db]} [_ chapter-id image-data-urls]]
   (let [images (vec (or image-data-urls []))
         image-count (count images)]
     (if (or (str/blank? (or chapter-id ""))
             (zero? image-count))
       {:db db}
       (let [command-id (sync/next-command-id)
             start-frame-number (store/next-frame-number db chapter-id "saga")
             optimistic-frames (mapv (fn [idx image-data-url]
                                       (assoc (store/optimistic-upload-frame db
                                                                             (str "temp-upload-frame-" command-id "-" idx)
                                                                             chapter-id
                                                                             image-data-url)
                                              :frameNumber (+ start-frame-number idx)))
                                     (range image-count)
                                     images)
             command {:id command-id
                      :kind :add-uploaded-frames
                      :payload {:chapter-id chapter-id
                                :image-data-urls images
                                :optimistic-frames optimistic-frames}
                      :success-status (str "Uploaded " image-count " image" (when (not= 1 image-count) "s") ".")}]
         (sync/queue-command (store/apply-command-optimistically db command)
                             (str "Uploading " image-count " image" (when (not= 1 image-count) "s") "...")
                             command))))))

(rf/reg-event-fx
 :delete-frame
 (fn [{:keys [db]} [_ frame-id]]
   (let [frame (store/frame-by-id db frame-id)
         command {:id (sync/next-command-id)
                  :kind :delete-frame
                  :payload {:frame-id frame-id}
                  :success-status (str "Deleted " (deleted-frame-label frame) ".")}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Deleting frame..."
                         command))))

(rf/reg-event-fx
 :delete-empty-frames
 (fn [{:keys [db]} [_ owner-id owner-type]]
   (let [empty-frame-ids (->> (or (:gallery-items db) [])
                              (filter (fn [frame]
                                        (and (= (or (:ownerType frame) "saga") (str owner-type))
                                             (= (:chapterId frame) owner-id)
                                             (str/blank? (or (:imageUrl frame) "")))))
                              (mapv :frameId))
         frame-count (count empty-frame-ids)
         command {:id (sync/next-command-id)
                  :kind :delete-empty-frames
                  :payload {:owner-id owner-id
                            :owner-type owner-type
                            :frame-ids empty-frame-ids}
                  :success-status (str "Deleted " frame-count " empty frame" (when (not= 1 frame-count) "s") ".")}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Deleting empty frames..."
                         command))))

(rf/reg-event-fx
 :clear-frame-image
 (fn [{:keys [db]} [_ frame-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :clear-frame-image
                  :payload {:frame-id frame-id}
                  :success-status "Frame image removed."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Removing frame image..."
                         command))))

(rf/reg-event-fx
 :replace-frame-image
 (fn [{:keys [db]} [_ frame-id image-data-url]]
   (let [command {:id (sync/next-command-id)
                  :kind :replace-frame-image
                  :payload {:frame-id frame-id
                            :image-data-url image-data-url}
                  :success-status "Frame image replaced."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Replacing frame image..."
                         command))))

(rf/reg-event-fx
 :save-frame-description
 (fn [{:keys [db]} [_ frame-id description]]
   (let [command {:id (sync/next-command-id)
                  :kind :update-frame-description
                  :payload {:frame-id frame-id
                            :description description}
                  :success-status "Description saved."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Saving description..."
                         command))))

(rf/reg-event-fx
 :enqueue-add-saga
 (fn [{:keys [db]} [_ name description]]
   (let [command-id (sync/next-command-id)
         optimistic-saga-id (str "temp-saga-" command-id)
         optimistic-saga {:sagaId optimistic-saga-id
                          :sagaNumber (store/next-saga-number db)
                          :name name
                          :description (or description "")
                          :createdAt (.toISOString (js/Date.))}
         command {:id command-id
                  :kind :add-saga
                  :payload {:name name
                            :description description
                            :optimistic-saga optimistic-saga}
                  :success-status "Saga created."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Creating saga..."
                         command))))

(rf/reg-event-fx
 :enqueue-add-roster
 (fn [{:keys [db]} [_ after-create]]
   (let [command-id (sync/next-command-id)
         optimistic-roster-id (str "temp-roster-" command-id)
         optimistic-roster {:rosterId optimistic-roster-id
                            :rosterNumber (store/next-roster-number db)
                            :name (str "Roster " (store/next-roster-number db))
                            :description ""
                            :createdAt (.toISOString (js/Date.))}
         command {:id command-id
                  :kind :add-roster
                  :payload {:name (:name after-create)
                            :description (:description after-create)
                            :after-create after-create
                            :optimistic-roster optimistic-roster}
                  :success-status "Roster created."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Creating roster..."
                         command))))

(rf/reg-event-fx
 :enqueue-add-chapter
 (fn [{:keys [db]} [_ saga-id roster-id name description]]
   (let [command-id (sync/next-command-id)
         optimistic-chapter-id (str "temp-chapter-" command-id)
         optimistic-frame-id (str "temp-frame-" command-id)
         optimistic-chapter {:chapterId optimistic-chapter-id
                             :sagaId saga-id
                             :rosterId roster-id
                             :rosterIds [roster-id]
                             :chapterNumber (store/next-chapter-number db saga-id)
                             :name name
                             :description (or description "")
                             :createdAt (.toISOString (js/Date.))}
         optimistic-frame (assoc (store/optimistic-frame optimistic-frame-id optimistic-chapter-id "saga")
                                 :frameNumber (store/next-frame-number db optimistic-chapter-id "saga"))
         command {:id command-id
                  :kind :add-chapter
                  :payload {:saga-id saga-id
                            :roster-id roster-id
                            :name name
                            :description description
                            :optimistic-chapter optimistic-chapter
                            :optimistic-frame optimistic-frame}
                  :success-status "Chapter created."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Creating chapter..."
                         command))))

(rf/reg-event-fx
 :enqueue-add-character
 (fn [{:keys [db]} [_ roster-id name description]]
   (let [command-id (sync/next-command-id)
         optimistic-character-id (str "temp-character-" command-id)
         optimistic-frame-id (str "temp-frame-" command-id)
         optimistic-character {:characterId optimistic-character-id
                               :rosterId roster-id
                               :characterNumber (store/next-character-number db)
                               :name name
                               :description (or description "")
                               :createdAt (.toISOString (js/Date.))}
         optimistic-frame (assoc (store/optimistic-frame optimistic-frame-id optimistic-character-id "character")
                                 :frameNumber (store/next-frame-number db optimistic-character-id "character"))
         command {:id command-id
                  :kind :add-character
                  :payload {:roster-id roster-id
                            :name name
                            :description description
                            :optimistic-character optimistic-character
                            :optimistic-frame optimistic-frame}
                  :success-status "Character created."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Creating character..."
                         command))))

(rf/reg-event-fx
 :update-chapter
 (fn [{:keys [db]} [_ chapter-id name description]]
   (let [command {:id (sync/next-command-id)
                  :kind :update-chapter
                  :payload {:chapter-id chapter-id
                            :name name
                            :description description}
                  :success-status "Chapter name updated."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Updating chapter..."
                         command))))

(rf/reg-event-fx
 :update-chapter-roster
 (fn [{:keys [db]} [_ chapter-id roster-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :update-chapter-roster
                  :payload {:chapter-id chapter-id
                            :roster-id roster-id}
                  :success-status "Chapter roster updated."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Updating chapter roster..."
                         command))))

(rf/reg-event-fx
 :add-chapter-roster
 (fn [{:keys [db]} [_ chapter-id roster-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :add-chapter-roster
                  :payload {:chapter-id chapter-id
                            :roster-id roster-id}
                  :success-status "Chapter roster added."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Adding chapter roster..."
                         command))))

(rf/reg-event-fx
 :update-character
 (fn [{:keys [db]} [_ character-id name description]]
   (let [command {:id (sync/next-command-id)
                  :kind :update-character
                  :payload {:character-id character-id
                            :name name
                            :description description}
                  :success-status "Character updated."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Updating character..."
                         command))))

(rf/reg-event-fx
 :update-saga
 (fn [{:keys [db]} [_ saga-id name description]]
   (let [normalized-name (some-> (or name "") str str/trim)
         normalized-description (some-> (or description "") str)
         command {:id (sync/next-command-id)
                  :kind :update-saga
                  :payload {:saga-id saga-id
                            :name normalized-name
                            :description normalized-description}
                  :success-status "Saga updated."}]
     (if (or (str/blank? (or saga-id ""))
             (str/blank? (or normalized-name "")))
       {:db db}
       (sync/queue-command (store/apply-command-optimistically db command)
                           "Updating saga..."
                           command)))))

(rf/reg-event-fx
 :delete-saga
 (fn [{:keys [db]} [_ saga-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-saga
                  :payload {:saga-id saga-id}
                  :success-status "Saga deleted."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Deleting saga..."
                         command))))

(rf/reg-event-fx
 :delete-chapter
 (fn [{:keys [db]} [_ chapter-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-chapter
                  :payload {:chapter-id chapter-id}
                  :success-status "Chapter deleted."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Deleting chapter..."
                         command))))

(rf/reg-event-fx
 :delete-character
 (fn [{:keys [db]} [_ character-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-character
                  :payload {:character-id character-id}
                  :success-status "Character deleted."}]
     (sync/queue-command (store/apply-command-optimistically db command)
                         "Deleting character..."
                         command))))

(defn current-image-url [db frame-id]
  (or (some (fn [frame]
              (when (= (:frameId frame) frame-id)
                (:imageUrl frame)))
            (or (:gallery-items db) []))
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
