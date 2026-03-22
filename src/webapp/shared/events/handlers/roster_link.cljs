(ns webapp.shared.events.handlers.roster-link
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

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
         chapter-id (:chapter-id target)
         chapter-entity (some->> chapter-id
                                 (model/entity-by-id (:entities db)))
         saga-id (or (:saga-id target)
                     (model/entity-parent-id chapter-entity)
                     (some->> chapter-id (model/chapter-parent-id (:entities db))))
         after-create (case (:mode target)
                        :add-chapter {:mode :add-chapter
                                      :saga-id saga-id
                                      :name (:name target)
                                      :description (:description target)}
                        :edit-chapter-roster {:mode :edit-chapter-roster
                                              :chapter-id chapter-id
                                              :saga-id saga-id}
                        :add-linked-roster {:mode :add-linked-roster
                                            :chapter-id chapter-id
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
         chapter-id (:chapter-id target)
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
        :dispatch [:update-chapter-roster chapter-id roster-id]}

       :add-linked-roster
       {:db next-db
        :dispatch [:add-chapter-roster chapter-id roster-id]}

       {:db next-db}))))
