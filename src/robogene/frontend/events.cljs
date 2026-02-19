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

(defn parse-json [text]
  (js->clj (.parse js/JSON text) :keywordize-keys true))

(defn handle-state-response [{:keys [ok status text]}]
  (let [data (parse-json text)]
    (if ok
      (rf/dispatch [:state-loaded data])
      (rf/dispatch [:state-failed (or (:error data)
                                      (str "HTTP " status))]))))

(defn handle-generate-response [{:keys [ok status text]}]
  (let [data (parse-json text)]
    (if (or ok (= 409 status))
      (rf/dispatch [:generate-accepted data])
      (rf/dispatch [:generate-failed (or (:error data)
                                         (str "HTTP " status))]))))

(defn to-gallery-items [state]
  (let [history (map (fn [s] {:kind :history :scene-number (:sceneNumber s) :payload s})
                     (or (:history state) []))
        pending (map (fn [p] {:kind :pending :scene-number (:sceneNumber p) :payload p})
                     (or (:pending state) []))]
    (->> (concat history pending)
         (sort-by :scene-number >)
         vec)))

(defn status-line [state]
  (let [pending (or (:pendingCount state) 0)
        cursor (:cursor state)
        total (:totalScenes state)]
    (if (and (> cursor total) (zero? pending))
      "Storyboard complete."
      (str "Next scene: " (:nextSceneNumber state) "/" total
           (when (pos? pending) (str " | Queue: " pending))))))

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
 :post-generate
 (fn [direction]
   (-> (js/fetch (api-url "/api/generate-next")
                 (clj->js {:method "POST"
                           :cache "no-store"
                           :headers {"Content-Type" "application/json"}
                           :body (.stringify js/JSON (clj->js {:direction direction}))}))
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
         new-db (-> db
                    (assoc :latest-state state
                           :pending-count (or (:pendingCount state) 0)
                           :status (status-line state)
                           :last-rendered-revision revision)
                    (cond-> changed? (assoc :gallery-items (to-gallery-items state)))
                    (cond-> (or (not (:direction-dirty? db))
                                (str/blank? (:direction-input db)))
                      (assoc :direction-input (or (:nextDefaultDirection state) "")
                             :direction-dirty? false)))]
     {:db new-db
      :schedule-poll {:ms (if (pos? (or (:pendingCount state) 0)) 1200 3500)
                      :dispatch [:fetch-state]}})))

(rf/reg-event-db
 :state-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Load failed: " msg))))

(rf/reg-event-db
 :direction-changed
 (fn [db [_ value]]
   (assoc db :direction-input value
             :direction-dirty? true)))

(rf/reg-event-fx
 :generate-next
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc :submitting? true
                   :status "Queued next scene..."))
    :post-generate (:direction-input db)}))

(rf/reg-event-fx
 :generate-accepted
 (fn [{:keys [db]} [_ data]]
   (if (:done data)
     {:db (assoc db :submitting? false :status "Storyboard complete.")}
     {:db (-> db
              (assoc :submitting? false
                     :direction-dirty? false
                     :direction-input (or (:nextDefaultDirection data) "")))
      :dispatch [:fetch-state]})))

(rf/reg-event-db
 :generate-failed
 (fn [db [_ msg]]
   (assoc db :submitting? false :status (str "Generation failed: " msg))))

(rf/reg-event-fx
 :force-refresh
 (fn [{:keys [db]} _]
   {:db db
    :dispatch [:fetch-state]}))
