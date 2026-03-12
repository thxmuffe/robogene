(ns webapp.shared.events.handlers.gallery
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(defn- active-gallery-chapter-id [db]
  (some (fn [frame]
          (when (= (:frameId frame) (:active-frame-id db))
            (:chapterId frame)))
        (or (:gallery-items db) [])))

(defn- first-frame-id-for-chapter [db chapter-id]
  (some->> (model/frames-for-chapter (:gallery-items db) chapter-id)
           first
           :frameId))

(rf/reg-event-fx
 :toggle-gallery-chapter-collapsed
 (fn [{:keys [db]} [_ chapter-id]]
   (let [collapsed-ids (or (get-in db [:view-state :gallery :collapsed-chapter-ids]) #{})
         collapsing? (not (contains? collapsed-ids chapter-id))
         next-collapsed-ids (if collapsing?
                              (conj collapsed-ids chapter-id)
                              (disj collapsed-ids chapter-id))
         active-chapter-id (active-gallery-chapter-id db)
         next-active-frame-id (cond
                                (and collapsing? (= active-chapter-id chapter-id))
                                nil

                                (not collapsing?)
                                (first-frame-id-for-chapter db chapter-id)

                                :else
                                (:active-frame-id db))]
     {:db (-> db
              (assoc-in [:view-state :gallery :collapsed-chapter-ids] next-collapsed-ids)
              (assoc :active-frame-id next-active-frame-id))})))

(rf/reg-event-fx
 :collapse-current-gallery-chapter
 (fn [{:keys [db]} _]
   (if-let [chapter-id (active-gallery-chapter-id db)]
     (let [collapsed-ids (or (get-in db [:view-state :gallery :collapsed-chapter-ids]) #{})]
       (if (contains? collapsed-ids chapter-id)
         {:db db}
         {:dispatch [:toggle-gallery-chapter-collapsed chapter-id]}))
     {:db db})))
