(ns webapp.shared.events.handlers.navigation
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.shared.events.handlers.shared :as shared]))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ chapter-id frame-id]]
   (let [chapter (or chapter-id (get-in db [:route :chapter]) (get-in db [:latest-state :chapterId]) "local")]
     {:db db
      :set-hash (model/frame-hash chapter frame-id)})))

(rf/reg-event-fx
 :navigate-index
 (fn [{:keys [db]} _]
   {:db db
    :set-hash ""}))

(rf/reg-event-fx
 :navigate-relative-frame
 (fn [{:keys [db]} [_ delta]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       (let [chapter-id (:chapter route)
             ordered (model/frames-for-chapter (:gallery-items db) chapter-id)
             current-frame-id (:frame-id route)
             target-frame (model/relative-frame-by-id ordered current-frame-id delta)]
         (if target-frame
           {:db db
            :dispatch [:navigate-frame chapter-id (:frameId target-frame)]}
           {:db db}))
       {:db db}))))

(rf/reg-event-fx
 :move-active-frame-horizontal
 (fn [{:keys [db]} [_ delta]]
   {:db db
    :move-active-frame-horizontal-dom {:frame-id (:active-frame-id db)
                                       :delta delta}}))

(rf/reg-event-fx
 :move-active-frame-vertical
 (fn [{:keys [db]} [_ direction]]
   (if (some? (:active-frame-id db))
     {:db db
      :move-active-frame-vertical {:frame-id (:active-frame-id db)
                                   :direction direction}}
     {:db db})))

(rf/reg-event-fx
 :keyboard-arrow
 (fn [{:keys [db]} [_ key]]
   (let [view (get-in db [:route :view])]
     (if (= :frame view)
       (case key
         "ArrowLeft" {:db db :dispatch [:navigate-relative-frame -1]}
         "ArrowRight" {:db db :dispatch [:navigate-relative-frame 1]}
         {:db db})
       (case key
         "ArrowLeft" {:db db :dispatch [:move-active-frame-horizontal -1]}
         "ArrowRight" {:db db :dispatch [:move-active-frame-horizontal 1]}
         "ArrowUp" {:db db :dispatch [:move-active-frame-vertical :up]}
         "ArrowDown" {:db db :dispatch [:move-active-frame-vertical :down]}
         {:db db})))))

(rf/reg-event-fx
 :open-active-frame
 (fn [{:keys [db]} _]
   (let [route (:route db)
         active-id (:active-frame-id db)
         active-frame (shared/frame-by-id (:gallery-items db) active-id)]
     (cond
       (= :frame (:view route))
       {:db db}

       (= active-id shared/new-chapter-frame-id)
       {:db db
        :dispatch [:set-new-chapter-panel-open true]}

       (some? active-frame)
       {:db db
        :dispatch [:navigate-frame (:chapterId active-frame) (:frameId active-frame)]}

       :else
       {:db db}))))
