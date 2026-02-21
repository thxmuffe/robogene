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
         pending (or (:pendingCount state) 0)
         processing? (true? (:processing state))
         poll-mode (if (or processing? (pos? pending)) :active :idle)]
     {:db (-> db
              (assoc :latest-state state
                     :status (model/status-line state episodes frames)
                     :last-rendered-revision (:revision state)
                     :episodes episodes
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
                             frames)))
      :set-fallback-polling poll-mode})))

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
    :set-fallback-polling :active
    :dispatch [:fetch-state]}))

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
 (fn [{:keys [db]} [_ episode-id frame-number]]
   (let [episode (or episode-id (get-in db [:route :episode]) (get-in db [:latest-state :storyId]) "local")]
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
       (let [episode-id (:episode route)
             frames-in-episode (->> (:episodes db)
                                    (some (fn [episode]
                                            (when (= (:episodeId episode) episode-id)
                                              (:frames episode))))
                                    (or []))
             ordered (model/ordered-frames frames-in-episode)
             current-frame (:frame-number route)
             idx (model/frame-index-by-number ordered current-frame)
             target-idx (when (number? idx) (+ idx delta))]
         (if (and (number? target-idx)
                  (<= 0 target-idx)
                  (< target-idx (count ordered)))
           {:db db
            :dispatch [:navigate-frame episode-id (model/frame-number-of (nth ordered target-idx))]}
           {:db db}))
       {:db db}))))

(rf/reg-event-db
 :new-episode-description-changed
 (fn [db [_ value]]
   (assoc db :new-episode-description value)))

(rf/reg-event-fx
 :add-episode
 (fn [{:keys [db]} _]
   {:db (assoc db :status "Creating episode...")
    :post-add-episode {:description (:new-episode-description db)}}))

(rf/reg-event-fx
 :add-episode-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db
               :new-episode-description ""
               :status "Episode created.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :add-episode-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Create episode failed: " msg))))

(rf/reg-event-fx
 :add-frame
 (fn [{:keys [db]} [_ episode-id]]
   {:db (assoc db :status "Adding frame...")
    :post-add-frame {:episode-id episode-id}}))

(rf/reg-event-fx
 :add-frame-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db :status "Frame added.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :add-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Add frame failed: " msg))))

(rf/reg-event-fx
 :delete-frame
 (fn [{:keys [db]} [_ frame-id]]
   {:db (assoc db :status "Deleting frame...")
    :post-delete-frame {:frame-id frame-id}}))

(rf/reg-event-fx
 :delete-frame-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db :status "Frame deleted.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :delete-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Delete frame failed: " msg))))
