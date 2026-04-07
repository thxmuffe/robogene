(ns webapp.shared.events.handlers.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(defn- from-page->hash [from-page saga-id roster-id]
  (case from-page
    :roster (model/roster-hash roster-id saga-id)
    :saga (model/saga-hash saga-id)
    nil))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ chapter-id frame-id from-page]]
   (let [route (:route db)
         entities (:entities db)
         frame-entity (get entities frame-id)
         payload (:payload frame-entity)
         owner-id (or chapter-id
                      (model/frame-owner-id frame-entity))
         from-page* (or from-page (:from-page route))
         owner-type (model/frame-owner-type frame-entity
                                            (when (= :roster from-page*)
                                              "character"))
         roster-id (or (get-in route [:roster-id])
                       (when (= owner-type "character")
                         (model/chapter-parent-id entities owner-id))
                       (:rosterId payload))
         saga-id (or (get-in route [:saga-id])
                     (when (= owner-type "saga")
                       (model/chapter-parent-id entities owner-id))
                     (when roster-id
                       (model/chapter-parent-id entities roster-id)))]
     {:db db
      :set-hash (model/frame-hash frame-id (true? (:fullscreen? route)) from-page* saga-id roster-id)})))

(rf/reg-event-fx
 :navigate-index
 (fn [{:keys [db]} _]
   {:db db
    :set-hash (model/index-hash)}))

(rf/reg-event-fx
 :navigate-entity-page
 (fn [{:keys [db]} [_ entity-id]]
   (let [entity (get (:entities db) (str entity-id))]
     {:db db
      :set-hash (model/route-hash-for-entity entity)})))

(rf/reg-event-fx
 :navigate-create
 (fn [{:keys [db]} _]
   {:db db
    :set-hash (model/create-hash)}))

(rf/reg-event-fx
 :navigate-from-page
 (fn [{:keys [db]} _]
   (if-let [target (from-page->hash (get-in db [:route :from-page])
                                    (get-in db [:route :saga-id])
                                    (get-in db [:route :roster-id]))]
     {:db db
      :set-hash target}
     {:db db})))

(rf/reg-event-fx
 :navigate-saga-page
 (fn [{:keys [db]} [_ saga-id]]
   {:db db
    :set-hash (model/saga-hash (or saga-id (get-in db [:route :saga-id])))}))

(rf/reg-event-fx
 :navigate-chapter-page
 (fn [{:keys [db]} [_ chapter-id]]
   {:db db
    :set-hash (model/chapter-hash chapter-id
                                  (model/chapter-parent-id (:entities db) chapter-id))}))

(rf/reg-event-fx
 :navigate-roster-page
 (fn [{:keys [db]} [_ roster-id saga-id]]
   {:db db
    :set-hash (model/roster-hash (or roster-id
                                     (get-in db [:route :roster-id])
                                     (some-> (model/entities-by-role (:entities db) :roster) first :id))
                                 (or saga-id (get-in db [:route :saga-id])))}))

(rf/reg-event-fx
 :navigate-relative-frame
 (fn [{:keys [db]} [_ delta]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       (let [frame-entity (get-in db [:entities (:frame-id route)])
             chapter-id (model/frame-owner-id frame-entity)
             owner-type (model/frame-owner-type frame-entity
                                                (when (= :roster (:from-page route))
                                                  "character"))
             ordered (model/frames-for-owner (:entities db) owner-type chapter-id)
             active-frame-id (:frame-id route)
             target-frame (model/relative-frame-by-id ordered active-frame-id delta)]
       (if target-frame
           {:db db
            :dispatch [:navigate-frame chapter-id (:frameId target-frame) (:from-page route)]}
           {:db db}))
       {:db db}))))

(rf/reg-event-fx
 :set-frame-fullscreen
 (fn [{:keys [db]} [_ fullscreen?]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       {:db (assoc db :active-frame-id (:frame-id route))
        :set-hash (model/frame-hash (:frame-id route)
                                    (true? fullscreen?)
                                    (:from-page route)
                                    (:saga-id route)
                                    (:roster-id route))}
       {:db db}))))

(rf/reg-event-fx
 :toggle-frame-fullscreen
 (fn [{:keys [db]} _]
   (let [route (:route db)]
     (if (= :frame (:view route))
       {:db db
        :dispatch [:set-frame-fullscreen (not (true? (:fullscreen? route)))]}
       {:db db}))))

(rf/reg-event-fx
 :toggle-fullscreen-shortcut
 (fn [{:keys [db]} _]
   (let [active-id (:active-frame-id db)
         active-frame (some-> (get (:entities db) active-id) model/frame-row)
         fullscreen? (true? (get-in db [:route :fullscreen?]))]
     (if active-frame
       {:db db
        :set-hash (model/frame-hash (:frameId active-frame)
                                    (not fullscreen?)
                                    (get-in db [:route :from-page])
                                    (get-in db [:route :saga-id])
                                    (get-in db [:route :roster-id]))}
       {:db db}))))

(rf/reg-event-fx
 :escape-pressed
 (fn [{:keys [db]} _]
   (let [route (:route db)
         open-frame-actions (:open-frame-actions db)
         open-frame-id (some (fn [[frame-id is-open?]]
                               (when (true? is-open?) frame-id))
                             open-frame-actions)]
     (cond
       (some? open-frame-id)
       {:db db
        :dispatch [:set-frame-actions-open open-frame-id false]}

       (and (= :frame (:view route)) (true? (:fullscreen? route)))
       {:db db :dispatch [:set-frame-fullscreen false]}
       :else
       {:db db :dispatch [:navigate-from-page]}))))
