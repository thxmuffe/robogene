(ns webapp.shared.events.handlers.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ chapter-id frame-id]]
   (let [chapter (or chapter-id (get-in db [:route :chapter]) (get-in db [:latest-state :chapterId]) "local")]
     {:db db
      :set-hash (model/frame-hash chapter frame-id (true? (get-in db [:route :fullscreen?])))})))

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
 :set-frame-fullscreen
 (fn [{:keys [db]} [_ fullscreen?]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       {:db db
        :set-hash (model/frame-hash (:chapter route) (:frame-id route) (true? fullscreen?))}
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
 :escape-pressed
 (fn [{:keys [db]} _]
   (let [route (:route db)]
     (cond
       (and (= :frame (:view route)) (true? (:fullscreen? route)))
       {:db db :dispatch [:set-frame-fullscreen false]}
       :else
       {:db db :dispatch [:navigate-index]}))))
