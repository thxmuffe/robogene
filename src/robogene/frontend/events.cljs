(ns robogene.frontend.events
  (:require [re-frame.core :as rf]
            [robogene.frontend.db :as db]
            [clojure.string :as str]))

(defonce poll-timeout-id (atom nil))

(defn api-base []
  (let [base (or (.-ROBOGENE_API_BASE js/window) "")]
    (str/replace base #"/+$" "")))

(defn api-url [path]
  (let [base (api-base)]
    (if (str/blank? base) path (str base path))))

(defn state-url []
  (str (api-url "/api/state") "?t=" (.now js/Date)))

(defn response->map [res]
  (-> (.text res)
      (.then (fn [text]
               {:ok (.-ok res)
                :status (.-status res)
                :text text}))))

(defn parse-json-safe [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:error (str "Expected JSON from backend, got non-JSON response.")})))

(defn handle-state-response [{:keys [ok status text]}]
  (let [data (parse-json-safe text)]
    (if ok
      (rf/dispatch [:state-loaded data])
      (rf/dispatch [:state-failed (or (:error data)
                                      (str "HTTP " status))]))))

(defn handle-generate-response [{:keys [ok status text]}]
  (let [data (parse-json-safe text)]
    (if (or ok (= 409 status))
      (rf/dispatch [:generate-accepted data])
      (rf/dispatch [:generate-failed (or (:error data)
                                         (str "HTTP " status))]))))

(defn to-gallery-items [state]
  (->> (or (:frames state) [])
       (sort-by :sceneNumber >)
       vec))

(defn merged-frame-inputs [existing frames]
  (reduce (fn [acc frame]
            (let [frame-id (:frameId frame)]
              (assoc acc frame-id
                     (or (get existing frame-id)
                         (:directionText frame)
                         (:suggestedDirection frame)
                         ""))))
          {}
          frames))

(defn status-line [state]
  (let [pending (or (:pendingCount state) 0)
        total (count (or (:frames state) []))]
    (str "Frames: " total
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))

(rf/reg-fx
 :schedule-poll
 (fn [{:keys [ms dispatch]}]
   (when-let [id @poll-timeout-id]
     (js/clearTimeout id))
   (reset! poll-timeout-id
           (js/setTimeout #(rf/dispatch dispatch) ms))))

(rf/reg-fx
 :fetch-state
 (fn [_]
   (-> (js/fetch (state-url) (clj->js {:cache "no-store"}))
       (.then response->map)
       (.then handle-state-response)
       (.catch (fn [e]
                 (rf/dispatch [:state-failed (str (.-message e))]))))))

(rf/reg-fx
 :post-generate-frame
 (fn [{:keys [frame-id direction]}]
   (-> (js/fetch (api-url "/api/generate-frame")
                 (clj->js {:method "POST"
                           :cache "no-store"
                           :headers {"Content-Type" "application/json"}
                           :body (.stringify js/JSON (clj->js {:frameId frame-id
                                                               :direction direction}))}))
       (.then response->map)
       (.then handle-generate-response)
       (.catch (fn [e]
                 (rf/dispatch [:generate-failed (str (.-message e))]))))))

(rf/reg-event-fx
 :initialize
 (fn [_ _]
   {:db db/default-db
    :dispatch [:fetch-state]}))

(rf/reg-event-fx
 :fetch-state
 (fn [{:keys [db]} _]
   {:db db
    :fetch-state true}))

(rf/reg-event-fx
 :state-loaded
 (fn [{:keys [db]} [_ state]]
   (let [revision (:revision state)
         changed? (not= revision (:last-rendered-revision db))
         frames (or (:frames state) [])
         new-db (-> db
                    (assoc :latest-state state
                           :status (status-line state)
                           :last-rendered-revision revision)
                    (cond-> changed? (assoc :gallery-items (to-gallery-items state)))
                    (assoc :frame-inputs (merged-frame-inputs (:frame-inputs db) frames)))]
     {:db new-db
      :schedule-poll {:ms (if (pos? (or (:pendingCount state) 0)) 1200 3500)
                      :dispatch [:fetch-state]}})))

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
   (let [direction (get-in db [:frame-inputs frame-id] "")]
     {:db (assoc db :status "Queueing frame...")
      :post-generate-frame {:frame-id frame-id
                            :direction direction}})))

(rf/reg-event-fx
 :generate-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db db
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
