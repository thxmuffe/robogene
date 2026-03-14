(ns webapp.shared.store
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.events.transport :as transport]))

(defn frame-by-id [db frame-id]
  (some (fn [frame]
          (when (= (:frameId frame) frame-id)
            frame))
        (or (:gallery-items db) [])))

(declare merge-frame-row)

(defn set-frame-image-status [db frame-id image-status]
  (let [set-status (fn [frame]
                     (if (= (:frameId frame) frame-id)
                       (assoc frame :imageStatus image-status)
                       frame))]
    (-> db
        (update :gallery-items (fn [frames] (mapv set-status (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv set-status (or frames [])))))))

(defn add-frame-row [db frame]
  (let [frame-id (:frameId frame)]
    (-> db
        (update :gallery-items
                (fn [frames]
                  (let [rows (vec (or frames []))]
                    (if (some (fn [row] (= (:frameId row) frame-id)) rows)
                      (merge-frame-row rows frame)
                      (conj rows frame)))))
        (update-in [:latest-state :frames]
                   (fn [frames]
                     (let [rows (vec (or frames []))]
                       (if (some (fn [row] (= (:frameId row) frame-id)) rows)
                         (merge-frame-row rows frame)
                         (conj rows frame)))))
        (update :hidden-frame-images dissoc frame-id)
        (assoc-in [:image-ui-by-frame-id frame-id]
                  (image-ui/image-ui-state-for-url (:imageUrl frame))))))

(defn add-frame [db owner-id owner-type frame-id]
  (add-frame-row db {:frameId frame-id
                     :chapterId owner-id
                     :ownerType (or owner-type "saga")
                     :description ""
                     :imageUrl nil
                     :imageStatus "draft"
                     :error nil
                     :createdAt (.toISOString (js/Date.))
                     :frameDescription ""}))

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
        (update :hidden-frame-images dissoc-ids)
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
        (assoc-in [:hidden-frame-images frame-id] true)
        (update :image-ui-by-frame-id image-ui/mark-image-idle frame-id))))

(defn replace-frame-image [db frame-id image-data-url]
  (let [replace-image (fn [frame]
                        (if (= (:frameId frame) frame-id)
                          (-> frame
                              (assoc :imageUrl image-data-url)
                              (assoc :imageStatus "uploading")
                              (assoc :error nil))
                          frame))]
    (-> db
        (update :gallery-items (fn [frames] (mapv replace-image (or frames []))))
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv replace-image (or frames []))))
        (update :hidden-frame-images dissoc frame-id)
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
        (cond-> (and (= "ready" (:imageStatus frame))
                     (not (str/blank? (or (:imageUrl frame) ""))))
          (update :hidden-frame-images dissoc (:frameId frame)))
        (assoc-in [:image-ui-by-frame-id (:frameId frame)]
                  (if (str/blank? (or (:imageUrl frame) ""))
                    :idle
                    :loading)))
    db))

(defn merge-command-revision [db command]
  (if-let [revision (some-> command :response :revision)]
    (assoc db :last-rendered-revision revision)
    db))

(defn replace-row-by-id [rows id-key temp-id next-row]
  (mapv (fn [row]
          (if (= (id-key row) temp-id)
            (or next-row row)
            row))
        (or rows [])))

(defn update-saga-row [rows saga-id f]
  (mapv (fn [row]
          (if (= (:sagaId row) saga-id)
            (f row)
            row))
        (or rows [])))

(defn entity-meta [entity-label]
  (case (str entity-label)
    "saga"
    {:list-key :sagas
     :id-key :sagaId
     :latest-list-key :sagas
     :name-inputs-key [:view-state :index :name-inputs]
     :description-inputs-key [:view-state :index :description-inputs]
     :editing-key [:view-state :index :editing-id]}

    "character"
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

(defn add-saga-row [db saga]
  (-> db
      (update :sagas (fn [rows] (conj (vec (or rows [])) saga)))
      (update-in [:latest-state :sagas] (fn [rows] (conj (vec (or rows [])) saga)))))

(defn add-entity-row [db entity-label entity]
  (let [{:keys [list-key latest-list-key]} (entity-meta entity-label)]
    (-> db
        (update list-key (fn [rows] (conj (vec (or rows [])) entity)))
        (update-in [:latest-state latest-list-key] (fn [rows] (conj (vec (or rows [])) entity))))))

(defn replace-entity-row [db entity-label temp-id entity]
  (let [{:keys [list-key id-key latest-list-key]} (entity-meta entity-label)]
    (-> db
        (update list-key replace-row-by-id id-key temp-id entity)
        (update-in [:latest-state latest-list-key] replace-row-by-id id-key temp-id entity))))

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

(defn remove-saga-row [rows saga-id]
  (->> (or rows [])
       (remove (fn [row] (= (:sagaId row) saga-id)))
       vec))

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
        (update :hidden-frame-images dissoc-ids)
        (update :image-ui-by-frame-id dissoc-ids)
        (update-in [:latest-state latest-list-key] (fn [_] remaining-entities))
        (update-in [:latest-state :frames] (fn [_] remaining-frames)))))

(defn remove-saga [db saga-id]
  (let [chapter-ids (->> (or (:saga db) [])
                         (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
                         (map :chapterId)
                         set)
        frame-ids (->> (or (:gallery-items db) [])
                       (filter (fn [frame] (contains? chapter-ids (:chapterId frame))))
                       (map :frameId)
                       vec)]
    (-> db
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
        (update :image-ui-by-frame-id (fn [m] (reduce image-ui/remove-frame (or m {}) frame-ids))))))

(defn optimistic-frame [frame-id owner-id owner-type]
  {:frameId frame-id
   :chapterId owner-id
   :ownerType (or owner-type "saga")
   :description ""
   :imageUrl nil
   :imageStatus "draft"
   :error nil
   :createdAt (.toISOString (js/Date.))
   :frameDescription ""})

(defn optimistic-upload-frame [_db frame-id chapter-id image-data-url]
  {:frameId frame-id
   :chapterId chapter-id
   :ownerType "saga"
   :frameNumber 0
   :description ""
   :imageUrl image-data-url
   :imageStatus "uploading"
   :error nil
   :createdAt (.toISOString (js/Date.))
   :frameDescription ""})

(defn next-saga-number [db]
  (inc (reduce max 0 (keep :sagaNumber (or (:sagas db) [])))))

(defn next-chapter-number [db saga-id]
  (inc (reduce max 0 (keep :chapterNumber (filter (fn [chapter]
                                                    (= (:sagaId chapter) saga-id))
                                                  (or (:saga db) []))))))

(defn next-character-number [db]
  (inc (reduce max 0 (keep :characterNumber (or (:roster db) [])))))

(defn next-frame-number [db owner-id owner-type]
  (inc (reduce max 0 (keep :frameNumber (filter (fn [frame]
                                                  (and (= (:chapterId frame) owner-id)
                                                       (= (or (:ownerType frame) "saga") (or owner-type "saga"))))
                                                (or (:gallery-items db) []))))))

(defn replace-temp-frame [db temp-frame-id created-frame]
  (if temp-frame-id
    (let [created-frame-id (or (:frameId created-frame) temp-frame-id)
          temp-frame (frame-by-id db temp-frame-id)
          temp-draft (get-in db [:frame-drafts temp-frame-id])
          temp-open? (true? (get-in db [:open-frame-actions temp-frame-id]))
          temp-hidden? (true? (get-in db [:hidden-frame-images temp-frame-id]))
          merged-frame (cond-> (merge temp-frame created-frame)
                         (and (seq (or (:description temp-frame) ""))
                              (str/blank? (or (:description created-frame) "")))
                         (assoc :description (:description temp-frame)))
          migrated-db (if (not= created-frame-id temp-frame-id)
                        (-> db
                            (update :frame-drafts (fn [m]
                                                    (cond-> (dissoc (or m {}) temp-frame-id)
                                                      (some? temp-draft)
                                                      (assoc created-frame-id temp-draft))))
                            (update :open-frame-actions (fn [m]
                                                          (cond-> (dissoc (or m {}) temp-frame-id)
                                                            temp-open?
                                                            (assoc created-frame-id true))))
                            (update :hidden-frame-images (fn [m]
                                                           (cond-> (dissoc (or m {}) temp-frame-id)
                                                             temp-hidden?
                                                             (assoc created-frame-id true))))
                            (update :image-ui-by-frame-id (fn [m]
                                                            (let [ui-map (or m {})
                                                                  existing (get ui-map temp-frame-id)]
                                                              (cond-> (image-ui/remove-frame ui-map temp-frame-id)
                                                                (some? existing)
                                                                (assoc created-frame-id existing)))))
                            (cond-> (= (:active-frame-id db) temp-frame-id)
                              (assoc :active-frame-id created-frame-id)))
                        (cond-> db
                          temp-hidden?
                          (assoc-in [:hidden-frame-images created-frame-id] true)))]
      (cond-> (-> migrated-db
                  (update :gallery-items replace-row-by-id :frameId temp-frame-id
                          (or merged-frame {:frameId temp-frame-id}))
                  (update-in [:latest-state :frames] replace-row-by-id :frameId temp-frame-id
                             (or merged-frame {:frameId temp-frame-id})))
        (seq (or created-frame-id ""))
        (assoc-in [:image-ui-by-frame-id created-frame-id]
                  (image-ui/image-ui-state-for-url (:imageUrl merged-frame)))))
    db))

(defn replace-temp-frames [db temp-frame-ids created-frames]
  (reduce (fn [acc [temp-id created-frame]]
            (replace-temp-frame acc temp-id created-frame))
          db
          (map vector (or temp-frame-ids []) (or created-frames []))))

(defn create-entity-success [db command entity-label temp-entity-id view-state-key]
  (let [response (:response command)
        created-entity (get response entity-label)
        created-frame (:frame response)
        temp-frame-id (get-in command [:payload :optimistic-frame :frameId])]
    {:db (-> db
             (merge-command-revision command)
             (replace-entity-row entity-label temp-entity-id
                                 (or created-entity
                                     {(case entity-label
                                        "saga" :sagaId
                                        "character" :characterId
                                        :chapterId) temp-entity-id}))
             (replace-temp-frame temp-frame-id created-frame)
             (assoc-in (conj view-state-key :new-name) "")
             (assoc-in (conj view-state-key :new-description) "")
             (assoc-in (conj view-state-key :new-panel-open?) false))}))

(defn create-saga-success [db command]
  {:db (-> db
           (merge-command-revision command)
           (replace-entity-row "saga"
                               (get-in command [:payload :optimistic-saga :sagaId])
                               (or (:saga (:response command))
                                   {:sagaId (get-in command [:payload :optimistic-saga :sagaId])}))
           (assoc-in [:view-state :index :new-name] "")
           (assoc-in [:view-state :index :new-description] "")
           (assoc-in [:view-state :index :new-panel-open?] false))})

(defn create-chapter-success [db command]
  (assoc (create-entity-success db command "chapter"
                                (get-in command [:payload :optimistic-chapter :chapterId])
                                [:view-state :saga])
         :start-chapter-celebration true))

(defn create-character-success [db command]
  (create-entity-success db command "character"
                         (get-in command [:payload :optimistic-character :characterId])
                         [:view-state :roster]))

(defn remove-temp-saga [db command]
  (let [temp-id (get-in command [:payload :optimistic-saga :sagaId])]
    (-> db
        (update :sagas remove-saga-row temp-id)
        (update-in [:latest-state :sagas] remove-saga-row temp-id))))

(defn remove-temp-entity [db entity-label temp-id]
  (remove-entity db entity-label temp-id))

(defn mutation-spec [kind]
  (case kind
    :generate-frame
    {:transport-fx :post-generate-frame
     :optimistic (fn [db payload]
                   (set-frame-image-status db (:frame-id payload) "queued"))
     :success (fn [db command]
                (let [response-frame (get-in command [:response :frame])]
                  {:db (cond-> (merge-command-revision db command)
                         response-frame
                         (merge-frame-response response-frame))}))}

    :add-frame
    {:transport-fx :post-add-frame
     :optimistic (fn [db payload]
                   (add-frame-row db (:optimistic-frame payload)))
     :success (fn [db command]
                (let [temp-id (get-in command [:payload :optimistic-frame :frameId])
                      created-frame (:frame (:response command))
                      replace-frame (fn [frame]
                                      (if (= (:frameId frame) temp-id)
                                        (or created-frame frame)
                                        frame))]
                  {:db (-> db
                           (merge-command-revision command)
                           (update :frame-drafts dissoc temp-id)
                           (update :open-frame-actions dissoc temp-id)
                           (update :image-ui-by-frame-id image-ui/remove-frame temp-id)
                           (update :gallery-items (fn [frames] (mapv replace-frame (or frames []))))
                           (update-in [:latest-state :frames]
                                      (fn [frames] (mapv replace-frame (or frames [])))))}))
     :failure (fn [db command]
                (remove-frames db [(get-in command [:payload :optimistic-frame :frameId])]))
     :fetch-after-success? false}

    :delete-frame
    {:transport-fx :post-delete-frame
     :optimistic (fn [db payload]
                   (remove-frames db [(:frame-id payload)]))
     :fetch-after-success? false}

    :delete-empty-frames
    {:transport-fx :post-delete-empty-frames
     :optimistic (fn [db payload]
                   (remove-frames db (or (:frame-ids payload) [])))
     :fetch-after-success? false}

    :clear-frame-image
    {:transport-fx :post-clear-frame-image
     :optimistic (fn [db payload]
                   (clear-frame-image db (:frame-id payload)))
     :success (fn [db command]
                {:db (-> db
                         (merge-command-revision command)
                         (merge-frame-response (get-in command [:response :frame])))})
     :fetch-after-success? false}

    :replace-frame-image
    {:transport-fx :post-replace-frame-image
     :optimistic (fn [db payload]
                   (replace-frame-image db (:frame-id payload)
                                        (:image-data-url payload)))
     :success (fn [db command]
                {:db (-> db
                         (merge-command-revision command)
                         (merge-frame-response (get-in command [:response :frame])))})
     :fetch-after-success? false}

    :add-uploaded-frames
    {:transport-fx :post-add-uploaded-frames
     :optimistic (fn [db payload]
                   (reduce add-frame-row db (or (:optimistic-frames payload) [])))
     :success (fn [db command]
                (let [response-frames (or (get-in command [:response :frames]) [])
                      temp-frame-ids (mapv :frameId (or (get-in command [:payload :optimistic-frames]) []))
                      first-frame-id (some-> response-frames first :frameId)]
                  {:db (cond-> (-> db
                                   (merge-command-revision command)
                                   (replace-temp-frames temp-frame-ids response-frames))
                         first-frame-id
                         (assoc :active-frame-id first-frame-id))}))
     :failure (fn [db command]
                (remove-frames db (mapv :frameId (or (get-in command [:payload :optimistic-frames]) []))))
     :fetch-after-success? false}

    :update-frame-description
    {:transport-fx :post-update-frame-description
     :optimistic (fn [db payload]
                   (update-frame-description db (:frame-id payload)
                                             (:description payload)))
     :success (fn [db command]
                {:db (-> db
                         (merge-command-revision command)
                         (merge-frame-response (get-in command [:response :frame])))} )}

    :add-saga
    {:transport-fx :post-add-saga
     :optimistic (fn [db payload]
                   (add-saga-row db (:optimistic-saga payload)))
     :success create-saga-success
     :failure remove-temp-saga}

    :add-chapter
    {:transport-fx :post-add-chapter
     :optimistic (fn [db payload]
                   (-> db
                       (add-entity-row "chapter" (:optimistic-chapter payload))
                       (cond-> (:optimistic-frame payload)
                         (add-frame-row (:optimistic-frame payload)))))
     :success create-chapter-success
     :failure (fn [db command]
                (remove-temp-entity db "chapter"
                                    (get-in command [:payload :optimistic-chapter :chapterId])))}

    :add-character
    {:transport-fx :post-add-character
     :optimistic (fn [db payload]
                   (-> db
                       (add-entity-row "character" (:optimistic-character payload))
                       (cond-> (:optimistic-frame payload)
                         (add-frame-row (:optimistic-frame payload)))))
     :success create-character-success
     :failure (fn [db command]
                (remove-temp-entity db "character"
                                    (get-in command [:payload :optimistic-character :characterId])))}

    :update-chapter
    {:transport-fx :post-update-chapter
     :optimistic (fn [db payload]
                   (update-entity db "chapter" (:chapter-id payload)
                                  (:name payload) (:description payload)))}

    :update-character
    {:transport-fx :post-update-character
     :optimistic (fn [db payload]
                   (update-entity db "character" (:character-id payload)
                                  (:name payload) (:description payload)))}

    :update-saga
    {:transport-fx :post-update-saga
     :optimistic (fn [db payload]
                   (-> db
                       (update :sagas update-saga-row (:saga-id payload)
                               (fn [saga]
                                 (assoc saga
                                        :name (:name payload)
                                        :description (or (:description payload) ""))))
                       (update-in [:latest-state :sagas] update-saga-row (:saga-id payload)
                                  (fn [saga]
                                    (assoc saga
                                           :name (:name payload)
                                           :description (or (:description payload) ""))))))}

    :delete-saga
    {:transport-fx :post-delete-saga
     :optimistic (fn [db payload]
                   (remove-saga db (:saga-id payload)))
     :fetch-after-success? false}

    :delete-chapter
    {:transport-fx :post-delete-chapter
     :optimistic (fn [db payload]
                   (remove-entity db "chapter" (:chapter-id payload)))
     :fetch-after-success? false}

    :delete-character
    {:transport-fx :post-delete-character
     :optimistic (fn [db payload]
                   (remove-entity db "character" (:character-id payload)))
     :fetch-after-success? false}

    nil))

(defn apply-command-optimistically [db command]
  (if-let [optimistic (get-in (mutation-spec (:kind command)) [:optimistic])]
    (optimistic db (:payload command))
    db))

(defn reapply-pending-commands [db]
  (reduce apply-command-optimistically
          db
          (concat (when-let [command (:sync-inflight db)]
                    [command])
                  (or (:sync-outbox db) []))))

(defn command->fx [command]
  (let [transport-fx (get-in (mutation-spec (:kind command)) [:transport-fx])]
    (when transport-fx
      {transport-fx (merge (:payload command)
                           (sync/callback-events (:id command)))})))

(defn fetch-after-success? [kind]
  (true? (get (mutation-spec kind) :fetch-after-success? false)))

(defn apply-sync-success [db command]
  (let [base-db (-> db
                    sync/dequeue-command
                    (assoc :sync-inflight nil))
        db-with-status (assoc base-db :status (or (:success-status command) "Done."))
        success-handler (get-in (mutation-spec (:kind command)) [:success])]
    (update (if success-handler
              (success-handler db-with-status command)
              {:db (merge-command-revision db-with-status command)})
            :db
            reapply-pending-commands)))

(defn apply-sync-failure [db command msg]
  (let [base-db (-> db
                    sync/dequeue-command
                    (assoc :sync-inflight nil
                           :status (str "Request failed: " msg)))
        failure-handler (get-in (mutation-spec (:kind command)) [:failure])]
    {:db (reapply-pending-commands
          (if failure-handler
            (failure-handler base-db command)
            base-db))}))

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
       (let [generate-frame? (= :generate-frame (:kind inflight))
             fetch-after-success? (fetch-after-success? (:kind inflight))
             fx (cond-> (merge (apply-sync-success db (assoc inflight :response data))
                               (if fetch-after-success?
                                 {:dispatch-n [[:sync-outbox/process]
                                               [:fetch-state]]}
                                 {:dispatch [:sync-outbox/process]}))
                  (and generate-frame?
                       (not fetch-after-success?)
                       (transport/realtime-disabled?))
                  (assoc :dispatch-after-burst {:delays [250 1000 2500]
                                                :event [:fetch-state]}))]
         fx)
       {:db db}))))

(rf/reg-event-fx
 :sync-outbox/failed
 (fn [{:keys [db]} [_ command-id msg]]
   (let [inflight (:sync-inflight db)]
     (if (= command-id (:id inflight))
       (assoc (apply-sync-failure db inflight msg)
              :dispatch-n [[:sync-outbox/process]
                           [:fetch-state]])
       {:db db}))))
