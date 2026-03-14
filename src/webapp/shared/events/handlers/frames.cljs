(ns webapp.shared.events.handlers.frames
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.events.transport :as transport]
            [webapp.shared.events.image-ui :as image-ui]))

(defn deleted-frame-label [frame]
  (let [description (str/trim (or (:description frame) ""))]
    (cond
      (seq description) (str "\"" description "\"")
      :else (or (:frameId frame) "frame"))))

(defn frame-by-id [db frame-id]
  (some (fn [frame]
          (when (= (:frameId frame) frame-id)
            frame))
        (or (:gallery-items db) [])))

(defn set-frame-status [db frame-id status]
  (let [set-status (fn [frame]
                     (if (= (:frameId frame) frame-id)
                       (assoc frame :status status)
                       frame))]
    (-> db
        (update :gallery-items (fn [frames] (mapv set-status (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv set-status (or frames [])))))))

(defn add-frame [db owner-id owner-type frame-id]
  (let [frame {:frameId frame-id
               :chapterId owner-id
               :ownerType (or owner-type "saga")
               :description ""
               :imageUrl nil
               :status "draft"
               :error nil
               :createdAt (.toISOString (js/Date.))
               :frameDescription ""}]
    (-> db
        (update :gallery-items (fn [frames] (conj (vec (or frames [])) frame)))
        (update-in [:latest-state :frames]
                   (fn [frames] (conj (vec (or frames [])) frame)))
        (update :image-ui-by-frame-id image-ui/mark-image-idle frame-id))))

(defn remove-frames [db frame-ids]
  (let [frame-id-set (set frame-ids)
        remaining-frames (->> (or (:gallery-items db) [])
                              (remove (fn [frame] (contains? frame-id-set (:frameId frame))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? frame-id-set current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) frame-ids))]
    (-> db
        (assoc :gallery-items remaining-frames
               :active-frame-id next-active-id)
        (update :frame-drafts dissoc-ids)
        (update :open-frame-actions dissoc-ids)
        (update :image-ui-by-frame-id
                (fn [ui-map]
                  (reduce image-ui/remove-frame (or ui-map {}) frame-ids)))
        (update-in [:latest-state :frames] (fn [_] remaining-frames)))))

(defn clear-frame-image [db frame-id]
  (let [clear-image (fn [frame]
                      (if (= (:frameId frame) frame-id)
                        (assoc frame :imageUrl nil)
                        frame))]
    (-> db
        (update :gallery-items (fn [frames] (mapv clear-image (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv clear-image (or frames []))))
        (update :image-ui-by-frame-id image-ui/mark-image-idle frame-id))))

(defn replace-frame-image [db frame-id image-data-url]
  (let [replace-image (fn [frame]
                        (if (= (:frameId frame) frame-id)
                          (-> frame
                              (assoc :imageUrl image-data-url)
                              (assoc :status "ready")
                              (assoc :error nil))
                          frame))]
    (-> db
        (update :gallery-items (fn [frames] (mapv replace-image (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv replace-image (or frames []))))
        (assoc-in [:image-ui-by-frame-id frame-id] :loading))))

(defn update-frame-description [db frame-id description]
  (let [normalized (or (some-> (or description "") str str/trim) "")
        set-description (fn [frame]
                          (if (= (:frameId frame) frame-id)
                            (assoc frame :description normalized)
                            frame))]
    (-> db
        (update :frame-drafts dissoc frame-id)
        (update :gallery-items (fn [frames] (mapv set-description (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv set-description (or frames [])))))))

(defn merge-frame-row [rows frame]
  (let [target-id (:frameId frame)]
    (mapv (fn [row]
            (if (= (:frameId row) target-id)
              (merge row frame)
              row))
          (or rows []))))

(defn merge-frame-response [db frame]
  (if (seq (or (:frameId frame) ""))
    (-> db
        (update :gallery-items merge-frame-row frame)
        (update-in [:latest-state :frames] merge-frame-row frame)
        (assoc-in [:image-ui-by-frame-id (:frameId frame)]
                  (if (str/blank? (or (:imageUrl frame) ""))
                    :idle
                    :loading)))
    db))

(defn merge-command-revision [db command]
  (if-let [revision (some-> command :response :revision)]
    (assoc db :last-rendered-revision revision)
    db))

(defn update-saga-row [rows saga-id f]
  (mapv (fn [row]
          (if (= (:sagaId row) saga-id)
            (f row)
            row))
        (or rows [])))

(defn add-saga-row [db saga]
  (-> db
      (update :sagas (fn [rows] (conj (vec (or rows [])) saga)))
      (update-in [:latest-state :sagas] (fn [rows] (conj (vec (or rows [])) saga)))))

(defn remove-saga-row [rows saga-id]
  (->> (or rows [])
       (remove (fn [row] (= (:sagaId row) saga-id)))
       vec))

(defn entity-meta [entity-label]
  (if (= "character" (str entity-label))
    {:list-key :roster
     :id-key :characterId
     :latest-list-key :roster
     :name-inputs-key [:view-state :roster :name-inputs]
     :description-inputs-key [:view-state :roster :description-inputs]
     :editing-key [:view-state :roster :editing-id]}
    {:list-key :saga
     :id-key :chapterId
     :latest-list-key :saga
     :name-inputs-key [:view-state :saga :name-inputs]
     :description-inputs-key [:view-state :saga :description-inputs]
     :editing-key [:view-state :saga :editing-id]}))

(defn update-entity [db entity-label entity-id name description]
  (let [{:keys [list-key id-key latest-list-key]} (entity-meta entity-label)
        update-entity* (fn [entity]
                         (if (= (id-key entity) entity-id)
                           (-> entity
                               (assoc :name name)
                               (assoc :description description))
                           entity))]
    (-> db
        (update list-key (fn [rows] (mapv update-entity* (or rows []))))
        (update-in [:latest-state latest-list-key]
                   (fn [rows] (mapv update-entity* (or rows [])))))))

(defn remove-entity [db entity-label entity-id]
  (let [{:keys [list-key id-key latest-list-key name-inputs-key description-inputs-key editing-key]} (entity-meta entity-label)
        owner-type (if (= "character" (str entity-label)) "character" "saga")
        remaining-entities (->> (or (list-key db) [])
                                (remove (fn [entity] (= (id-key entity) entity-id)))
                                vec)
        removed-frame-ids (->> (or (:gallery-items db) [])
                               (filter (fn [frame]
                                         (and (= (:chapterId frame) entity-id)
                                              (= (or (:ownerType frame) "saga") owner-type))))
                               (map :frameId)
                               set)
        remaining-frames (->> (or (:gallery-items db) [])
                              (remove (fn [frame]
                                        (and (= (:chapterId frame) entity-id)
                                             (= (or (:ownerType frame) "saga") owner-type))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? removed-frame-ids current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) removed-frame-ids))]
    (-> db
        (assoc list-key remaining-entities
               :gallery-items remaining-frames
               :active-frame-id next-active-id)
        (update-in name-inputs-key dissoc entity-id)
        (update-in description-inputs-key dissoc entity-id)
        (cond-> (= (get-in db editing-key) entity-id)
          (assoc-in editing-key nil))
        (update :frame-drafts dissoc-ids)
        (update :open-frame-actions dissoc-ids)
        (update :image-ui-by-frame-id dissoc-ids)
        (update-in [:latest-state latest-list-key] (fn [_] remaining-entities))
        (update-in [:latest-state :frames] (fn [_] remaining-frames)))))

(defn command->fx [command]
  (let [kind (:kind command)
        payload (:payload command)
        callbacks (sync/callback-events (:id command))]
    (case kind
      :generate-frame
      {:post-generate-frame (merge payload callbacks)}

      :add-frame
      {:post-add-frame (merge payload callbacks)}

      :delete-frame
      {:post-delete-frame (merge payload callbacks)}

      :delete-empty-frames
      {:post-delete-empty-frames (merge payload callbacks)}

      :clear-frame-image
      {:post-clear-frame-image (merge payload callbacks)}

      :replace-frame-image
      {:post-replace-frame-image (merge payload callbacks)}

      :update-frame-description
      {:post-update-frame-description (merge payload callbacks)}

      :add-saga
      {:post-add-saga (merge payload callbacks)}

      :add-chapter
      {:post-add-chapter (merge payload callbacks)}

      :add-character
      {:post-add-character (merge payload callbacks)}

      :update-chapter
      {:post-update-chapter (merge payload callbacks)}

      :update-character
      {:post-update-character (merge payload callbacks)}

      :update-saga
      {:post-update-saga (merge payload callbacks)}

      :delete-saga
      {:post-delete-saga (merge payload callbacks)}

      :delete-chapter
      {:post-delete-chapter (merge payload callbacks)}

      :delete-character
      {:post-delete-character (merge payload callbacks)}

      nil)))

(defn apply-sync-success [db command]
  (let [kind (:kind command)
        base-db (-> db
                    sync/dequeue-command
                    (assoc :sync-inflight nil))
        db-with-status (assoc base-db :status (or (:success-status command) "Done."))]
    (case kind
      :add-frame
      (let [temp-id (get-in command [:payload :optimistic-frame-id])
            created-frame (:frame (:response command))
            replace-frame (fn [frame]
                            (if (= (:frameId frame) temp-id)
                              (or created-frame frame)
                              frame))
            cleanup-maps (-> db-with-status
                             (update :frame-drafts dissoc temp-id)
                             (update :open-frame-actions dissoc temp-id))]
        {:db (-> cleanup-maps
                 (merge-command-revision command)
                 (update :gallery-items (fn [frames] (mapv replace-frame (or frames []))))
                 (update-in [:latest-state :frames]
                            (fn [frames] (mapv replace-frame (or frames [])))))} )

      :replace-frame-image
      {:db (-> db-with-status
               (merge-command-revision command)
               (merge-frame-response (get-in command [:response :frame])))}

      :update-frame-description
      {:db (-> db-with-status
               (merge-command-revision command)
               (merge-frame-response (get-in command [:response :frame])))}

      :clear-frame-image
      {:db (-> db-with-status
               (merge-command-revision command)
               (merge-frame-response (get-in command [:response :frame])))}

      :add-saga
      (let [temp-id (get-in command [:payload :optimistic-saga-id])
            created-saga (:saga (:response command))]
        {:db (-> db-with-status
                 (merge-command-revision command)
                 (update :sagas update-saga-row temp-id
                         (fn [_]
                           (or created-saga
                               {:sagaId temp-id})))
                 (update-in [:latest-state :sagas] update-saga-row temp-id
                            (fn [_]
                              (or created-saga
                                  {:sagaId temp-id})))
                 (assoc-in [:view-state :index :new-name] "")
                 (assoc-in [:view-state :index :new-description] "")
                 (assoc-in [:view-state :index :new-panel-open?] false))})

      :add-chapter
      {:db (-> db-with-status
               (merge-command-revision command)
               (assoc-in [:view-state :saga :new-name] "")
               (assoc-in [:view-state :saga :new-description] "")
               (assoc-in [:view-state :saga :new-panel-open?] false)
               (assoc-in [:view-state :saga :show-celebration?] true))
       :start-chapter-celebration true}

      :add-character
      {:db (-> db-with-status
               (merge-command-revision command)
               (assoc-in [:view-state :roster :new-name] "")
               (assoc-in [:view-state :roster :new-description] "")
               (assoc-in [:view-state :roster :new-panel-open?] false))}

      {:db (merge-command-revision db-with-status command)})))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-drafts frame-id] value)))

(rf/reg-event-fx
 :generate-frame
  (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [direction (or provided-direction
                       (get-in db [:frame-drafts frame-id])
                       (:description (frame-by-id db frame-id))
                       "")
         command {:id (sync/next-command-id)
                  :kind :generate-frame
                  :payload {:frame-id frame-id
                            :direction direction
                            :without-roster false}
                  :success-status "Frame request queued."}]
     (sync/queue-command (set-frame-status db frame-id "queued")
                         "Queueing frame..."
                         command))))

(rf/reg-event-fx
 :generate-frame-without-roster
 (fn [{:keys [db]} [_ frame-id provided-direction]]
   (let [direction (or provided-direction
                       (get-in db [:frame-drafts frame-id])
                       (:description (frame-by-id db frame-id))
                       "")
         command {:id (sync/next-command-id)
                  :kind :generate-frame
                  :payload {:frame-id frame-id
                            :direction direction
                            :without-roster true}
                  :success-status "Frame request queued."}]
     (sync/queue-command (set-frame-status db frame-id "queued")
                         "Queueing frame..."
                         command))))

(rf/reg-event-fx
 :add-frame
  (fn [{:keys [db]} [_ owner-id owner-type]]
   (let [temp-frame-id (str "temp-frame-" (sync/next-command-id))
         owner-type (or owner-type "saga")
         command {:id (sync/next-command-id)
                  :kind :add-frame
                  :payload {:owner-id owner-id
                            :owner-type owner-type
                            :optimistic-frame-id temp-frame-id}
                  :success-status "Frame added."}]
     (sync/queue-command (add-frame db owner-id owner-type temp-frame-id)
                         "Adding frame..."
                         command))))

(rf/reg-event-fx
 :upload-chapter-images
 (fn [{:keys [db]} [_ chapter-id image-data-urls]]
   (let [image-count (count (or image-data-urls []))]
     (if (or (str/blank? (or chapter-id ""))
             (zero? image-count))
       {:db db}
       {:db (assoc db :status (str "Uploading " image-count " image" (when (not= 1 image-count) "s") "..."))
        :post-add-uploaded-frames {:chapter-id chapter-id
                                   :image-data-urls image-data-urls
                                   :on-success [:upload-chapter-images/succeeded chapter-id]
                                   :on-failure [:upload-chapter-images/failed chapter-id]}}))))

(rf/reg-event-fx
 :upload-chapter-images/succeeded
 (fn [{:keys [db]} [_ _chapter-id response]]
   (let [first-frame-id (some-> response :frames first :frameId)]
     (if first-frame-id
       {:db (assoc db :status "Images uploaded.")
        :dispatch-n [[:set-active-frame first-frame-id]
                     [:fetch-state]]}
       {:db (assoc db :status "Images uploaded.")
        :dispatch [:fetch-state]}))))

(rf/reg-event-db
 :upload-chapter-images/failed
 (fn [db [_ _chapter-id error-message]]
   (assoc db :status (or error-message "Upload images failed."))))

(rf/reg-event-fx
 :delete-frame
  (fn [{:keys [db]} [_ frame-id]]
     (let [frame (frame-by-id db frame-id)
         command {:id (sync/next-command-id)
                  :kind :delete-frame
                  :payload {:frame-id frame-id}
                  :success-status (str "Deleted " (deleted-frame-label frame) ".")}]
     (sync/queue-command (remove-frames db [frame-id])
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
                            :owner-type owner-type}
                  :success-status (str "Deleted " frame-count " empty frame" (when (not= 1 frame-count) "s") ".")}]
     (sync/queue-command (remove-frames db empty-frame-ids)
                         "Deleting empty frames..."
                         command))))

(rf/reg-event-fx
 :clear-frame-image
  (fn [{:keys [db]} [_ frame-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :clear-frame-image
                  :payload {:frame-id frame-id}
                  :success-status "Frame image removed."}]
     (sync/queue-command (clear-frame-image db frame-id)
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
     (sync/queue-command (replace-frame-image db frame-id image-data-url)
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
     (sync/queue-command (update-frame-description db frame-id description)
                         "Saving description..."
                         command))))

(rf/reg-event-fx
:enqueue-add-saga
 (fn [{:keys [db]} [_ name description]]
   (let [command-id (sync/next-command-id)
         optimistic-saga-id (str "temp-saga-" command-id)
         next-saga-number (inc (reduce max 0 (map :sagaNumber (or (:sagas db) []))))
         optimistic-saga {:sagaId optimistic-saga-id
                          :sagaNumber next-saga-number
                          :name name
                          :description (or description "")
                          :createdAt (.toISOString (js/Date.))}
         command {:id command-id
                  :kind :add-saga
                  :payload {:name name
                            :optimistic-saga-id optimistic-saga-id
                            :description description}
                  :success-status "Saga created."}]
     (sync/queue-command (add-saga-row db optimistic-saga) "Creating saga..." command))))

(rf/reg-event-fx
:enqueue-add-chapter
  (fn [{:keys [db]} [_ saga-id name description]]
   (let [command {:id (sync/next-command-id)
                  :kind :add-chapter
                  :payload {:saga-id saga-id
                            :name name
                            :description description}
                  :success-status "Chapter created."}]
     (sync/queue-command db "Creating chapter..." command))))

(rf/reg-event-fx
 :enqueue-add-character
  (fn [{:keys [db]} [_ name description]]
   (let [command {:id (sync/next-command-id)
                  :kind :add-character
                  :payload {:name name
                            :description description}
                  :success-status "Character created."}]
     (sync/queue-command db
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
     (sync/queue-command (update-entity db "chapter" chapter-id name description)
                         "Updating chapter..."
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
     (sync/queue-command (update-entity db "character" character-id name description)
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
       (sync/queue-command (-> db
                               (update :sagas update-saga-row saga-id
                                       (fn [saga]
                                         (assoc saga
                                                :name normalized-name
                                                :description (or normalized-description ""))))
                               (update-in [:latest-state :sagas] update-saga-row saga-id
                                          (fn [saga]
                                            (assoc saga
                                                   :name normalized-name
                                                   :description (or normalized-description "")))))
                           "Updating saga..."
                           command)))))

(rf/reg-event-fx
 :delete-saga
 (fn [{:keys [db]} [_ saga-id]]
   (let [chapter-ids (->> (or (:saga db) [])
                          (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
                          (map :chapterId)
                          set)
         frame-ids (->> (or (:gallery-items db) [])
                        (filter (fn [frame] (contains? chapter-ids (:chapterId frame))))
                        (map :frameId)
                        vec)
         command {:id (sync/next-command-id)
                  :kind :delete-saga
                  :payload {:saga-id saga-id}
                  :success-status "Saga deleted."}]
     (sync/queue-command (-> db
                             (update :sagas remove-saga-row saga-id)
                             (update :saga (fn [rows]
                                             (->> (or rows [])
                                                  (remove (fn [row] (= (:sagaId row) saga-id)))
                                                  vec)))
                             (update :gallery-items (fn [rows]
                                                      (->> (or rows [])
                                                           (remove (fn [frame]
                                                                     (contains? chapter-ids (:chapterId frame))))
                                                           vec)))
                             (update-in [:latest-state :sagas] remove-saga-row saga-id)
                             (update-in [:latest-state :saga] (fn [rows]
                                                                (->> (or rows [])
                                                                     (remove (fn [row] (= (:sagaId row) saga-id)))
                                                                     vec)))
                             (update-in [:latest-state :frames] (fn [rows]
                                                                  (->> (or rows [])
                                                                       (remove (fn [frame]
                                                                                 (contains? chapter-ids (:chapterId frame))))
                                                                       vec)))
                             (update :frame-drafts (fn [m] (apply dissoc (or m {}) frame-ids)))
                             (update :open-frame-actions (fn [m] (apply dissoc (or m {}) frame-ids)))
                             (update :image-ui-by-frame-id (fn [m] (reduce image-ui/remove-frame (or m {}) frame-ids))))
                         "Deleting saga..."
                         command))))

(rf/reg-event-fx
 :delete-chapter
 (fn [{:keys [db]} [_ chapter-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-chapter
                  :payload {:chapter-id chapter-id}
                  :success-status "Chapter deleted."}]
     (sync/queue-command (remove-entity db "chapter" chapter-id)
                         "Deleting chapter..."
                         command))))

(rf/reg-event-fx
 :delete-character
 (fn [{:keys [db]} [_ character-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :delete-character
                  :payload {:character-id character-id}
                  :success-status "Character deleted."}]
     (sync/queue-command (remove-entity db "character" character-id)
                         "Deleting character..."
                         command))))

(rf/reg-event-fx
 :sync-outbox/process
 (fn [{:keys [db]} _]
   (let [inflight (:sync-inflight db)
         next-command (first (or (:sync-outbox db) []))
         mutation-fx (when next-command (command->fx next-command))]
     (cond
       (some? inflight)
       {:db db}

       (nil? next-command)
       {:db db}

       (nil? mutation-fx)
       {:db (-> db
                sync/dequeue-command
                (assoc :status "Skipped unknown sync command."))
        :dispatch [:sync-outbox/process]}

       :else
       (merge {:db (assoc db :sync-inflight next-command)}
              mutation-fx)))))

(rf/reg-event-fx
 :sync-outbox/succeeded
 (fn [{:keys [db]} [_ command-id data]]
   (let [inflight (:sync-inflight db)]
     (if (= command-id (:id inflight))
       (cond-> (merge (apply-sync-success db (assoc inflight :response data))
                      {:dispatch-n [[:sync-outbox/process]
                                    [:fetch-state]]})
         (and (= :generate-frame (:kind inflight))
              (transport/realtime-disabled?))
         (assoc :dispatch-after-burst {:delays [250 1000 2500]
                                       :event [:fetch-state]}))
       {:db db}))))

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

(rf/reg-event-fx
 :sync-outbox/failed
 (fn [{:keys [db]} [_ command-id msg]]
   (let [inflight (:sync-inflight db)]
     (if (= command-id (:id inflight))
       {:db (cond-> (-> db
                        sync/dequeue-command
                        (assoc :sync-inflight nil
                               :status (str "Request failed: " msg)))
              (= :add-frame (:kind inflight))
              (remove-frames [(get-in inflight [:payload :optimistic-frame-id])]))
        :dispatch-n [[:sync-outbox/process]
                     [:fetch-state]]}
       {:db db}))))
