(ns webapp.shared.store
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.events.transport :as transport]
            [webapp.shared.model :as model]))

; Entity conversion helpers for new generic API
(defn payload->entity 
  "Convert domain-specific payload to unified entity structure"
  [kind payload]
  (case kind
    :add-saga
    {:vanityRole "saga"
     :title (:name payload)
     :description (or (:description payload) "")}
    
    :add-roster
    {:vanityRole "roster"
     :title (:name payload)
     :description (or (:description payload) "")}
    
    :add-chapter
    {:vanityRole "chapter"
     :title (:name payload)
     :description (or (:description payload) "")
     :children []}
    
    :add-character
    {:vanityRole "character"
     :title (:name payload)
     :description (or (:description payload) "")
     :children []}
    
    :add-frame
    {:vanityRole "frame"
     :title ""
     :description (or (:description payload) "")
     :children []}
    
    :update-entity
    nil
    
    nil))

;; --- Entity normalization helpers ------------------------------------------

(defn- normalize-saga-entity [saga]
  {:id (some-> (:sagaId saga) str)
   :title (:name saga)
   :description (:description saga)
   :vanityRole "saga"
   :children (mapv str (or (:chapterIds saga) []))
   :payload {:sagaId (some-> (:sagaId saga) str)}})

(defn- normalize-chapter-entity [chapter]
  {:id (some-> (:chapterId chapter) str)
   :title (:name chapter)
   :description (:description chapter)
   :vanityRole "chapter"
   :children (mapv str (or (:frameIds chapter) []))
   :payload {:chapterId (some-> (:chapterId chapter) str)
             :sagaId (some-> (:sagaId chapter) str)
             :rosterId (some-> (:rosterId chapter) str)}})

(defn- normalize-roster-entity [roster]
  {:id (some-> (:rosterId roster) str)
   :title (:name roster)
   :description (:description roster)
   :vanityRole "roster"
   :children (mapv str (or (:characterIds roster) []))
   :payload {:rosterId (some-> (:rosterId roster) str)}})

(defn- normalize-character-entity [character]
  {:id (some-> (:characterId character) str)
   :title (:name character)
   :description (:description character)
   :vanityRole "character"
   :children (mapv str (or (:frameIds character) []))
   :payload {:characterId (some-> (:characterId character) str)
             :rosterId (some-> (:rosterId character) str)}})

(defn- normalize-frame-entity [frame]
  {:id (some-> (:frameId frame) str)
   :title (str "Frame " (:frameNumber frame))
   :description (:description frame)
   :vanityRole "frame"
   :children []
   :payload {:frameId (some-> (:frameId frame) str)
             :chapterId (some-> (:chapterId frame) str)
             :characterId (some-> (:characterId frame) str)
             :imageUrl (:imageUrl frame)
             :imageStatus (:imageStatus frame)
             :frameNumber (:frameNumber frame)
             :createdAt (:createdAt frame)}})

(defn normalize-entity [entity]
  (let [entity-id (some-> (:id entity) str)]
    (when (seq (or entity-id ""))
      (-> entity
          (assoc :id entity-id)
          (update :children #(mapv str (or % [])))
          (update :payload #(or % {}))))))

(defn normalize-entities-map [entities]
  (reduce (fn [acc [_ entity]]
            (if-let [normalized (normalize-entity entity)]
              (assoc acc (:id normalized) normalized)
              acc))
          {}
          (or entities {})))

(defn compute-derived-state [entities]
  {:children-by-parent-id
   (reduce (fn [acc [entity-id entity]]
             (if (seq (:children entity))
               (assoc acc entity-id (:children entity))
               acc))
           {}
           entities)})

(defn refresh-entities-from-flat
  "Set entities/derived-state directly from an entities map."
  [db entities]
  (let [entities* (normalize-entities-map entities)
        derived (compute-derived-state entities*)]
    (assoc db
           :entities entities*
           :derived-state derived)))

(defn sync-flat-entities [db]
  (refresh-entities-from-flat db (:entities db)))

(defn- frame-row->entity [frame]
  (let [frame-id (some-> (:frameId frame) str)
        owner-id (some-> (:chapterId frame) str)
        owner-type (or (some-> (:ownerType frame) str) "saga")]
    {:id frame-id
     :title (or (:title frame)
                (when (some? (:frameNumber frame))
                  (str "Frame " (:frameNumber frame)))
                "")
     :description (:description frame)
     :vanityRole "frame"
     :children []
     :payload {:frameId frame-id
               :parentId owner-id
               :ownerType owner-type
               :chapterId (when (= owner-type "saga") owner-id)
               :characterId (when (= owner-type "character") owner-id)
               :imageUrl (:imageUrl frame)
               :imageStatus (:imageStatus frame)
               :frameNumber (:frameNumber frame)
               :createdAt (:createdAt frame)
               :error (:error frame)}}))

(defn- entity-label->entity [entity-label entity]
  (case (str entity-label)
    "saga" (normalize-saga-entity entity)
    "chapter" (assoc-in (normalize-chapter-entity entity) [:payload :parentId] (some-> (:sagaId entity) str))
    "roster" (normalize-roster-entity entity)
    "character" (assoc-in (normalize-character-entity entity) [:payload :parentId] (some-> (:rosterId entity) str))
    "frame" (frame-row->entity entity)
    (normalize-entity entity)))

(defn- update-entities [db f]
  (let [entities (normalize-entities-map (:entities db))
        next-entities (normalize-entities-map (f entities))]
    (assoc db
           :entities next-entities
           :derived-state (compute-derived-state next-entities))))

(defn- assoc-entity [db entity]
  (if-let [entity* (normalize-entity entity)]
    (update-entities db #(assoc % (:id entity*) entity*))
    db))

(defn- update-entity-in-pool [db entity-id f]
  (update-entities
   db
   (fn [entities]
     (if-let [entity (model/entity-by-id entities entity-id)]
       (assoc entities entity-id (normalize-entity (f entity)))
       entities))))

(defn- dissoc-entity [db entity-id]
  (update-entities db #(dissoc % (str entity-id))))

(defn- append-child-id [db parent-id child-id]
  (update-entity-in-pool
   db
   (str parent-id)
   (fn [entity]
     (update entity :children
             (fn [children]
               (let [child-id (str child-id)
                     children (vec (or children []))]
                 (if (some #(= child-id %) children)
                   children
                   (conj children child-id))))))))

(defn- replace-child-id [db parent-id old-child-id new-child-id]
  (update-entity-in-pool
   db
   (str parent-id)
   (fn [entity]
     (update entity :children
             (fn [children]
               (mapv (fn [child-id]
                       (if (= (str child-id) (str old-child-id))
                         (str new-child-id)
                         (str child-id)))
                     (or children [])))))))

(defn- remove-child-id [db parent-id child-id]
  (update-entity-in-pool
   db
   (str parent-id)
   (fn [entity]
     (update entity :children
             (fn [children]
               (->> (or children [])
                    (remove #(= (str %) (str child-id)))
                    (mapv str)))))))

(defn command->generic-entity-payload
  "Convert command payload to generic entity structure with id"
  [kind payload]
  (let [base-entity (payload->entity kind payload)
        optimistic (or (:optimistic-saga payload)
                      (:optimistic-roster payload)
                      (:optimistic-chapter payload)
                      (:optimistic-character payload)
                      (:optimistic-frame payload))]
    (if base-entity
      (let [entity-id (if optimistic
                       (case kind
                         :add-saga (:sagaId optimistic)
                         :add-roster (:rosterId optimistic)
                         :add-chapter (:chapterId optimistic)
                         :add-character (:characterId optimistic)
                         :add-frame (:frameId optimistic)
                         :update-entity (:id payload)
                         (str (random-uuid)))
                       (str (random-uuid)))]
        (assoc base-entity :id entity-id))
      nil)))

(defn frame-by-id [db frame-id]
  (some-> (model/entity-by-id (:entities db) frame-id)
          model/frame-row))

(declare merge-frame-row)

(defn set-frame-image-status [db frame-id image-status generator]
  (let [set-status (fn [frame]
                     (if (= (:frameId frame) frame-id)
                       (assoc frame :imageStatus image-status)
                       frame))]
    (-> db
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv set-status (or frames []))))
        (update-entity-in-pool frame-id
                               #(cond-> (assoc-in % [:payload :imageStatus] image-status)
                                  (some? generator)
                                  (assoc-in [:payload :generator] generator))))))

(defn add-frame-row [db frame]
  (let [frame-id (:frameId frame)]
    (-> db
        (update-in [:latest-state :frames]
                   (fn [frames]
                     (let [rows (vec (or frames []))]
                       (if (some (fn [row] (= (:frameId row) frame-id)) rows)
                         (merge-frame-row rows frame)
                         (conj rows frame)))))
        (update :hidden-frame-images dissoc frame-id)
        (assoc-in [:image-ui-by-frame-id frame-id]
                  (image-ui/image-ui-state-for-url (:imageUrl frame)))
        (assoc-entity (frame-row->entity frame))
        (append-child-id (:chapterId frame) frame-id))))

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
        remaining-frames (->> (model/gallery-frames (:entities db))
                              (remove (fn [frame] (contains? frame-id-set (:frameId frame))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? frame-id-set current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) frame-ids))]
    (reduce
     (fn [db* frame-id]
       (let [owner-id (or (get-in (model/entity-by-id (:entities db*) frame-id) [:payload :parentId])
                          (some-> (frame-by-id db* frame-id) :chapterId))]
         (cond-> (-> db*
                     (assoc :active-frame-id next-active-id)
                     (update :frame-drafts dissoc-ids)
                     (update :open-frame-actions dissoc-ids)
                     (update :hidden-frame-images dissoc-ids)
                     (update :image-ui-by-frame-id
                             (fn [ui-map]
                               (reduce image-ui/remove-frame (or ui-map {}) frame-ids)))
                     (update-in [:latest-state :frames] (fn [_] remaining-frames))
                     (dissoc-entity frame-id))
           owner-id (remove-child-id owner-id frame-id))))
     db
     frame-ids)))

(defn clear-frame-image [db frame-id]
  (let [clear-image (fn [frame]
                      (if (= (:frameId frame) frame-id)
                        (assoc frame :imageUrl nil)
                        frame))]
    (-> db
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv clear-image (or frames []))))
        (assoc-in [:hidden-frame-images frame-id] true)
        (update :image-ui-by-frame-id image-ui/mark-image-idle frame-id)
        (update-entity-in-pool frame-id
                               #(assoc-in % [:payload :imageUrl] nil)))))

(defn replace-frame-image [db frame-id image-data-url]
  (let [replace-image (fn [frame]
                        (if (= (:frameId frame) frame-id)
                          (-> frame
                              (assoc :imageUrl image-data-url)
                              (assoc :imageStatus "uploading")
                              (assoc :error nil))
                          frame))]
    (-> db
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv replace-image (or frames []))))
        (update :hidden-frame-images dissoc frame-id)
        (assoc-in [:image-ui-by-frame-id frame-id] :loading)
        (update-entity-in-pool frame-id
                               #(-> %
                                    (assoc-in [:payload :imageUrl] image-data-url)
                                    (assoc-in [:payload :imageStatus] "uploading")
                                    (update :payload dissoc :error))))))

(defn update-frame-description [db frame-id description]
  (let [normalized (or (some-> (or description "") str str/trim) "")
        set-description (fn [frame]
                          (if (= (:frameId frame) frame-id)
                            (assoc frame :description normalized)
                            frame))]
    (-> db
        (update :frame-drafts dissoc frame-id)
        (update-in [:latest-state :frames]
                   (fn [frames] (mapv set-description (or frames []))))
        (update-entity-in-pool frame-id #(assoc % :description normalized)))))

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
        (update-in [:latest-state :frames] merge-frame-row frame)
        (cond-> (and (= "ready" (:imageStatus frame))
                     (not (str/blank? (or (:imageUrl frame) ""))))
          (update :hidden-frame-images dissoc (:frameId frame)))
        (assoc-in [:image-ui-by-frame-id (:frameId frame)]
                  (if (str/blank? (or (:imageUrl frame) ""))
                    :idle
                    :loading))
        (assoc-entity (frame-row->entity frame))
        (append-child-id (:chapterId frame) (:frameId frame)))
    db))

(defn merge-entity-row [rows id-key entity]
  (let [target-id (get entity id-key)
        found (atom false)
        updated (mapv (fn [row]
                        (if (= (get row id-key) target-id)
                          (do (reset! found true) (merge row entity))
                          row))
                      (or rows []))]
    (if @found
      updated
      (conj (vec (or rows [])) entity))))

(defn update-flat-entity [db id name description type]
  (let [patch (or name {})
        entity-id (str id)
        entities (normalize-entities-map (:entities db))
        existing (model/entity-by-id entities entity-id)]
    (if (map? existing)
      (let [next-entity (-> existing
                            (cond-> (contains? patch :title)
                              (assoc :title (:title patch)))
                            (cond-> (contains? patch :description)
                              (assoc :description (:description patch)))
                            (update :payload #(or % {}))
                            (update :children #(vec (or % []))))
            entities* (assoc entities entity-id next-entity)]
        (-> db
            (assoc :entities entities*)
            (assoc :derived-state (compute-derived-state entities*))))
      db)))

(defn update-command-entity [db payload]
  (let [entity-id (str (:id payload))
        existing (model/entity-by-id (:entities db) entity-id)
        patch (or (:patch payload) {})
        role (or (:vanityRole existing)
                 "generic")
        title (if (contains? patch :title)
                (:title patch)
                (:title existing))
        description (if (contains? patch :description)
                      (:description patch)
                      (:description existing))
        payload-map (or (:payload existing) {})
        children (vec (or (:children existing) []))]
    {:id entity-id
     :vanityRole role
     :title (or title "")
     :description (or description "")
     :children (mapv str children)
     :payload payload-map}))

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

(defn entity-meta [entity-label]
  (case (str entity-label)
    "saga"
    {:name-inputs-key [:view-state :index :name-inputs]
     :description-inputs-key [:view-state :index :description-inputs]
     :editing-key [:view-state :index :editing-id]}

    "character"
    {:name-inputs-key [:view-state :roster :name-inputs]
     :description-inputs-key [:view-state :roster :description-inputs]
     :editing-key [:view-state :roster :editing-id]}

    {:name-inputs-key [:view-state :saga :name-inputs]
     :description-inputs-key [:view-state :saga :description-inputs]
     :editing-key [:view-state :saga :editing-id]}))

(defn add-saga-row [db saga]
  (assoc-entity db (entity-label->entity "saga" saga)))

(defn add-roster-row [db roster]
  (assoc-entity db (entity-label->entity "roster" roster)))

(defn add-entity-row [db entity-label entity]
  (let [parent-id (or (:sagaId entity) (:rosterId entity))
        child-id (case (str entity-label)
                   "chapter" (:chapterId entity)
                   "character" (:characterId entity)
                   nil)]
    (cond-> (assoc-entity db (entity-label->entity entity-label entity))
      (and parent-id child-id)
      (append-child-id parent-id child-id))))

(defn update-chapter-roster [db chapter-id roster-id]
  (-> db
      (update-entity-in-pool
       chapter-id
       (fn [entity]
         (let [existing (vec (remove str/blank? (or (get-in entity [:payload :rosterIds]) [])))
               remaining (->> existing
                              (remove (fn [existing-id]
                                        (= existing-id roster-id)))
                              vec)]
           (-> entity
               (assoc-in [:payload :rosterId] roster-id)
               (assoc-in [:payload :rosterIds] (vec (cons roster-id remaining)))))))))

(defn add-chapter-roster [db chapter-id roster-id]
  (-> db
      (update-entity-in-pool
       chapter-id
       (fn [entity]
         (let [existing (vec (remove str/blank? (or (get-in entity [:payload :rosterIds]) [])))
               next-roster-ids (if (some (fn [existing-id]
                                           (= existing-id roster-id))
                                         existing)
                                 existing
                                 (conj existing roster-id))]
           (-> entity
               (update :payload assoc :rosterId (or (get-in entity [:payload :rosterId]) roster-id))
               (assoc-in [:payload :rosterIds] next-roster-ids)))))))

(defn remove-entity [db entity-label entity-id]
  (let [{:keys [name-inputs-key description-inputs-key editing-key]} (entity-meta entity-label)
        owner-type (if (= "character" (str entity-label)) "character" "saga")
        removed-frame-ids (->> (model/frames-for-owner (:entities db) owner-type entity-id)
                               (filter (fn [frame]
                                         (= (or (:ownerType frame) "saga") owner-type)))
                               (map :frameId)
                               set)
        remaining-frames (->> (model/gallery-frames (:entities db))
                              (remove (fn [frame]
                                        (contains? removed-frame-ids (:frameId frame))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? removed-frame-ids current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) removed-frame-ids))]
    (-> db
        (assoc :active-frame-id next-active-id)
        (update-in name-inputs-key dissoc entity-id)
        (update-in description-inputs-key dissoc entity-id)
        (cond-> (= (get-in db editing-key) entity-id)
          (assoc-in editing-key nil))
        (update :frame-drafts dissoc-ids)
        (update :open-frame-actions dissoc-ids)
        (update :hidden-frame-images dissoc-ids)
        (update :image-ui-by-frame-id dissoc-ids)
        (update-in [:latest-state :frames] (fn [_] remaining-frames))
        (dissoc-entity entity-id)
        (cond-> (#{"chapter" "character"} (str entity-label))
          (remove-child-id (if (= "character" (str entity-label))
                             (get-in (model/entity-by-id (:entities db) entity-id) [:payload :rosterId])
                             (get-in (model/entity-by-id (:entities db) entity-id) [:payload :sagaId]))
                           entity-id))
        ((fn [db*]
           (reduce (fn [acc frame-id]
                     (dissoc-entity acc frame-id))
                   db*
                   removed-frame-ids))))))

(defn remove-saga [db saga-id]
  (let [chapter-ids (->> (model/children-by-role (:entities db) saga-id :chapter)
                         (map :id)
                         set)
        frame-ids (->> (model/gallery-frames (:entities db))
                       (filter (fn [frame] (contains? chapter-ids (:chapterId frame))))
                       (map :frameId)
                       vec)]
    (-> db
        (update-in [:latest-state :frames] (fn [rows]
                                             (->> (or rows [])
                                                  (remove (fn [frame]
                                                            (contains? chapter-ids (:chapterId frame))))
                                                  vec)))
        (update :frame-drafts (fn [m] (apply dissoc (or m {}) frame-ids)))
        (update :open-frame-actions (fn [m] (apply dissoc (or m {}) frame-ids)))
        (update :image-ui-by-frame-id (fn [m] (reduce image-ui/remove-frame (or m {}) frame-ids)))
        (dissoc-entity saga-id)
        ((fn [db*]
           (reduce (fn [acc chapter-id]
                     (dissoc-entity acc chapter-id))
                   db*
                   chapter-ids)))
        ((fn [db*]
           (reduce (fn [acc frame-id]
                     (dissoc-entity acc frame-id))
                   db*
                   frame-ids))))))

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

(defn- role-entities [db role]
  (model/entities-by-role (:entities db) role))

(defn next-saga-number [db]
  (inc (count (role-entities db :saga))))

(defn next-roster-number [db]
  (inc (count (role-entities db :roster))))

(defn next-chapter-number [db saga-id]
  (inc (count (model/children-by-role (:entities db) saga-id :chapter))))

(defn next-character-number [db]
  (inc (count (role-entities db :character))))

(defn next-frame-number [db owner-id owner-type]
  (let [rows (model/frames-for-owner (:entities db) owner-type owner-id)]
    (inc (reduce max 0 (keep :frameNumber rows)))))

(defn replace-temp-frame [db temp-frame-id created-frame]
  (if temp-frame-id
    (let [created-frame-id (or (:frameId created-frame) temp-frame-id)
          temp-frame-entity (model/entity-by-id (:entities db) temp-frame-id)
          temp-frame (or (some-> temp-frame-entity model/frame-row)
                         (frame-by-id db temp-frame-id))
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
                  (update-in [:latest-state :frames] replace-row-by-id :frameId temp-frame-id
                             (or merged-frame {:frameId temp-frame-id}))
                  (dissoc-entity temp-frame-id)
                  (assoc-entity (frame-row->entity (or merged-frame {:frameId created-frame-id
                                                                     :chapterId (or (:chapterId temp-frame)
                                                                                    (get-in temp-frame-entity [:payload :parentId]))
                                                                     :ownerType (or (:ownerType temp-frame)
                                                                                    (get-in temp-frame-entity [:payload :ownerType])
                                                                                    "saga")})))
                  (cond-> (and temp-frame-entity (not= created-frame-id temp-frame-id))
                    (replace-child-id (get-in temp-frame-entity [:payload :parentId])
                                      temp-frame-id
                                      created-frame-id)))
        (seq (or created-frame-id ""))
        (assoc-in [:image-ui-by-frame-id created-frame-id]
                  (image-ui/image-ui-state-for-url (:imageUrl merged-frame)))))
    db))

(defn replace-temp-frames [db temp-frame-ids created-frames]
  (reduce (fn [acc [temp-id created-frame]]
            (replace-temp-frame acc temp-id created-frame))
          db
          (map vector (or temp-frame-ids []) (or created-frames []))))

(defn generic-entity-success [db command]
  "Generic success handler for POST/PATCH to /api/entity endpoint"
  (let [response (:response command)
        kind (:kind command)
        temp-id (case kind
                  :add-saga (get-in command [:payload :optimistic-saga :sagaId])
                  :add-roster (get-in command [:payload :optimistic-roster :rosterId])
                  :add-chapter (get-in command [:payload :optimistic-chapter :chapterId])
                  :add-character (get-in command [:payload :optimistic-character :characterId])
                  :add-frame (get-in command [:payload :optimistic-frame :frameId])
                  nil)
        entity-label (case kind
                       :add-saga "saga"
                       :add-roster "roster"
                       :add-chapter "chapter"
                       :add-character "character"
                       :add-frame "frame"
                       nil)
        entity (normalize-entity response)
        celebration? (= :add-chapter kind)
        view-state-path (case entity-label
                          "saga" [:view-state :index]
                          "roster" [:view-state :index]
                          "chapter" [:view-state :saga]
                          "character" [:view-state :roster]
                          nil)]
    {:db (-> db
             (merge-command-revision command)
             (cond-> (and temp-id entity-label (not= temp-id (:id entity)))
               (dissoc-entity temp-id))
             (cond-> entity
               (assoc-entity entity))
             (cond-> (and entity (#{"chapter" "character"} entity-label))
               (replace-child-id (get-in entity [:payload :parentId]) temp-id (:id entity)))
             (cond-> (and entity (= "frame" entity-label))
               (replace-child-id (get-in entity [:payload :parentId]) temp-id (:id entity)))
             (assoc-in (conj view-state-path :new-name) "")
             (assoc-in (conj view-state-path :new-description) "")
             (assoc-in (conj view-state-path :new-panel-open?) false))
     :start-chapter-celebration celebration?}))

(defn create-roster-success [db command]
  (let [temp-roster-id (get-in command [:payload :optimistic-roster :rosterId])
        created-roster-id (or (some-> (:response command) :id str)
                              (some-> temp-roster-id str))
        after-create (:after-create (:payload command))
        saga-id (:saga-id after-create)
        dispatches (case (:mode after-create)
                     :add-chapter [[:enqueue-add-chapter saga-id
                                    created-roster-id
                                    (:chapter-name after-create)
                                    (:chapter-description after-create)]
                                   [:navigate-roster-page created-roster-id saga-id]]
                     :edit-chapter-roster [[:update-chapter-roster (:chapter-id after-create)
                                            created-roster-id]
                                           [:navigate-roster-page created-roster-id saga-id]]
                     :add-linked-roster [[:add-chapter-roster (:chapter-id after-create)
                                          created-roster-id]
                                         [:navigate-roster-page created-roster-id saga-id]]
                     [[:navigate-roster-page created-roster-id saga-id]])]
    {:db (:db (generic-entity-success db command))
     :dispatch-n (vec dispatches)}))

(defn create-chapter-success [db command]
  (assoc (generic-entity-success db command)
         :start-chapter-celebration true))

(defn create-character-success [db command]
  (generic-entity-success db command))

(defn remove-temp-saga [db command]
  (let [temp-id (get-in command [:payload :optimistic-saga :sagaId])]
    (-> db
        (dissoc-entity temp-id))))

(defn remove-temp-entity [db entity-label temp-id]
  (remove-entity db entity-label temp-id))

(defn mutation-spec [kind]
  (case kind
    :generate-frame
    {:transport-fx :post-generate-frame
     :optimistic (fn [db payload]
                   (set-frame-image-status db (:frame-id payload) "queued" (:generator payload)))
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
                      response (:response command)
                      created-frame (or (:frame response)
                                        (some-> response normalize-entity model/frame-row))]
                  {:db (-> db
                           (merge-command-revision command)
                           (update :frame-drafts dissoc temp-id)
                           (update :open-frame-actions dissoc temp-id)
                           (update :image-ui-by-frame-id image-ui/remove-frame temp-id)
                           (cond-> created-frame
                             (replace-temp-frame temp-id created-frame)))}))
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

    :update-entity
    {:transport-fx :post-save-entity
     :optimistic (fn [db payload]
                   (update-flat-entity db (:id payload)
                                       (:patch payload)
                                       nil
                                       nil))
     :success (fn [db command]
                (let [entity (get-in command [:response])]
                  {:db (if (map? entity)
                         (let [entity-id (str (:id entity))
                               entities (assoc (normalize-entities-map (:entities db))
                                               entity-id
                                               (or (normalize-entity entity) entity))]
                           (-> db
                               (merge-command-revision command)
                               (assoc :entities entities)
                               (assoc :derived-state (compute-derived-state entities))))
                         (merge-command-revision db command))}))}

    :add-saga
    {:transport-fx :post-add-saga
     :optimistic (fn [db payload]
                   (add-saga-row db (:optimistic-saga payload)))
     :success generic-entity-success
     :failure remove-temp-saga}

    :add-roster
    {:transport-fx :post-add-roster
     :optimistic (fn [db payload]
                   (add-roster-row db (:optimistic-roster payload)))
     :success create-roster-success
     :failure (fn [db command]
                (dissoc-entity db (get-in command [:payload :optimistic-roster :rosterId])))}

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

    :update-chapter-roster
    {:transport-fx :post-update-chapter-roster
     :optimistic (fn [db payload]
                   (update-chapter-roster db (:chapter-id payload)
                                          (:roster-id payload)))}

    :add-chapter-roster
    {:transport-fx :post-add-chapter-roster
     :optimistic (fn [db payload]
                   (add-chapter-roster db (:chapter-id payload)
                                       (:roster-id payload)))}

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
  (let [db* (if-let [optimistic (get-in (mutation-spec (:kind command)) [:optimistic])]
              (optimistic db (:payload command))
              db)]
    (sync-flat-entities db*)))

(defn reapply-pending-commands [db]
  (reduce apply-command-optimistically
          db
          (concat (when-let [command (:sync-inflight db)]
                    [command])
                  (or (:sync-outbox db) []))))

(defn command->fx [db command]
  (let [kind (:kind command)
        transport-fx (get-in (mutation-spec kind) [:transport-fx])]
    (when transport-fx
      (case kind
        ; Generic entity operations use new unified API
        (:add-saga :add-roster :add-chapter :add-character :add-frame :update-entity)
        (let [is-update (= :update-entity kind)
              entity (if is-update
                       (update-command-entity db (:payload command))
                       (command->generic-entity-payload kind (:payload command)))]
          {:post-save-entity (merge {:entity entity
                                     :is-update is-update}
                                    (sync/callback-events (:id command)))})
        
        (:delete-saga :delete-chapter :delete-character)
        (let [entity-id (case kind
                          :delete-saga (get-in command [:payload :saga-id])
                          :delete-chapter (get-in command [:payload :chapter-id])
                          :delete-character (get-in command [:payload :character-id]))]
          {:post-delete-entity (merge {:id entity-id}
                                      (sync/callback-events (:id command)))})
        
        ; Keep other operations on old transport for now
        {transport-fx (merge (:payload command)
                            (sync/callback-events (:id command)))}))))

(defn fetch-after-success? [kind]
  (true? (get (mutation-spec kind) :fetch-after-success? false)))

(defn apply-sync-success [db command]
  (let [base-db (-> db
                    sync/dequeue-command
                    (assoc :sync-inflight nil))
        db-with-status (assoc base-db :status (or (:success-status command) "Done."))
        success-handler (get-in (mutation-spec (:kind command)) [:success])
        kind (:kind command)]
    (-> (if success-handler
          (success-handler db-with-status command)
          {:db (merge-command-revision db-with-status command)})
        (update :db reapply-pending-commands)
        (update :db sync-flat-entities))))

(defn apply-sync-failure [db command msg]
  (let [base-db (-> db
                    sync/dequeue-command
                    (assoc :sync-inflight nil
                           :status (str "Request failed: " msg)))
        failure-handler (get-in (mutation-spec (:kind command)) [:failure])
        kind (:kind command)]
    {:db (-> (if failure-handler
               (failure-handler base-db command)
               base-db)
             reapply-pending-commands
             sync-flat-entities)}))

(rf/reg-event-fx
 :sync-outbox/process
 (fn [{:keys [db]} _]
   (let [inflight (:sync-inflight db)
         next-command (first (or (:sync-outbox db) []))
         mutation-fx (when next-command (command->fx db next-command))]
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
