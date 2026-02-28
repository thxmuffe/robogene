(ns webapp.shared.events.handlers.chapters
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(rf/reg-event-db
 :new-chapter-description-changed
 (fn [db [_ value]]
   (assoc db :new-chapter-description value)))

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
     {:db (assoc db :status "Creating chapter...")
      :post-add-chapter {:description (:new-chapter-description db)}})))

(rf/reg-event-fx
 :add-chapter-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db
               :new-chapter-description ""
               :new-chapter-panel-open? false
               :show-chapter-celebration? true
               :status "Chapter created.")
    :start-chapter-celebration true}))

(rf/reg-event-db
 :add-chapter-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Request failed: " msg))))
