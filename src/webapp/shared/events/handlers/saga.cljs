(ns webapp.shared.events.handlers.saga
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(defn entity-label->keys [entity-label]
  (if (= "character" (str entity-label))
    {:editing-key [:view-state :roster :editing-id]
     :name-inputs-key [:view-state :roster :name-inputs]
     :description-key [:view-state :roster :new-description]
     :panel-open-key [:view-state :roster :new-panel-open?]}
    {:editing-key [:view-state :saga :editing-id]
     :name-inputs-key [:view-state :saga :name-inputs]
     :description-key [:view-state :saga :new-description]
     :panel-open-key [:view-state :saga :new-panel-open?]}))

(rf/reg-event-db
:new-chapter-description-changed
(fn [db [_ value]]
   (assoc-in db [:view-state :saga :new-description] value)))

(rf/reg-event-db
:new-character-description-changed
(fn [db [_ value]]
   (assoc-in db [:view-state :roster :new-description] value)))

(rf/reg-event-db
 :start-entity-name-edit
 (fn [db [_ entity-label entity-id current-name]]
   (let [{:keys [editing-key name-inputs-key]} (entity-label->keys entity-label)]
     (-> db
         (assoc-in editing-key entity-id)
         (assoc-in (conj name-inputs-key entity-id) (or current-name ""))))))

(rf/reg-event-db
 :entity-name-input-changed
 (fn [db [_ entity-label entity-id value]]
   (let [{:keys [name-inputs-key]} (entity-label->keys entity-label)]
     (assoc-in db (conj name-inputs-key entity-id) value))))

(rf/reg-event-db
 :cancel-entity-name-edit
 (fn [db [_ entity-label]]
   (let [{:keys [editing-key]} (entity-label->keys entity-label)]
     (assoc-in db editing-key nil))))

(rf/reg-event-fx
 :save-entity-name
 (fn [{:keys [db]} [_ entity-label entity-id]]
   (let [{:keys [editing-key name-inputs-key]} (entity-label->keys entity-label)
         value (some-> (get-in db (conj name-inputs-key entity-id)) str str/trim)]
     (if (str/blank? (or value ""))
       {:db db}
       {:db (assoc-in db editing-key nil)
        :dispatch [(if (= "character" (str entity-label))
                     :rename-character
                     :rename-chapter)
                   entity-id
                   value]}))))

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
:add-chapter
(fn [{:keys [db]} _]
   (let [description (get-in db [:view-state :saga :new-description])]
     (if (str/blank? (or description ""))
       {:db (-> db
                (assoc-in [:view-state :saga :new-panel-open?] true)
                (assoc :status "Add a chapter theme first."))}
     {:db db
      :dispatch [:enqueue-add-chapter description]}))))

(rf/reg-event-fx
:add-character
(fn [{:keys [db]} _]
   (let [description (get-in db [:view-state :roster :new-description])]
     (if (str/blank? (or description ""))
       {:db (-> db
                (assoc-in [:view-state :roster :new-panel-open?] true)
                (assoc :status "Add a character description first."))}
     {:db db
      :dispatch [:enqueue-add-character description]}))))
