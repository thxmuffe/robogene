(ns webapp.shared.events.handlers.common
  (:require [re-frame.core :as rf]
            [clojure.string :as str]
            [webapp.shared.db :as db]
            [webapp.shared.events.browser]
            [webapp.shared.controls :as controls]
            [webapp.shared.events.image-ui :as image-ui]
            [webapp.shared.events.transport]
            [webapp.shared.store :as store]
            [webapp.shared.visual-effects]
            [webapp.shared.events.handlers.frame-page]
            [webapp.shared.events.handlers.create]
            [webapp.shared.events.handlers.link-entities]
            [webapp.shared.events.handlers.saga]
            [webapp.shared.events.handlers.visual-effects]
            [webapp.shared.events.handlers.frames]
            [webapp.shared.events.handlers.entity]
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

(defn refresh-derived-status [db]
  (let [latest-state (or (:latest-state db) {})]
    (-> db
        (assoc :status
               (model/status-line latest-state (:entities db))))))

(defn cleanup-deleted-frame-state [db deleted-frame-ids]
  (let [frame-id-set (set deleted-frame-ids)
        remaining-frames (->> (model/gallery-frames (:entities db))
                              (remove (fn [frame]
                                        (contains? frame-id-set (:frameId frame))))
                              vec)
        current-active-id (:active-frame-id db)
        next-active-id (if (contains? frame-id-set current-active-id)
                         (some-> remaining-frames first :frameId)
                         current-active-id)
        dissoc-ids (fn [m]
                     (apply dissoc (or m {}) deleted-frame-ids))]
    (-> db
        (assoc :active-frame-id next-active-id)
        (update :frame-drafts dissoc-ids)
        (update :open-frame-actions dissoc-ids)
        (update :hidden-frame-images dissoc-ids)
        (update :image-ui-by-frame-id dissoc-ids))))

(defn apply-realtime-delta [db payload]
  (let [incoming-entities (->> (concat (when-let [entity (:entity payload)]
                                         [entity])
                                       (or (:entities payload) []))
                               (keep store/normalize-entity)
                               (reduce (fn [acc entity]
                                         (assoc acc (:id entity) entity))
                                       {}))
        deleted-ids (->> (concat (when-let [id (:id payload)]
                                   [id])
                                 (or (:deletedIds payload) []))
                         (keep #(some-> % str not-empty))
                         distinct
                         vec)
        existing-entities (:entities db)
        deleted-frame-ids (->> deleted-ids
                               (keep (fn [id]
                                       (when (= "frame" (model/entity-role (get existing-entities id)))
                                         id)))
                               vec)
        next-entities (reduce dissoc
                              (reduce (fn [acc [entity-id entity]]
                                        (assoc acc entity-id entity))
                                      existing-entities
                                      incoming-entities)
                              deleted-ids)]
    (cond-> (-> db
                (assoc :entities next-entities)
                (assoc :derived-state (store/compute-derived-state next-entities))
                refresh-derived-status)
      (seq deleted-frame-ids)
      (cleanup-deleted-frame-state deleted-frame-ids))))

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
   (let [route (model/parse-hash-route hash)]
     (cond-> (assoc db :route route)
       (= :frame (:view route))
       (assoc :active-frame-id (:frame-id route))))))

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

(rf/reg-event-db
 :set-selected-image-generator
 (fn [db [_ generator]]
   (assoc db :selected-image-generator (some-> generator str not-empty))))

(rf/reg-event-fx
 :state-loaded
 (fn [{:keys [db]} [_ state]]
   (let [incoming-revision (or (:revision state) 0)
         current-revision (or (:last-rendered-revision db) -1)]
    (if (< incoming-revision current-revision)
      {:db db}
       (let [entities (or (:entities state) {})
             available-image-generators (vec (or (:availableImageGenerators state) []))
             default-image-generator (let [candidate (:defaultImageGenerator state)]
                                       (when (some #(= % candidate) available-image-generators)
                                         candidate))
             previous-frames (model/gallery-frames (:entities db))
             frames (model/gallery-frames entities)
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
                                   frames)
            db* (-> db
                    (assoc :latest-state {:processing (:processing state)
                                          :pendingCount (:pendingCount state)}
                            :status (model/status-line state entities)
                            :last-rendered-revision incoming-revision
                            :available-image-generators available-image-generators
                            :default-image-generator default-image-generator
                            :selected-image-generator (let [selected (:selected-image-generator db)]
                                                        (or (when (some #(= % selected) available-image-generators)
                                                              selected)
                                                            default-image-generator))
                            :entities entities
                            :derived-state (store/compute-derived-state entities)
                            :image-ui-by-frame-id image-ui-by-frame-id
                            :hidden-frame-images (into {}
                                                       (for [[frame-id hidden?] (or (:hidden-frame-images db) {})
                                                             :when (and hidden? (contains? frame-ids frame-id))]
                                                         [frame-id true]))
                           :open-frame-actions open-frame-actions
                           :active-frame-id active-frame-id)
                    (update :frame-drafts
                            (fn [drafts]
                              (into {}
                                    (for [[frame-id draft] (or drafts {})
                                          :when (true? (get open-frame-actions frame-id))]
                                      [frame-id draft]))))
                     (store/reapply-pending-commands))]
        {:db (store/refresh-entities-from-flat db* entities)}))))) 

(rf/reg-event-fx
 :realtime-state-changed
 (fn [{:keys [db]} [_ payload]]
   (let [{:keys [processing revision pendingCount]} (or payload {})
         current-revision (or (:last-rendered-revision db) -1)]
     (if (and (some? revision) (<= revision current-revision))
       {:db db}
       (let [next-revision (if (some? revision)
                             (max current-revision revision)
                             current-revision)
             next-db (cond-> db
                       (some? revision)
                       (assoc :last-rendered-revision next-revision)

                       (some? processing)
                       (assoc-in [:latest-state :processing] processing)

                       (some? pendingCount)
                       (assoc-in [:latest-state :pendingCount] pendingCount))
             next-db (apply-realtime-delta next-db payload)]
         {:db next-db})))))

(rf/reg-event-fx
 :set-active-frame
 (fn [{:keys [db]} [_ frame-id]]
   (if (= frame-id (:active-frame-id db))
     {:db db}
     {:db (assoc db :active-frame-id frame-id)})))

(rf/reg-event-db
 :set-frame-actions-open
 (fn [db [_ frame-id open?]]
   (let [open? (true? open?)
         frame-description (or (:description (get-in db [:entities frame-id]))
                               "")]
     (if open?
       (-> db
           (assoc :open-frame-actions {frame-id true})
           (update :frame-drafts #(assoc {} frame-id (or (get (or % {}) frame-id)
                                                         frame-description))))
       (-> db
           (assoc-in [:open-frame-actions frame-id] false)
           (update :frame-drafts dissoc frame-id))))))

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
  (-> db
      (update :cancel-ui-token (fnil inc 0))
      (assoc :open-frame-actions {})
      (assoc-in [:view-state :index :editing-id] nil)
      (assoc-in [:view-state :saga :editing-id] nil)
      (assoc-in [:view-state :link-entities :open?] false)
      (assoc-in [:view-state :link-entities :search] "")
      (assoc-in [:view-state :link-entities :sort] "title-asc")
      (assoc-in [:view-state :link-entities :role-filters] ["roster"])
      (assoc-in [:view-state :link-entities :target] nil)
      (assoc-in [:view-state :roster-link :open?] false)
      (assoc-in [:view-state :roster-link :search] "")
      (assoc-in [:view-state :roster-link :target] nil)
      (assoc-in [:view-state :roster :editing-id] nil)
      (assoc-in [:view-state :index :new-panel-open?] false)
      (assoc-in [:view-state :saga :new-panel-open?] false)
      (assoc-in [:view-state :roster :new-panel-open?] false)
      (assoc :frame-drafts {})))

(rf/reg-event-fx
 :cancel-open-edit-db-items
 (fn [{:keys [db]} _]
   {:db (cancel-open-edit-db-items db)}))
