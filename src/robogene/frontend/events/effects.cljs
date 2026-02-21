(ns robogene.frontend.events.effects
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]))

(defonce state-poll-timer* (atom nil))
(defonce state-poll-interval-ms* (atom nil))

(defn api-base []
  (-> (or (.-ROBOGENE_API_BASE js/window) "")
      (str/replace #"/+$" "")))

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

(defn dispatch-api-response
  [{:keys [ok status text]} success-event fail-event ok?]
  (let [data (model/parse-json-safe text)]
    (if (ok? ok status)
      (rf/dispatch [success-event data])
      (rf/dispatch [fail-event (or (:error data)
                                   (str "HTTP " status))]))))

(defn dispatch-network-error [fail-event e]
  (rf/dispatch [fail-event (str (.-message e))]))

(defn request-json
  [url request-options success-event fail-event ok?]
  (-> (js/fetch url
                (clj->js request-options))
      (.then response->map)
      (.then #(dispatch-api-response % success-event fail-event ok?))
      (.catch #(dispatch-network-error fail-event %))))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (set! (.-hash js/location) hash)))

(rf/reg-fx
 :dispatch-after
 (fn [entries]
   (doseq [{:keys [ms event]} (if (sequential? entries) entries [entries])]
     (js/setTimeout #(rf/dispatch event) ms))))

(defn stop-state-polling! []
  (when-let [timer-id @state-poll-timer*]
    (js/clearInterval timer-id)
    (reset! state-poll-timer* nil)
    (reset! state-poll-interval-ms* nil)))

(defn start-state-polling! [interval-ms]
  (when (or (nil? @state-poll-timer*)
            (not= @state-poll-interval-ms* interval-ms))
    (stop-state-polling!)
    (reset! state-poll-interval-ms* interval-ms)
    (reset! state-poll-timer*
            (js/setInterval
             (fn []
               (when-not (.-hidden js/document)
                 (rf/dispatch [:fetch-state])))
             interval-ms))))

(rf/reg-fx
 :set-state-polling
 (fn [mode]
   (case mode
     :active (start-state-polling! 2500)
     :idle (start-state-polling! 15000)
     :off (stop-state-polling!)
     nil)))

(rf/reg-fx
 :fetch-state
 (fn [_]
   (request-json (state-url)
                 {:cache "no-store"}
                 :state-loaded
                 :state-failed
                 (fn [ok _] ok))))

(rf/reg-fx
 :post-generate-frame
 (fn [{:keys [frame-id direction]}]
   (request-json (api-url "/api/generate-frame")
                 {:method "POST"
                  :cache "no-store"
                  :headers {"Content-Type" "application/json"}
                  :body (.stringify js/JSON
                                    (clj->js {:frameId frame-id
                                              :direction direction}))}
                 :generate-accepted
                 :generate-failed
                 (fn [ok status] (or ok (= 409 status))))))
