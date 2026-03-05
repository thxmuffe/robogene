(ns webapp.shared.events.handlers.frames
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.sync :as sync]
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

(defn remove-frame [db frame-id]
  (let [remaining-frames (->> (or (:gallery-items db) [])
                              (remove (fn [frame] (= (:frameId frame) frame-id)))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (= current-active-id frame-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)]
    (-> db
        (assoc :gallery-items remaining-frames
               :active-frame-id next-active-id)
        (update :frame-inputs dissoc frame-id)
        (update :open-frame-actions dissoc frame-id)
        (update :image-ui-by-frame-id image-ui/remove-frame frame-id)
        (update-in [:latest-state :frames]
                   (fn [frames]
                     (->> (or frames [])
                          (remove (fn [frame] (= (:frameId frame) frame-id)))
                          vec))))))

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

(defn entity-meta [entity-label]
  (if (= "character" (str entity-label))
    {:list-key :characters
     :id-key :characterId
     :latest-list-key :characters
     :name-inputs-key [:view-state :characters :name-inputs]
     :editing-key [:view-state :characters :editing-id]}
    {:list-key :saga
     :id-key :chapterId
     :latest-list-key :saga
     :name-inputs-key [:view-state :saga :name-inputs]
     :editing-key [:view-state :saga :editing-id]}))

(defn rename-entity [db entity-label entity-id description]
  (let [{:keys [list-key id-key latest-list-key]} (entity-meta entity-label)
        rename-entity* (fn [entity]
                         (if (= (id-key entity) entity-id)
                           (assoc entity :description description)
                           entity))]
    (-> db
        (update list-key (fn [rows] (mapv rename-entity* (or rows []))))
        (update-in [:latest-state latest-list-key]
                   (fn [rows] (mapv rename-entity* (or rows [])))))))

(defn remove-entity [db entity-label entity-id]
  (let [{:keys [list-key id-key latest-list-key name-inputs-key editing-key]} (entity-meta entity-label)
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
        (cond-> (= (get-in db editing-key) entity-id)
          (assoc-in editing-key nil))
        (update :frame-inputs dissoc-ids)
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

      :clear-frame-image
      {:post-clear-frame-image (merge payload callbacks)}

      :add-chapter
      {:post-add-chapter (merge payload callbacks)}

      :add-character
      {:post-add-character (merge payload callbacks)}

      :rename-chapter
      {:post-update-chapter (merge payload callbacks)}

      :rename-character
      {:post-update-character (merge payload callbacks)}

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
                             (update :frame-inputs dissoc temp-id)
                             (update :open-frame-actions dissoc temp-id))]
        {:db (-> cleanup-maps
                 (update :gallery-items (fn [frames] (mapv replace-frame (or frames []))))
                 (update-in [:latest-state :frames]
                            (fn [frames] (mapv replace-frame (or frames [])))))} )

      :add-chapter
      {:db (-> db-with-status
               (assoc-in [:view-state :saga :new-description] "")
               (assoc-in [:view-state :saga :new-panel-open?] false)
               (assoc-in [:view-state :saga :show-celebration?] true))
       :start-chapter-celebration true}

      :add-character
      {:db (-> db-with-status
               (assoc-in [:view-state :characters :new-description] "")
               (assoc-in [:view-state :characters :new-panel-open?] false))}

      {:db db-with-status})))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-inputs frame-id] value)))

(rf/reg-event-fx
 :generate-frame
  (fn [{:keys [db]} [_ frame-id]]
   (let [command {:id (sync/next-command-id)
                  :kind :generate-frame
                  :payload {:frame-id frame-id
                            :direction (get-in db [:frame-inputs frame-id] "")}
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
 :delete-frame
  (fn [{:keys [db]} [_ frame-id]]
   (let [frame (frame-by-id db frame-id)
         command {:id (sync/next-command-id)
                  :kind :delete-frame
                  :payload {:frame-id frame-id}
                  :success-status (str "Deleted " (deleted-frame-label frame) ".")}]
     (sync/queue-command (remove-frame db frame-id)
                         "Deleting frame..."
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
 :enqueue-add-chapter
  (fn [{:keys [db]} [_ description]]
   (let [command {:id (sync/next-command-id)
                  :kind :add-chapter
                  :payload {:description description}
                  :success-status "Chapter created."}]
     (sync/queue-command db "Creating chapter..." command))))

(rf/reg-event-fx
 :enqueue-add-character
  (fn [{:keys [db]} [_ description]]
   (let [command {:id (sync/next-command-id)
                  :kind :add-character
                  :payload {:description description}
                  :success-status "Character created."}]
     (sync/queue-command db
                         "Creating character..."
                         command))))

(rf/reg-event-fx
 :rename-chapter
 (fn [{:keys [db]} [_ chapter-id description]]
   (let [command {:id (sync/next-command-id)
                  :kind :rename-chapter
                  :payload {:chapter-id chapter-id
                            :description description}
                  :success-status "Chapter name updated."}]
     (sync/queue-command (rename-entity db "chapter" chapter-id description)
                         "Updating chapter name..."
                         command))))

(rf/reg-event-fx
 :rename-character
 (fn [{:keys [db]} [_ character-id description]]
   (let [command {:id (sync/next-command-id)
                  :kind :rename-character
                  :payload {:character-id character-id
                            :description description}
                  :success-status "Character name updated."}]
     (sync/queue-command (rename-entity db "character" character-id description)
                         "Updating character name..."
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
       (merge (apply-sync-success db (assoc inflight :response data))
              {:dispatch-n [[:sync-outbox/process]
                            [:fetch-state]]})
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
       {:db (-> db
                sync/dequeue-command
                (assoc :sync-inflight nil
                       :status (str "Request failed: " msg))
                (cond-> (= :add-frame (:kind inflight))
                  (remove-frame (get-in inflight [:payload :optimistic-frame-id]))))
        :dispatch-n [[:sync-outbox/process]
                     [:fetch-state]]}
       {:db db}))))
