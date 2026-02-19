(ns robogene.frontend.events
  (:require [re-frame.core :as rf]
            [robogene.frontend.db :as db]
            [clojure.string :as str]))

(defonce poll-timeout-id (atom nil))

(def legacy-draft-id "__legacy_draft__")

(defn api-base []
  (let [base (or (.-ROBOGENE_API_BASE js/window) "")]
    (str/replace base #"/+$" "")))

(defn api-url [path]
  (let [base (api-base)]
    (if (str/blank? base) path (str base path))))

(defn state-url []
  (str (api-url "/api/state") "?t=" (.now js/Date)))

(defn wait-state-url [since timeout-ms]
  (str (api-url "/api/wait-state")
       "?since=" since
       "&timeoutMs=" timeout-ms
       "&t=" (.now js/Date)))

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

(defn frame-from-history [idx h]
  {:frameId (or (:frameId h) (str "legacy-ready-" (:sceneNumber h) "-" idx))
   :sceneNumber (:sceneNumber h)
   :beatText (:beatText h)
   :suggestedDirection ""
   :directionText ""
   :imageDataUrl (:imageDataUrl h)
   :status "ready"
   :reference (:reference h)
   :createdAt (:createdAt h)})

(defn frame-from-pending [idx p]
  {:frameId (or (:frameId p) (:jobId p) (str "legacy-pending-" (:sceneNumber p) "-" idx))
   :sceneNumber (:sceneNumber p)
   :beatText (:beatText p)
   :suggestedDirection (:directionText p)
   :directionText (:directionText p)
   :error (:error p)
   :status (or (:status p) "queued")
   :createdAt (:queuedAt p)})

(defn legacy-draft-frame [state existing-frames]
  (let [max-scene (reduce max 0 (map :sceneNumber existing-frames))
        suggested (or (:nextDefaultDirection state) "")
        next-num (or (:nextSceneNumber state) (inc max-scene))]
    {:frameId legacy-draft-id
     :sceneNumber next-num
     :beatText (str "Scene " next-num)
     :suggestedDirection suggested
     :directionText suggested
     :status "draft"
     :createdAt (.toISOString (js/Date.))}))

(defn frame-model [state]
  (if (seq (:frames state))
    {:backend-mode :frames
     :frames (vec (:frames state))}
    (let [ready (->> (or (:history state) [])
                     (map-indexed frame-from-history))
          pending (->> (or (:pending state) [])
                       (map-indexed frame-from-pending))
          frames (vec (concat ready pending))
          frames-with-draft (if (some (fn [f] (str/blank? (or (:imageDataUrl f) ""))) frames)
                              frames
                              (conj frames (legacy-draft-frame state frames)))
          ensured (if (seq frames-with-draft)
                    frames-with-draft
                    [(legacy-draft-frame state [])])]
      {:backend-mode :legacy
       :frames ensured})))

(defn to-gallery-items [frames]
  (->> frames
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

(defn status-line [state frames]
  (let [pending (or (:pendingCount state)
                    (count (filter (fn [f]
                                     (or (= "queued" (:status f))
                                         (= "processing" (:status f))))
                                   frames)))
        total (count frames)]
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
 :await-state-change
 (fn [{:keys [since timeout-ms]}]
   (-> (js/fetch (wait-state-url (or since 0) (or timeout-ms 25000))
                 (clj->js {:cache "no-store"}))
       (.then response->map)
       (.then (fn [{:keys [ok status text]}]
                (let [data (parse-json-safe text)]
                  (if ok
                    (rf/dispatch [:state-change-signal data])
                    (rf/dispatch [:wait-failed (or (:error data) (str "HTTP " status))])))))
       (.catch (fn [e]
                 (rf/dispatch [:wait-failed (str (.-message e))]))))))

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

(rf/reg-fx
 :post-generate-next
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
         {:keys [backend-mode frames]} (frame-model state)
         new-db (-> db
                    (assoc :latest-state state
                           :backend-mode backend-mode
                           :status (status-line state frames)
                           :last-rendered-revision revision
                           :gallery-items (to-gallery-items frames))
                    (assoc :frame-inputs (merged-frame-inputs (:frame-inputs db) frames)))]
     {:db new-db
      :await-state-change {:since revision
                           :timeout-ms 25000}})))

(rf/reg-event-fx
 :state-change-signal
 (fn [{:keys [db]} [_ data]]
   (let [latest (or (:last-rendered-revision db) 0)
         changed? (or (:changed data) (> (or (:revision data) 0) latest))]
     (if changed?
       {:db db
        :dispatch [:fetch-state]}
       {:db db
        :await-state-change {:since latest
                             :timeout-ms 25000}}))))

(rf/reg-event-fx
 :wait-failed
 (fn [{:keys [db]} [_ _msg]]
   {:db db
    :schedule-poll {:ms 5000
                    :dispatch [:fetch-state]}}))

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
  (let [direction (get-in db [:frame-inputs frame-id] "")
         legacy? (= :legacy (:backend-mode db))]
     (cond
       legacy?
       {:db (assoc db :status "Queueing frame...")
        :post-generate-next direction}

       :else
       {:db (assoc db :status "Queueing frame...")
        :post-generate-frame {:frame-id frame-id
                              :direction direction}}))))

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
