(ns webapp.shared.events.handlers.common
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [webapp.shared.db :as db]
            [webapp.shared.events.effects]
            [webapp.shared.model :as model]
            [webapp.shared.events.handlers.shared :as shared]))

(defn push-wait-lights-event [db kind message]
  (let [entry {:id (str (.now js/Date) "-" (rand-int 1000000))
               :ts (.toLocaleTimeString (js/Date.) "en-US" #js {:hour12 false})
               :kind (or kind :info)
               :message (or message "")}
        current (vec (or (:wait-lights-events db) []))
        next-events (->> (conj current entry)
                         (take-last 5)
                         vec)]
    (assoc db :wait-lights-events next-events)))

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
 :api-request-start
 (fn [{:keys [db]} [_ request-label]]
   (let [pending (or (:pending-api-requests db) 0)
         next-pending (inc pending)
         next-db (assoc db
                        :pending-api-requests next-pending
                        :wait-lights-visible? true)
         msg (str "Outgoing: " (or request-label "request started"))]
     {:db (push-wait-lights-event next-db :outgoing msg)})))

(rf/reg-event-fx
 :api-request-finish
 (fn [{:keys [db]} [_ request-label]]
   (let [pending (or (:pending-api-requests db) 0)
         next-pending (max 0 (dec pending))
         next-db (assoc db :pending-api-requests next-pending)
         with-log (push-wait-lights-event next-db :incoming (str "Complete: " (or request-label "request")))]
     (if (zero? next-pending)
       {:db (assoc with-log :wait-lights-visible? false)}
       {:db with-log}))))

(rf/reg-event-db
 :wait-lights-log
 (fn [db [_ kind message]]
   (push-wait-lights-event db kind message)))

(rf/reg-event-fx
 :state-loaded
 (fn [{:keys [db]} [_ state]]
   (let [{:keys [chapters frames]} (model/normalize-state state)
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
                           (= existing-active-id shared/new-chapter-frame-id)
                           existing-active-id
                           (seq frames)
                           (:frameId (first frames))
                           :else nil)]
     {:db (-> db
              (assoc :latest-state state
                     :status (model/status-line state chapters frames)
                     :last-rendered-revision (:revision state)
                     :chapters chapters
                     :gallery-items frames
                     :open-frame-actions open-frame-actions
                     :active-frame-id active-frame-id)
              (assoc :frame-inputs
                     (reduce (fn [acc frame]
                               (let [frame-id (:frameId frame)
                                     existing-val (get-in db [:frame-inputs frame-id])
                                     description (str/trim (or (:description frame) ""))
                                     services-val (or (when (and (seq description)
                                                                (not (model/generic-frame-text? description)))
                                                       description)
                                                     "")]
                                 (assoc acc frame-id
                                        (if (str/blank? (or existing-val ""))
                                          services-val
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
