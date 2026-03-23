(ns webapp.shared.store
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.events.transport :as transport]
            [webapp.shared.model :as model]))

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

(defn- frame-row-owner-id [frame]
  (some-> (or (:parentId frame)
              (:chapterId frame)
              (:characterId frame))
          str
          not-empty))

(defn- frame-row->entity [frame]
  (let [frame-id (some-> (:frameId frame) str)
        owner-id (frame-row-owner-id frame)
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
  "Convert a queued command into the canonical entity payload for /api/entity."
  [kind payload]
  (let [local-entity (:local-entity payload)
        base-entity (normalize-entity local-entity)]
    (if base-entity
      (assoc base-entity :id (or (:id base-entity)
                                 (some-> (:id local-entity) str)
                                 (str (random-uuid))))
      nil)))

(defn local-placeholder-id [command]
  (or (get-in command [:payload :local-entity :id])
      (get-in command [:payload :local-saga :sagaId])
      (get-in command [:payload :local-roster :rosterId])
      (get-in command [:payload :local-chapter :chapterId])
      (get-in command [:payload :local-character :characterId])
      (get-in command [:payload :local-frame :frameId])))

(defn frame-by-id [db frame-id]
  (some-> (model/entity-by-id (:entities db) frame-id)
          model/frame-row))

(declare merge-frame-row build-local-frame)

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
  (let [frame-id (:frameId frame)
        owner-id (frame-row-owner-id frame)]
    (cond-> (-> db
                (update-in [:latest-state :frames]
                           (fn [frames]
                             (let [rows (vec (or frames []))]
                               (if (some (fn [row] (= (:frameId row) frame-id)) rows)
                                 (merge-frame-row rows frame)
                                 (conj rows frame)))))
                (update :hidden-frame-images dissoc frame-id)
                (assoc-in [:image-ui-by-frame-id frame-id]
                          (image-ui/image-ui-state-for-url (:imageUrl frame)))
                (assoc-entity (frame-row->entity frame)))
      owner-id
      (append-child-id owner-id frame-id))))

(defn add-frame [db owner-id owner-type frame-id]
  (add-frame-row db (build-local-frame frame-id owner-id owner-type)))

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

(defn merge-frame-row [rows frame]
  (let [target-id (:frameId frame)]
    (mapv (fn [row]
            (if (= (:frameId row) target-id)
              (merge row frame)
              row))
          (or rows []))))

(defn merge-frame-response [db frame]
  (if (seq (or (:frameId frame) ""))
    (let [owner-id (frame-row-owner-id frame)]
      (cond-> (-> db
                  (update-in [:latest-state :frames] merge-frame-row frame)
                  (cond-> (and (= "ready" (:imageStatus frame))
                               (not (str/blank? (or (:imageUrl frame) ""))))
                    (update :hidden-frame-images dissoc (:frameId frame)))
                  (assoc-in [:image-ui-by-frame-id (:frameId frame)]
                            (if (str/blank? (or (:imageUrl frame) ""))
                              :idle
                              :loading))
                  (assoc-entity (frame-row->entity frame)))
        owner-id
        (append-child-id owner-id (:frameId frame))))
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
      (let [previous-parent-id (model/entity-parent-id existing)
            next-entity (-> existing
                            (cond-> (contains? patch :title)
                              (assoc :title (:title patch)))
                            (cond-> (contains? patch :description)
                              (assoc :description (:description patch)))
                            (cond-> (contains? patch :vanityRole)
                              (assoc :vanityRole (:vanityRole patch)))
                            (cond-> (contains? patch :payload)
                              (update :payload merge (or (:payload patch) {})))
                            (update :payload #(or % {}))
                            (update :children #(vec (or % []))))
            next-parent-id (model/entity-parent-id next-entity)
            entities* (cond-> (assoc entities entity-id next-entity)
                        (and previous-parent-id (not= previous-parent-id next-parent-id))
                        (update previous-parent-id
                                (fn [parent]
                                  (when parent
                                    (update parent :children
                                            (fn [children]
                                              (->> (or children [])
                                                   (remove #(= entity-id (str %)))
                                                   (mapv str)))))))

                        next-parent-id
                        (update next-parent-id
                                (fn [parent]
                                  (when parent
                                    (update parent :children
                                            (fn [children]
                                              (let [children (vec (map str (or children [])))]
                                                (if (some #(= entity-id %) children)
                                                  children
                                                  (conj children entity-id)))))))))
            image-url-patch (get-in patch [:payload :imageUrl])
            image-url-updated? (contains? (or (:payload patch) {}) :imageUrl)]
        (cond-> (-> db
                    (assoc :entities entities*)
                    (assoc :derived-state (compute-derived-state entities*)))
          (contains? patch :description)
          (update :frame-drafts dissoc entity-id)

          (and image-url-updated? (str/blank? (or image-url-patch "")))
          (-> (assoc-in [:hidden-frame-images entity-id] true)
              (update :image-ui-by-frame-id image-ui/mark-image-idle entity-id))

          (and image-url-updated? (seq (or image-url-patch "")))
          (-> (update :hidden-frame-images dissoc entity-id)
              (assoc-in [:image-ui-by-frame-id entity-id]
                        (image-ui/image-ui-state-for-url image-url-patch)))))
      db)))

(defn update-command-entity [db payload]
  (let [entity-id (str (:id payload))
        existing (model/entity-by-id (:entities db) entity-id)
        patch (or (:patch payload) {})
        role (or (:vanityRole patch)
                 (:vanityRole existing)
                 "generic")
        title (if (contains? patch :title)
                (:title patch)
                (:title existing))
        description (if (contains? patch :description)
                      (:description patch)
                      (:description existing))
        payload-map (merge (or (:payload existing) {})
                           (or (:payload patch) {}))
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

(defn replace-row-by-id [rows id-key placeholder-id next-row]
  (mapv (fn [row]
          (if (= (id-key row) placeholder-id)
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

(defn add-local-entity [db entity]
  (let [entity* (normalize-entity entity)
        parent-id (model/entity-parent-id entity*)]
    (cond-> (assoc-entity db entity*)
      (and parent-id (:id entity*))
      (append-child-id parent-id (:id entity*)))))

(defn entity-descendant-ids [entities entity-id]
  (let [entity (model/entity-by-id entities entity-id)]
    (reduce (fn [acc child-id]
              (let [child-id* (str child-id)]
                (into (conj acc child-id*)
                      (entity-descendant-ids entities child-id*))))
            []
            (or (:children entity) []))))

(defn remove-entity-tree [db entity-id]
  (let [entities (:entities db)
        root-entity (model/entity-by-id entities entity-id)
        role (model/entity-role root-entity)
        {:keys [name-inputs-key description-inputs-key editing-key]} (entity-meta role)
        removed-ids (->> (cons (str entity-id) (entity-descendant-ids entities entity-id))
                         distinct
                         vec)
        removed-id-set (set removed-ids)
        removed-frame-ids (->> removed-ids
                               (filter (fn [id]
                                         (= "frame" (model/entity-role (model/entity-by-id entities id)))))
                               vec)
        removed-frame-id-set (set removed-frame-ids)
        remaining-frames (->> (model/gallery-frames entities)
                              (remove (fn [frame]
                                        (contains? removed-frame-id-set (:frameId frame))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? removed-frame-id-set current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) removed-frame-ids))
        parent-id (model/entity-parent-id root-entity)]
    (cond-> (-> db
                (assoc :active-frame-id next-active-id)
                (update-in name-inputs-key dissoc (str entity-id))
                (update-in description-inputs-key dissoc (str entity-id))
                (cond-> (= (get-in db editing-key) (str entity-id))
                  (assoc-in editing-key nil))
                (update :frame-drafts dissoc-ids)
                (update :open-frame-actions dissoc-ids)
                (update :hidden-frame-images dissoc-ids)
                (update :image-ui-by-frame-id
                        (fn [ui-map]
                          (reduce image-ui/remove-frame (or ui-map {}) removed-frame-ids)))
                (update-in [:latest-state :frames] (fn [_] remaining-frames))
                ((fn [db*]
                   (reduce (fn [acc id]
                             (dissoc-entity acc id))
                           db*
                           removed-ids))))
      parent-id
      (remove-child-id parent-id entity-id))))

(defn build-local-frame [frame-id owner-id owner-type]
  (let [owner-type (or owner-type "saga")]
    {:frameId frame-id
     :parentId owner-id
     :chapterId (when (= owner-type "saga") owner-id)
     :characterId (when (= owner-type "character") owner-id)
     :ownerType owner-type
     :description ""
     :imageUrl nil
     :imageStatus "draft"
     :error nil
     :createdAt (.toISOString (js/Date.))
     :frameDescription ""}))

(defn build-upload-frame [_db frame-id chapter-id image-data-url]
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

(defn replace-placeholder-frame [db placeholder-frame-id created-frame]
  (if placeholder-frame-id
    (let [created-frame-id (or (:frameId created-frame) placeholder-frame-id)
          placeholder-frame-entity (model/entity-by-id (:entities db) placeholder-frame-id)
          placeholder-frame (or (some-> placeholder-frame-entity model/frame-row)
                                (frame-by-id db placeholder-frame-id))
          placeholder-draft (get-in db [:frame-drafts placeholder-frame-id])
          placeholder-open? (true? (get-in db [:open-frame-actions placeholder-frame-id]))
          placeholder-hidden? (true? (get-in db [:hidden-frame-images placeholder-frame-id]))
          merged-frame (cond-> (merge placeholder-frame created-frame)
                         (and (seq (or (:description placeholder-frame) ""))
                              (str/blank? (or (:description created-frame) "")))
                         (assoc :description (:description placeholder-frame)))
          migrated-db (if (not= created-frame-id placeholder-frame-id)
                        (-> db
                            (update :frame-drafts (fn [m]
                                                    (cond-> (dissoc (or m {}) placeholder-frame-id)
                                                      (some? placeholder-draft)
                                                      (assoc created-frame-id placeholder-draft))))
                            (update :open-frame-actions (fn [m]
                                                          (cond-> (dissoc (or m {}) placeholder-frame-id)
                                                            placeholder-open?
                                                            (assoc created-frame-id true))))
                            (update :hidden-frame-images (fn [m]
                                                           (cond-> (dissoc (or m {}) placeholder-frame-id)
                                                             placeholder-hidden?
                                                             (assoc created-frame-id true))))
                            (update :image-ui-by-frame-id (fn [m]
                                                            (let [ui-map (or m {})
                                                                  existing (get ui-map placeholder-frame-id)]
                                                              (cond-> (image-ui/remove-frame ui-map placeholder-frame-id)
                                                                (some? existing)
                                                                (assoc created-frame-id existing)))))
                            (cond-> (= (:active-frame-id db) placeholder-frame-id)
                              (assoc :active-frame-id created-frame-id)))
                        (cond-> db
                          placeholder-hidden?
                          (assoc-in [:hidden-frame-images created-frame-id] true)))]
      (cond-> (-> migrated-db
                  (update-in [:latest-state :frames] replace-row-by-id :frameId placeholder-frame-id
                             (or merged-frame {:frameId placeholder-frame-id}))
                  (dissoc-entity placeholder-frame-id)
                  (assoc-entity (frame-row->entity (or merged-frame {:frameId created-frame-id
                                                                     :parentId (or (frame-row-owner-id placeholder-frame)
                                                                                   (get-in placeholder-frame-entity [:payload :parentId]))
                                                                     :ownerType (or (:ownerType placeholder-frame)
                                                                                    (get-in placeholder-frame-entity [:payload :ownerType])
                                                                                    "saga")
                                                                     :chapterId (or (:chapterId placeholder-frame)
                                                                                    (get-in placeholder-frame-entity [:payload :chapterId]))
                                                                     :characterId (or (:characterId placeholder-frame)
                                                                                      (get-in placeholder-frame-entity [:payload :characterId]))})))
                  (cond-> (and placeholder-frame-entity (not= created-frame-id placeholder-frame-id))
                    (replace-child-id (get-in placeholder-frame-entity [:payload :parentId])
                                      placeholder-frame-id
                                      created-frame-id)))
        (seq (or created-frame-id ""))
        (assoc-in [:image-ui-by-frame-id created-frame-id]
                  (image-ui/image-ui-state-for-url (:imageUrl merged-frame)))))
    db))

(defn generic-entity-success [db command]
  "Generic success handler for POST/PATCH to /api/entity endpoint"
  (let [response (:response command)
        kind (:kind command)
        placeholder-id (local-placeholder-id command)
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
             (cond-> (and placeholder-id entity-label (not= placeholder-id (:id entity)))
               (dissoc-entity placeholder-id))
             (cond-> entity
               (assoc-entity entity))
             (cond-> (and entity (#{"chapter" "character"} entity-label))
               (replace-child-id (get-in entity [:payload :parentId]) placeholder-id (:id entity)))
             (cond-> (and entity (= "frame" entity-label))
               (replace-child-id (get-in entity [:payload :parentId]) placeholder-id (:id entity)))
             (assoc-in (conj view-state-path :new-name) "")
             (assoc-in (conj view-state-path :new-description) "")
             (assoc-in (conj view-state-path :new-panel-open?) false))
     :start-chapter-celebration celebration?}))

(defn create-roster-success [db command]
  (let [placeholder-roster-id (get-in command [:payload :local-roster :rosterId])
        created-roster-id (or (some-> (:response command) :id str)
                              (some-> placeholder-roster-id str))
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

(defn remove-placeholder-saga [db command]
  (remove-entity-tree db (local-placeholder-id command)))

(defn remove-placeholder-entity [db placeholder-id]
  (remove-entity-tree db placeholder-id))

(defn mutation-spec [kind]
  (case kind
    :generate-frame
    {:transport-fx :post-generate-frame
     :apply-local (fn [db payload]
                    (set-frame-image-status db (:frame-id payload) "queued" (:generator payload)))
     :success (fn [db command]
                (let [response-frame (some-> (get-in command [:response :frame])
                                             normalize-entity
                                             model/frame-row)]
                  {:db (cond-> (merge-command-revision db command)
                         response-frame
                         (merge-frame-response response-frame))}))}

    :add-frame
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
                    (add-frame-row db (:local-frame payload)))
     :success (fn [db command]
                (let [placeholder-id (local-placeholder-id command)
                      response (:response command)
                      created-frame (or (:frame response)
                                        (some-> response normalize-entity model/frame-row))]
                  {:db (-> db
                           (merge-command-revision command)
                           (update :frame-drafts dissoc placeholder-id)
                           (update :open-frame-actions dissoc placeholder-id)
                           (update :image-ui-by-frame-id image-ui/remove-frame placeholder-id)
                           (cond-> created-frame
                             (replace-placeholder-frame placeholder-id created-frame)))}))
     :failure (fn [db command]
                (remove-frames db [(local-placeholder-id command)]))
     :fetch-after-success? false}

    :delete-frame
    {:transport-fx :post-delete-entity
     :apply-local (fn [db payload]
                    (remove-frames db [(:frame-id payload)]))
     :fetch-after-success? false}

    :update-entity
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
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
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
                    (add-local-entity db (:local-entity payload)))
     :success generic-entity-success
     :failure remove-placeholder-saga}

    :add-roster
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
                    (add-local-entity db (:local-entity payload)))
     :success create-roster-success
     :failure (fn [db command]
                (remove-entity-tree db (local-placeholder-id command)))}

    :add-chapter
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
                    (-> db
                        (add-local-entity (:local-entity payload))
                        (cond-> (:local-frame payload)
                          (add-frame-row (:local-frame payload)))))
     :success create-chapter-success
     :failure (fn [db command]
                (remove-placeholder-entity db (local-placeholder-id command)))}

    :add-character
    {:transport-fx :post-save-entity
     :apply-local (fn [db payload]
                    (-> db
                        (add-local-entity (:local-entity payload))
                        (cond-> (:local-frame payload)
                          (add-frame-row (:local-frame payload)))))
     :success create-character-success
     :failure (fn [db command]
                (remove-placeholder-entity db (local-placeholder-id command)))}

    :delete-saga
    {:transport-fx :post-delete-entity
     :apply-local (fn [db payload]
                    (remove-entity-tree db (:saga-id payload)))
     :fetch-after-success? false}

    :delete-chapter
    {:transport-fx :post-delete-entity
     :apply-local (fn [db payload]
                    (remove-entity-tree db (:chapter-id payload)))
     :fetch-after-success? false}

    :delete-character
    {:transport-fx :post-delete-entity
     :apply-local (fn [db payload]
                    (remove-entity-tree db (:character-id payload)))
     :fetch-after-success? false}

    nil))

(defn apply-command-locally [db command]
  (let [db* (if-let [apply-local (get-in (mutation-spec (:kind command)) [:apply-local])]
              (apply-local db (:payload command))
              db)]
    (sync-flat-entities db*)))

(defn reapply-pending-commands [db]
  (reduce apply-command-locally
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
        
        (:delete-saga :delete-chapter :delete-character :delete-frame)
        (let [entity-id (case kind
                          :delete-saga (get-in command [:payload :saga-id])
                          :delete-chapter (get-in command [:payload :chapter-id])
                          :delete-character (get-in command [:payload :character-id])
                          :delete-frame (get-in command [:payload :frame-id]))]
          {:post-delete-entity (merge {:id entity-id}
                                      (sync/callback-events (:id command)))})
        
        ; Non-CRUD operations keep their dedicated transport.
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
