(ns webapp.shared.events.handlers.entity
  "Generic entity CRUD handlers for unified entity model."
  (:require [re-frame.core :as rf]
            [webapp.shared.store :as store]))

(defn- with-derived-state [db entities]
  (assoc db
         :entities entities
         :derived-state (store/compute-derived-state entities)))

(rf/reg-event-db
 :entity-add
 (fn [db [_ entity-id entity-data]]
   (let [entity (-> (merge {:id entity-id
                            :vanityRole "generic"
                            :children []
                            :payload {}}
                           entity-data)
                    store/normalize-entity)
         next-entities (assoc (store/normalize-entities-map (:entities db))
                              (:id entity)
                              entity)]
     (with-derived-state db next-entities))))

(rf/reg-event-db
 :entity-delete
 (fn [db [_ entity-id]]
   (let [entity-id* (str entity-id)
         next-entities (dissoc (store/normalize-entities-map (:entities db)) entity-id*)
         all-entities (reduce (fn [acc [id entity]]
                                (if (contains? (set (:children entity)) entity-id*)
                                  (assoc acc id (update entity :children
                                                        (fn [children]
                                                          (vec (remove #(= % entity-id*) children)))))
                                  acc))
                              next-entities
                              next-entities)]
     (with-derived-state db all-entities))))

(rf/reg-event-db
 :entity-add-child
 (fn [db [_ parent-id child-id]]
   (let [parent-id* (str parent-id)
         child-id* (str child-id)
         parent (get-in db [:entities parent-id*])
         next-parent (update parent :children
                             (fn [children]
                               (vec (distinct (conj (or children []) child-id*)))))
         next-entities (assoc (:entities db) parent-id* next-parent)]
     (with-derived-state db next-entities))))

(rf/reg-event-db
 :entity-remove-child
 (fn [db [_ parent-id child-id]]
   (let [parent-id* (str parent-id)
         child-id* (str child-id)
         parent (get-in db [:entities parent-id*])
         next-parent (update parent :children
                             (fn [children]
                               (vec (remove #(= % child-id*) (or children [])))))
         next-entities (assoc (:entities db) parent-id* next-parent)]
     (with-derived-state db next-entities))))

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

(rf/reg-event-db
 :entities-load-batch
 (fn [db [_ entities-list]]
   (let [entities-map (store/normalize-entities-map
                       (reduce (fn [acc entity]
                                 (assoc acc (:id entity) entity))
                               {}
                               entities-list))
         next-entities (merge (store/normalize-entities-map (:entities db)) entities-map)]
     (with-derived-state db next-entities))))

(rf/reg-event-db
 :entities-replace-all
 (fn [db [_ entities-list]]
   (let [entities-map (store/normalize-entities-map
                       (reduce (fn [acc entity]
                                 (assoc acc (:id entity) entity))
                               {}
                               entities-list))]
     (with-derived-state db entities-map))))
