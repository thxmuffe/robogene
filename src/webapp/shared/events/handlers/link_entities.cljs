(ns webapp.shared.events.handlers.link-entities
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def default-role-filters ["roster"])

(rf/reg-event-db
 :open-link-entities-dialog
 (fn [db [_ target]]
   (-> db
       (assoc-in [:view-state :link-entities :open?] true)
       (assoc-in [:view-state :link-entities :search] "")
       (assoc-in [:view-state :link-entities :sort] "title-asc")
       (assoc-in [:view-state :link-entities :role-filters] default-role-filters)
       (assoc-in [:view-state :link-entities :target] target))))

(rf/reg-event-db
 :close-link-entities-dialog
 (fn [db _]
   (-> db
       (assoc-in [:view-state :link-entities :open?] false)
       (assoc-in [:view-state :link-entities :search] "")
       (assoc-in [:view-state :link-entities :sort] "title-asc")
       (assoc-in [:view-state :link-entities :role-filters] default-role-filters)
       (assoc-in [:view-state :link-entities :target] nil))))

(rf/reg-event-db
 :link-entities-search-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :link-entities :search] (or value ""))))

(rf/reg-event-db
 :link-entities-sort-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :link-entities :sort]
             (or (some-> value str str/trim not-empty) "title-asc"))))

(rf/reg-event-db
 :link-entities-role-filters-changed
 (fn [db [_ values]]
   (assoc-in db [:view-state :link-entities :role-filters]
             (vec (or values default-role-filters)))))

(rf/reg-event-fx
 :select-link-entity
 (fn [{:keys [db]} [_ child-id]]
   (let [target (get-in db [:view-state :link-entities :target])
         parent-id (:parent-id target)
         next-db (-> db
                     (assoc-in [:view-state :link-entities :open?] false)
                     (assoc-in [:view-state :link-entities :search] "")
                     (assoc-in [:view-state :link-entities :sort] "title-asc")
                     (assoc-in [:view-state :link-entities :role-filters] default-role-filters)
                     (assoc-in [:view-state :link-entities :target] nil))]
     (if (and (seq (or parent-id "")) (seq (or child-id "")))
       {:db next-db
        :dispatch [:update-entity child-id {:payload {:parentId parent-id}}]}
       {:db next-db}))))
