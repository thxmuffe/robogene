(ns webapp.shared.events.handlers.saga
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(rf/reg-event-db
 :new-chapter-description-changed
 (fn [db [_ value]]
   (assoc db :new-chapter-description value)))

(rf/reg-event-db
 :start-chapter-name-edit
 (fn [db [_ chapter-id current-name]]
   (-> db
       (assoc :editing-chapter-id chapter-id)
       (assoc-in [:chapter-name-inputs chapter-id] (or current-name "")))))

(rf/reg-event-db
 :chapter-name-input-changed
 (fn [db [_ chapter-id value]]
   (assoc-in db [:chapter-name-inputs chapter-id] value)))

(rf/reg-event-db
 :cancel-chapter-name-edit
 (fn [db _]
   (assoc db :editing-chapter-id nil)))

(rf/reg-event-fx
 :save-chapter-name
 (fn [{:keys [db]} [_ chapter-id]]
   (let [value (some-> (get-in db [:chapter-name-inputs chapter-id]) str str/trim)]
     (if (str/blank? (or value ""))
       {:db db}
       {:db (assoc db :editing-chapter-id nil)
        :dispatch [:rename-chapter chapter-id value]}))))

(rf/reg-event-db
 :set-new-chapter-panel-open
 (fn [db [_ open?]]
   (assoc db :new-chapter-panel-open? (true? open?))))

(rf/reg-event-db
 :chapter-celebration-ended
 (fn [db _]
   (assoc db :show-chapter-celebration? false)))

(rf/reg-event-fx
 :add-chapter
 (fn [{:keys [db]} _]
   (if (str/blank? (or (:new-chapter-description db) ""))
     {:db (assoc db
                 :new-chapter-panel-open? true
                 :status "Add a chapter theme first.")}
     {:db db
      :dispatch [:enqueue-add-chapter (:new-chapter-description db)]})))
