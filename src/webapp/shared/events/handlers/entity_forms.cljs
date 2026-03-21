(ns webapp.shared.events.handlers.entity-forms
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.model :as model]))

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
 (fn [{:keys [db]} [_ entity-id patch]]
   (let [entity (model/entity-by-id (:entities db) entity-id)
         role (model/entity-role entity)
         {:keys [editing-key]} (entity-label->keys role)
         title (cond
                 (contains? patch :title) (some-> (:title patch) str str/trim)
                 :else (:title entity))
         next-patch (cond-> {}
                      (contains? patch :title) (assoc :title title)
                      (contains? patch :description) (assoc :description (some-> (:description patch) str)))]
     (if (or (nil? entity)
             (str/blank? (or title "")))
       {:db db}
       {:db (assoc-in db editing-key nil)
        :dispatch [:update-entity entity-id next-patch]}))))

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
 :collection-search-changed
 (fn [db [_ view-id value]]
   (-> db
       (assoc-in [:view-state view-id :search] (or value ""))
       (assoc-in [:view-state view-id :page] 1))))

(rf/reg-event-db
 :collection-page-selected
 (fn [db [_ view-id page]]
   (assoc-in db [:view-state view-id :page] (max 1 (or page 1)))))
