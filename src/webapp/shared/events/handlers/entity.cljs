(ns webapp.shared.events.handlers.entity
  "Generic entity CRUD handlers for unified entity model."
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

;; Normalization: Convert legacy API responses into flat entity pool

(defn normalize-saga [saga]
  "Convert saga API response to generic entity."
  {:id (:sagaId saga)
   :title (:name saga)
   :description (:description saga)
   :vanityRole "saga"
   :children (or (:chapterIds saga) [])
   :payload {:sagaId (:sagaId saga)}})

(defn normalize-chapter [chapter]
  "Convert chapter API response to generic entity."
  {:id (:chapterId chapter)
   :title (:name chapter)
   :description (:description chapter)
   :vanityRole "chapter"
   :children (or (:frameIds chapter) [])
   :payload {:chapterId (:chapterId chapter)
             :sagaId (:sagaId chapter)
             :rosterId (:rosterId chapter)}})

(defn normalize-character [character]
  "Convert character API response to generic entity (character is a sequence of images)."
  {:id (:characterId character)
   :title (:name character)
   :description (:description character)
   :vanityRole "character"
   :children (or (:frameIds character) [])
   :payload {:characterId (:characterId character)
             :rosterId (:rosterId character)}})

(defn normalize-roster [roster]
  "Convert roster API response to generic entity."
  {:id (:rosterId roster)
   :title (:name roster)
   :description (:description roster)
   :vanityRole "roster"
   :children (or (:characterIds roster) [])
   :payload {:rosterId (:rosterId roster)}})

(defn normalize-frame [frame]
  "Convert frame API response to generic entity (frame is an item, no children)."
  {:id (:frameId frame)
   :title (str "Frame " (:frameNumber frame))
   :description (:description frame)
   :vanityRole "frame"
   :children []
   :payload {:frameId (:frameId frame)
             :chapterId (:chapterId frame)
             :characterId (:characterId frame)
             :imageUrl (:imageUrl frame)
             :imageStatus (:imageStatus frame)
             :frameNumber (:frameNumber frame)
             :createdAt (:createdAt frame)}})

(defn normalize-api-state [legacy-state]
  "Convert legacy API state (with sagas, rosters, chapters, characters, frames) 
   into flat entity pool."
  (let [sagas (or (:sagas legacy-state) [])
        rosters (or (:rosters legacy-state) [])
        chapters (or (:chapters legacy-state) [])
        characters (or (:characters legacy-state) [])
        frames (or (:frames legacy-state) [])
        
        saga-entities (map normalize-saga sagas)
        roster-entities (map normalize-roster rosters)
        chapter-entities (map normalize-chapter chapters)
        character-entities (map normalize-character characters)
        frame-entities (map normalize-frame frames)
        
        all-entities (concat saga-entities roster-entities chapter-entities 
                             character-entities frame-entities)
        entities-map (reduce
                      (fn [acc entity]
                        (assoc acc (:id entity) entity))
                      {}
                      all-entities)]
    entities-map))

(defn compute-derived-state [entities]
  "Compute cached lookups from entities pool.
   Returns: {:children-by-parent-id {parent-id [child-ids]}}"
  (let [children-by-parent (reduce
                            (fn [acc [entity-id entity]]
                              (if (seq (:children entity))
                                (assoc acc entity-id (:children entity))
                                acc))
                            {}
                            entities)]
    {:children-by-parent-id children-by-parent}))

;; Entity CRUD Events

(rf/reg-event-db
  :entity-add
  (fn [db [_ entity-id entity-data]]
    (let [entity (merge
                   {:id entity-id
                    :vanityRole "generic"
                    :children []
                    :payload {}}
                   entity-data)
          next-entities (assoc (:entities db) entity-id entity)]
      (-> db
          (assoc :entities next-entities)
          (assoc :derived-state (compute-derived-state next-entities))))))

(rf/reg-event-db
  :entity-delete
  (fn [db [_ entity-id]]
    (let [next-entities (dissoc (:entities db) entity-id)
          ;; Remove from parent's children
          all-entities (reduce
                        (fn [acc [id entity]]
                          (if (contains? (set (:children entity)) entity-id)
                            (assoc acc id (update entity :children
                                                  (fn [children]
                                                    (vec (remove #(= % entity-id) children)))))
                            acc))
                        next-entities
                        next-entities)]
      (-> db
          (assoc :entities all-entities)
          (assoc :derived-state (compute-derived-state all-entities))))))

(rf/reg-event-db
  :entity-add-child
  (fn [db [_ parent-id child-id]]
    (let [parent (get-in db [:entities parent-id])
          next-parent (update parent :children
                             (fn [children]
                               (vec (distinct (conj (or children []) child-id)))))
          next-entities (assoc (:entities db) parent-id next-parent)]
      (-> db
          (assoc :entities next-entities)
          (assoc :derived-state (compute-derived-state next-entities))))))

(rf/reg-event-db
  :entity-remove-child
  (fn [db [_ parent-id child-id]]
    (let [parent (get-in db [:entities parent-id])
          next-parent (update parent :children
                             (fn [children]
                               (vec (remove #(= % child-id) (or children [])))))
          next-entities (assoc (:entities db) parent-id next-parent)]
      (-> db
          (assoc :entities next-entities)
          (assoc :derived-state (compute-derived-state next-entities))))))

;; UI State Events (editing, drafts, etc)

(rf/reg-event-db
  :ui-entity-field-changed
  (fn [db [_ entity-id field value]]
    (assoc-in db [:ui-state entity-id field] value)))

(rf/reg-event-db
  :ui-entity-editing-start
  (fn [db [_ entity-id]]
    (let [entity (get-in db [:entities entity-id])]
      (assoc-in db [:ui-state entity-id]
                {:editing? true
                 :name-draft (:title entity)
                 :description-draft (:description entity)}))))

(rf/reg-event-db
  :ui-entity-editing-stop
  (fn [db [_ entity-id]]
    (assoc-in db [:ui-state entity-id :editing?] false)))

;; Batch loading (for initial state fetch)

(rf/reg-event-db
  :entities-load-from-legacy-state
  (fn [db [_ legacy-state]]
    (let [entities-map (normalize-api-state legacy-state)]
      (-> db
          (assoc :entities entities-map)
          (assoc :derived-state (compute-derived-state entities-map))))))

(rf/reg-event-db
  :entities-load-batch
  (fn [db [_ entities-list]]
    (let [entities-map (reduce
                        (fn [acc entity]
                          (assoc acc (:id entity) entity))
                        {}
                        entities-list)
          next-entities (merge (:entities db) entities-map)]
      (-> db
          (assoc :entities next-entities)
          (assoc :derived-state (compute-derived-state next-entities))))))

(rf/reg-event-db
  :entities-replace-all
  (fn [db [_ entities-list]]
    (let [entities-map (reduce
                        (fn [acc entity]
                          (assoc acc (:id entity) entity))
                        {}
                        entities-list)]
      (-> db
          (assoc :entities entities-map)
          (assoc :derived-state (compute-derived-state entities-map))))))
