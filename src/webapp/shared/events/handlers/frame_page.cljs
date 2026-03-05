(ns webapp.shared.events.handlers.frame-page
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(defn- from-page->hash [from-page]
  (case from-page
    :characters "#/characters"
    :saga "#/saga"
    nil))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ chapter-id frame-id from-page]]
   (let [route (:route db)
         chapter (or chapter-id (:chapter route) (get-in db [:latest-state :chapterId]) "local")
         from-page* (or from-page (:from-page route))]
     {:db db
      :set-hash (model/frame-hash chapter frame-id (true? (:fullscreen? route)) from-page*)})))

(rf/reg-event-fx
 :navigate-index
 (fn [{:keys [db]} _]
   {:db db
    :set-hash "#/saga"}))

(rf/reg-event-fx
 :navigate-from-page
 (fn [{:keys [db]} _]
   (if-let [target (from-page->hash (get-in db [:route :from-page]))]
     {:db db
      :set-hash target}
     {:db db})))

(rf/reg-event-fx
 :navigate-saga-page
 (fn [{:keys [db]} _]
   {:db db
    :set-hash "#/saga"}))

(rf/reg-event-fx
 :navigate-characters-page
 (fn [{:keys [db]} _]
   {:db db
    :set-hash "#/characters"}))

(rf/reg-event-fx
 :navigate-relative-frame
 (fn [{:keys [db]} [_ delta]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       (let [chapter-id (:chapter route)
             ordered (model/frames-for-chapter (:gallery-items db) chapter-id)
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
       {:db db
        :set-hash (model/frame-hash (:chapter route) (:frame-id route) (true? fullscreen?) (:from-page route))}
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
         active-frame (some (fn [frame]
                              (when (= (:frameId frame) active-id)
                                frame))
                            (or (:gallery-items db) []))
         fullscreen? (true? (get-in db [:route :fullscreen?]))]
     (if active-frame
       {:db db
        :set-hash (model/frame-hash (:chapterId active-frame)
                                    (:frameId active-frame)
                                    (not fullscreen?)
                                    (get-in db [:route :from-page]))}
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
