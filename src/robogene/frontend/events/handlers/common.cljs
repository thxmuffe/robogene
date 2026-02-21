(ns robogene.frontend.events.handlers.common
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [robogene.frontend.db :as db]
            [robogene.frontend.events.effects]
            [robogene.frontend.events.model :as model]
            [robogene.frontend.events.handlers.shared :as shared]))

(rf/reg-event-fx
 :initialize
 (fn [_ _]
   {:db db/default-db
    :realtime-connect true
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
   (let [{:keys [episodes frames]} (model/normalize-state state)
         existing-active-id (:active-frame-id db)
         frame-ids (set (map :frameId frames))
         old-open-map (:open-frame-actions db)
         open-frame-actions (into {}
                                 (for [[frame-id open?] old-open-map
                                       :when (contains? frame-ids frame-id)]
                                   [frame-id open?]))
         active-frame-id (cond
                           (and (some? existing-active-id) (contains? frame-ids existing-active-id))
                           existing-active-id
                           (= existing-active-id shared/new-episode-frame-id)
                           existing-active-id
                           (seq frames)
                           (:frameId (first frames))
                           :else nil)]
     {:db (-> db
              (assoc :latest-state state
                     :status (model/status-line state episodes frames)
                     :last-rendered-revision (:revision state)
                     :episodes episodes
                     :gallery-items frames
                     :open-frame-actions open-frame-actions
                     :active-frame-id active-frame-id)
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

(rf/reg-event-fx
 :set-active-frame
 (fn [{:keys [db]} [_ frame-id]]
   (if (= frame-id (:active-frame-id db))
     {:db db}
     {:db (assoc db :active-frame-id frame-id)
      :scroll-frame-into-view frame-id})))

(rf/reg-event-db
 :set-frame-actions-open
 (fn [db [_ frame-id open?]]
   (assoc-in db [:open-frame-actions frame-id] (true? open?))))

(rf/reg-event-db
 :state-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Load failed: " msg))))

(rf/reg-event-fx
 :force-refresh
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [:fetch-state]}))
