(ns webapp.shared.events.handlers.saga
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(defn inferred-saga-roster-id [db saga-id]
  (let [roster-ids (->> (or (:saga db) [])
                        (filter (fn [chapter]
                                  (= (:sagaId chapter) saga-id)))
                        (mapcat (fn [chapter]
                                  (or (:rosterIds chapter)
                                      (when-let [roster-id (:rosterId chapter)]
                                        [roster-id])
                                      [])))
                        distinct
                        vec)]
    (when (= 1 (count roster-ids))
      (first roster-ids))))

(defn entity-label->keys [entity-label]
  (case (str entity-label)
    "saga"
    {:editing-key [:view-state :index :editing-id]
     :name-inputs-key [:view-state :index :name-inputs]
     :description-inputs-key [:view-state :index :description-inputs]
     :name-key [:view-state :index :new-name]
     :description-key [:view-state :index :new-description]
     :panel-open-key [:view-state :index :new-panel-open?]}

    "character"
    {:editing-key [:view-state :roster :editing-id]
     :name-inputs-key [:view-state :roster :name-inputs]
     :description-inputs-key [:view-state :roster :description-inputs]
     :name-key [:view-state :roster :new-name]
     :description-key [:view-state :roster :new-description]
     :panel-open-key [:view-state :roster :new-panel-open?]}

    {:editing-key [:view-state :saga :editing-id]
     :name-inputs-key [:view-state :saga :name-inputs]
     :description-inputs-key [:view-state :saga :description-inputs]
     :name-key [:view-state :saga :new-name]
     :description-key [:view-state :saga :new-description]
     :panel-open-key [:view-state :saga :new-panel-open?]}))

(rf/reg-event-db
 :new-saga-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :index :new-name] value)))

(rf/reg-event-db
 :new-chapter-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :saga :new-name] value)))

(rf/reg-event-db
 :new-character-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :roster :new-name] value)))

(rf/reg-event-db
 :new-saga-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :index :new-description] value)))

(rf/reg-event-db
 :new-chapter-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :saga :new-description] value)))

(rf/reg-event-db
 :new-character-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :roster :new-description] value)))

(rf/reg-event-db
 :start-entity-edit
 (fn [db [_ entity-label entity-id current-name current-description]]
   (let [{:keys [editing-key name-inputs-key description-inputs-key]} (entity-label->keys entity-label)]
     (-> db
         (assoc-in editing-key entity-id)
         (assoc-in (conj name-inputs-key entity-id) (or current-name ""))
         (assoc-in (conj description-inputs-key entity-id) (or current-description ""))))))

(rf/reg-event-db
 :entity-name-input-changed
 (fn [db [_ entity-label entity-id value]]
   (let [{:keys [name-inputs-key]} (entity-label->keys entity-label)]
     (assoc-in db (conj name-inputs-key entity-id) value))))

(rf/reg-event-db
 :entity-description-input-changed
 (fn [db [_ entity-label entity-id value]]
   (let [{:keys [description-inputs-key]} (entity-label->keys entity-label)]
     (assoc-in db (conj description-inputs-key entity-id) value))))

(rf/reg-event-db
 :cancel-entity-name-edit
 (fn [db [_ entity-label]]
   (let [{:keys [editing-key]} (entity-label->keys entity-label)]
     (assoc-in db editing-key nil))))

(rf/reg-event-fx
 :save-entity
 (fn [{:keys [db]} [_ entity-label entity-id provided-name provided-description]]
   (let [{:keys [editing-key name-inputs-key description-inputs-key]} (entity-label->keys entity-label)
         name-source (if (some? provided-name)
                       provided-name
                       (get-in db (conj name-inputs-key entity-id)))
         description-source (if (some? provided-description)
                              provided-description
                              (get-in db (conj description-inputs-key entity-id)))
         name (some-> name-source str str/trim)
         description (some-> description-source str)]
     (if (str/blank? (or name ""))
       {:db db}
       {:db (assoc-in db editing-key nil)
        :dispatch [(case (str entity-label)
                     "saga" :update-saga
                     "character" :update-character
                     :update-chapter)
                   entity-id
                   name
                   description]}))))

(rf/reg-event-db
 :set-new-saga-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :index :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :set-new-chapter-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :saga :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :set-new-character-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :roster :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :chapter-celebration-ended
 (fn [db _]
   (assoc-in db [:view-state :saga :show-celebration?] false)))

(rf/reg-event-fx
 :add-saga
 (fn [{:keys [db]} _]
   (let [name (some-> (get-in db [:view-state :index :new-name]) str str/trim)
         description (get-in db [:view-state :index :new-description])]
     (if (str/blank? (or name ""))
       {:db (-> db
                (assoc-in [:view-state :index :new-panel-open?] true)
                (assoc :status "Add a saga name first."))}
       {:db db
        :dispatch [:enqueue-add-saga name description]}))))

(rf/reg-event-fx
 :add-chapter
 (fn [{:keys [db]} _]
   (let [saga-id (get-in db [:route :saga-id])
         name (some-> (get-in db [:view-state :saga :new-name]) str str/trim)
         description (get-in db [:view-state :saga :new-description])
         inferred-roster-id (inferred-saga-roster-id db saga-id)]
     (cond
       (str/blank? (or saga-id ""))
       {:db (assoc db :status "Open a saga before adding chapters.")}

       (str/blank? (or name ""))
       {:db (-> db
                (assoc-in [:view-state :saga :new-panel-open?] true)
                (assoc :status "Add a chapter name first."))}

       :else
       (if (seq (or inferred-roster-id ""))
         {:db db
          :dispatch [:enqueue-add-chapter saga-id inferred-roster-id name description]}
         {:db db
          :dispatch [:open-roster-link-dialog {:mode :add-chapter
                                               :saga-id saga-id
                                               :name name
                                               :description description}]})))))

(rf/reg-event-fx
 :add-character
 (fn [{:keys [db]} _]
   (let [roster-id (or (get-in db [:route :roster-id])
                       (some-> (:rosters db) first :rosterId))
         name (some-> (get-in db [:view-state :roster :new-name]) str str/trim)
         description (get-in db [:view-state :roster :new-description])]
     (cond
       (str/blank? (or roster-id ""))
       {:db (assoc db :status "Open a roster before adding characters.")}

       (str/blank? (or name ""))
       {:db (-> db
                (assoc-in [:view-state :roster :new-panel-open?] true)
                (assoc :status "Add a character name first."))}

       :else
       {:db db
        :dispatch [:enqueue-add-character roster-id name description]}))))

(rf/reg-event-db
 :collection-search-changed
 (fn [db [_ view-id value]]
   (-> db
       (assoc-in [:view-state view-id :search] (or value ""))
       (assoc-in [:view-state view-id :page] 1))))

(rf/reg-event-db
 :collection-page-selected
 (fn [db [_ view-id page]]
  (assoc-in db [:view-state view-id :page] (max 1 (or page 1)))))

(rf/reg-event-db
 :open-roster-link-dialog
 (fn [db [_ target]]
   (-> db
       (assoc-in [:view-state :roster-link :open?] true)
       (assoc-in [:view-state :roster-link :search] "")
       (assoc-in [:view-state :roster-link :target] target))))

(rf/reg-event-db
 :close-roster-link-dialog
 (fn [db _]
   (-> db
       (assoc-in [:view-state :roster-link :open?] false)
       (assoc-in [:view-state :roster-link :search] "")
       (assoc-in [:view-state :roster-link :target] nil))))

(rf/reg-event-db
 :roster-link-search-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :roster-link :search] (or value ""))))

(rf/reg-event-fx
 :edit-chapter-roster
 (fn [{:keys [db]} [_ chapter-id]]
   {:db db
    :dispatch [:open-roster-link-dialog {:mode :edit-chapter-roster
                                         :chapter-id chapter-id}]}))

(rf/reg-event-fx
 :add-linked-chapter-roster
 (fn [{:keys [db]} [_ chapter-id]]
  {:db db
   :dispatch [:open-roster-link-dialog {:mode :add-linked-roster
                                         :chapter-id chapter-id}]}))

(rf/reg-event-fx
 :create-roster-link
 (fn [{:keys [db]} _]
   (let [target (get-in db [:view-state :roster-link :target])
         chapter (some (fn [row]
                         (when (= (:chapterId row) (:chapter-id target))
                           row))
                       (or (:saga db) []))
         saga-id (or (:saga-id target) (:sagaId chapter))
         after-create (case (:mode target)
                        :add-chapter {:mode :add-chapter
                                      :saga-id saga-id
                                      :chapter-name (:name target)
                                      :chapter-description (:description target)}
                        :edit-chapter-roster {:mode :edit-chapter-roster
                                              :chapter-id (:chapter-id target)
                                              :saga-id saga-id}
                        :add-linked-roster {:mode :add-linked-roster
                                            :chapter-id (:chapter-id target)
                                            :saga-id saga-id}
                        {:mode nil
                         :saga-id saga-id})]
     {:db (-> db
              (assoc-in [:view-state :roster-link :open?] false)
              (assoc-in [:view-state :roster-link :search] "")
              (assoc-in [:view-state :roster-link :target] nil))
      :dispatch [:enqueue-add-roster after-create]})))

(rf/reg-event-fx
 :select-roster-link
 (fn [{:keys [db]} [_ roster-id]]
   (let [target (get-in db [:view-state :roster-link :target])
         next-db (-> db
                     (assoc-in [:view-state :roster-link :open?] false)
                     (assoc-in [:view-state :roster-link :search] "")
                     (assoc-in [:view-state :roster-link :target] nil))]
     (case (:mode target)
       :add-chapter
       {:db next-db
        :dispatch [:enqueue-add-chapter (:saga-id target)
                   roster-id
                   (:name target)
                   (:description target)]}

       :edit-chapter-roster
       {:db next-db
       :dispatch [:update-chapter-roster (:chapter-id target) roster-id]}

       :add-linked-roster
       {:db next-db
        :dispatch [:add-chapter-roster (:chapter-id target) roster-id]}

       {:db next-db}))))
