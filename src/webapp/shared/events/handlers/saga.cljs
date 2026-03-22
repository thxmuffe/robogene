(ns webapp.shared.events.handlers.saga
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.events.handlers.entity-forms]
            [webapp.shared.events.handlers.roster-link]
            [webapp.shared.model :as model]))

(defn inferred-saga-roster-id [db saga-id]
  (let [roster-ids (->> (model/children-by-role (:entities db) saga-id :chapter)
                        (mapcat (fn [chapter]
                                  (or (seq (get-in chapter [:payload :rosterIds]))
                                      (when-let [roster-id (get-in chapter [:payload :rosterId])]
                                        [roster-id])
                                      [])))
                        (remove str/blank?)
                        distinct
                        vec)]
    (when (= 1 (count roster-ids))
      (first roster-ids))))

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

       (seq (or inferred-roster-id ""))
       {:db db
        :dispatch [:enqueue-add-chapter saga-id inferred-roster-id name description]}

       :else
       {:db db
        :dispatch [:open-roster-link-dialog {:mode :add-chapter
                                             :saga-id saga-id
                                             :name name
                                             :description description}]}))))

(rf/reg-event-fx
 :add-character
 (fn [{:keys [db]} _]
   (let [roster-id (or (get-in db [:route :roster-id])
                       (some-> (model/entities-by-role (:entities db) :roster) first :id))
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
