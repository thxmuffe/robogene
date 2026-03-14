(ns webapp.shared.events.handlers.saga
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

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
         description (get-in db [:view-state :saga :new-description])]
     (cond
       (str/blank? (or saga-id ""))
       {:db (assoc db :status "Open a saga before adding chapters.")}

       (str/blank? (or name ""))
       {:db (-> db
                (assoc-in [:view-state :saga :new-panel-open?] true)
                (assoc :status "Add a chapter name first."))}

       :else
       {:db db
        :dispatch [:enqueue-add-chapter saga-id name description]}))))

(rf/reg-event-fx
 :add-character
 (fn [{:keys [db]} _]
   (let [name (some-> (get-in db [:view-state :roster :new-name]) str str/trim)
         description (get-in db [:view-state :roster :new-description])]
     (if (str/blank? (or name ""))
       {:db (-> db
                (assoc-in [:view-state :roster :new-panel-open?] true)
                (assoc :status "Add a character name first."))}
       {:db db
        :dispatch [:enqueue-add-character name description]}))))

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
