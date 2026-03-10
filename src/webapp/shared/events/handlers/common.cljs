(ns webapp.shared.events.handlers.common
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [webapp.shared.db :as db]
            [webapp.shared.events.browser]
            [webapp.shared.controls :as controls]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.events.effects]
            [webapp.shared.events.transport]
            [webapp.shared.events.handlers.frame-page]
            [webapp.shared.events.handlers.saga]
            [webapp.shared.events.handlers.frames]
            [webapp.shared.model :as model]))

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
   (let [previous-frames (:gallery-items db)
         {:keys [saga roster frames]} (model/derived-state state)
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
                           (= existing-active-id controls/new-chapter-frame-id)
                           existing-active-id
                           (seq frames)
                           (:frameId (first frames))
                           :else nil)
         image-ui-by-frame-id (image-ui/sync-image-ui-by-frame-id
                               (:image-ui-by-frame-id db)
                               previous-frames
                               frames)]
     {:db (-> db
              (assoc :latest-state state
                     :status (model/status-line state saga roster frames)
                     :last-rendered-revision (:revision state)
                     :saga-meta (or (:sagaMeta state) (:saga-meta db))
                     :saga saga
                     :roster roster
                     :gallery-items frames
                     :image-ui-by-frame-id image-ui-by-frame-id
                     :open-frame-actions open-frame-actions
                     :active-frame-id active-frame-id)
              (assoc :frame-inputs
                     (reduce (fn [acc frame]
                               (let [frame-id (:frameId frame)
                                     editing? (true? (get open-frame-actions frame-id))
                                     existing-val (get-in db [:frame-inputs frame-id])
                                     description (str/trim (or (:description frame) ""))
                                     services-val (when (and (seq description)
                                                             (not (model/generic-frame-text? description)))
                                                    description)]
                                 (assoc acc frame-id
                                        (if editing?
                                          existing-val
                                          services-val))))
                             {}
                             frames)))})))

(rf/reg-event-fx
 :set-active-frame
 (fn [{:keys [db]} [_ frame-id]]
   (if (= frame-id (:active-frame-id db))
     {:db db}
     {:db (assoc db :active-frame-id frame-id)})))

(rf/reg-event-db
 :set-frame-actions-open
 (fn [db [_ frame-id open?]]
   (assoc-in db [:open-frame-actions frame-id] (true? open?))))

(rf/reg-event-db
 :state-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Service unavailable: " msg))))

(rf/reg-event-fx
 :force-refresh
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [:fetch-state]}))

(defn cancel-open-edit-db-items [db]
  (let [open-frame-ids (->> (or (:open-frame-actions db) {})
                            (keep (fn [[frame-id open?]]
                                    (when (true? open?) frame-id)))
                            vec)
        frame-desc-by-id (into {}
                               (map (fn [frame]
                                      [(:frameId frame) (or (:description frame) "")])
                                    (or (:gallery-items db) [])))]
    (-> db
        (update :cancel-ui-token (fnil inc 0))
        (assoc :open-frame-actions {})
        (assoc-in [:view-state :saga :editing-id] nil)
        (assoc-in [:view-state :roster :editing-id] nil)
        (assoc-in [:view-state :saga :new-panel-open?] false)
        (assoc-in [:view-state :roster :new-panel-open?] false)
        (assoc-in [:view-state :saga :meta-editing?] false)
        (update :frame-inputs
                (fn [inputs]
                  (reduce (fn [acc frame-id]
                            (assoc acc frame-id (get frame-desc-by-id frame-id "")))
                          (or inputs {})
                          open-frame-ids))))))

(rf/reg-event-fx
 :cancel-open-edit-db-items
 (fn [{:keys [db]} _]
   {:db (cancel-open-edit-db-items db)}))
