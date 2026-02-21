(ns robogene.frontend.events.handlers
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [robogene.frontend.db :as db]
            [robogene.frontend.events.effects]
            [robogene.frontend.events.model :as model]))

(rf/reg-event-fx
 :initialize
 (fn [_ _]
   {:db db/default-db
    :dispatch-n [[:hash-changed (.-hash js/location)]
                 [:fetch-state]]}))

(rf/reg-event-db
 :hash-changed
 (fn [db [_ hash]]
   (assoc db :route (model/parse-hash-route hash))))

(rf/reg-event-fx
 :fetch-state
 (fn [{:keys [db]} _]
   {:db db
    :fetch-state true}))

(rf/reg-event-fx
 :state-loaded
 (fn [{:keys [db]} [_ state]]
   (let [{:keys [frames]} (model/normalize-state state)]
     {:db (-> db
              (assoc :latest-state state
                     :status (model/status-line state frames)
                     :last-rendered-revision (:revision state)
                     :gallery-items frames)
              (assoc :frame-inputs
                     (reduce (fn [acc frame]
                               (let [frame-id (:frameId frame)
                                     existing-val (get-in db [:frame-inputs frame-id])
                                     description (str/trim (or (:description frame) ""))
                                     frame-number (model/frame-number-of frame)
                                     backend-val (or (when (and (seq description)
                                                                (not (model/generic-frame-text? description frame-number)))
                                                       description)
                                                     "")]
                                 (assoc acc frame-id
                                        (if (str/blank? (or existing-val ""))
                                          backend-val
                                          existing-val))))
                             {}
                             frames)))})))

(rf/reg-event-db
 :state-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Load failed: " msg))))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-inputs frame-id] value)))

(rf/reg-event-fx
 :generate-frame
 (fn [{:keys [db]} [_ frame-id]]
   {:db (assoc db :status "Queueing frame...")
    :post-generate-frame {:frame-id frame-id
                          :direction (get-in db [:frame-inputs frame-id] "")}}))

(rf/reg-event-fx
 :generate-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db db
    :dispatch [:fetch-state]
    :dispatch-after [{:ms 1000 :event [:fetch-state]}
                     {:ms 2500 :event [:fetch-state]}
                     {:ms 5000 :event [:fetch-state]}]}))

(rf/reg-event-db
 :generate-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Generation failed: " msg))))

(rf/reg-event-fx
 :force-refresh
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [:fetch-state]}))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ frame-number]]
   (let [episode (or (get-in db [:latest-state :storyId]) "local")]
     {:db db
      :set-hash (model/frame-hash episode frame-number)})))

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
       (let [ordered (model/ordered-frames (:gallery-items db))
             current-frame (:frame-number route)
             idx (model/frame-index-by-number ordered current-frame)
             target-idx (when (number? idx) (+ idx delta))]
         (if (and (number? target-idx)
                  (<= 0 target-idx)
                  (< target-idx (count ordered)))
           {:db db
            :dispatch [:navigate-frame (model/frame-number-of (nth ordered target-idx))]}
           {:db db}))
       {:db db}))))
