(ns robogene.frontend.events.effects
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]
            ["@microsoft/signalr" :as signalr]))

(defonce realtime-conn* (atom nil))
(defonce realtime-connected?* (atom false))
(defonce fallback-poll-timer* (atom nil))
(defonce fallback-poll-ms* (atom nil))
;; Legacy vars kept intentionally to clear intervals created by earlier builds
;; during shadow-cljs hot-reload sessions.
(defonce state-poll-timer* (atom nil))
(defonce state-poll-interval-ms* (atom nil))
(defonce refresh-timer* (atom nil))

(defn api-base []
  (-> (or (.-ROBOGENE_API_BASE js/window) "")
      (str/replace #"/+$" "")))

(defn fallback-polling-enabled? []
  (= "1" (some-> (.-ROBOGENE_ENABLE_FALLBACK_POLLING js/window) str)))

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

(defn negotiate-realtime! []
  (-> (js/fetch (api-url "/api/negotiate")
                #js {:method "POST"
                     :cache "no-store"})
      (.then response->map)
      (.then (fn [{:keys [ok status text]}]
               (if ok
                 (model/parse-json-safe text)
                 (throw (js/Error. (str "Negotiate failed: HTTP " status))))))))

(defn start-realtime! []
  (when-not @realtime-conn*
    (-> (negotiate-realtime!)
        (.then (fn [info]
                 (if (true? (:disabled info))
                   (do
                     (reset! realtime-connected?* false)
                     (rf/dispatch [:set-fallback-polling :idle])
                     (rf/dispatch [:fetch-state]))
                   (let [url (or (:url info) (get info "url"))
                         access-token (or (:accessToken info) (get info "accessToken"))]
                      (when (str/blank? (or url ""))
                        (throw (js/Error. "Negotiate response missing SignalR URL.")))
                      (let [builder (signalr/HubConnectionBuilder.)
                            conn (-> builder
                                     (.withUrl url #js {:accessTokenFactory (fn [] access-token)})
                                     (.withAutomaticReconnect)
                                     (.build))]
                        (.onreconnecting conn
                                         (fn [_]
                                           (reset! realtime-connected?* false)))
                        (.onclose conn
                                  (fn [_]
                                    (reset! realtime-connected?* false)))
                        (.onreconnected conn
                                        (fn [_]
                                          (reset! realtime-connected?* true)
                                          (rf/dispatch [:set-fallback-polling :off])
                                          (rf/dispatch [:fetch-state])))
                        (.on conn "stateChanged"
                             (fn [_]
                               (rf/dispatch [:fetch-state])))
                        (-> (.start conn)
                            (.then (fn []
                                     (reset! realtime-conn* conn)
                                     (reset! realtime-connected?* true)
                                     (rf/dispatch [:set-fallback-polling :off])
                                     (rf/dispatch [:fetch-state])))
                            (.catch (fn [err]
                                      (reset! realtime-connected?* false)
                                      (rf/dispatch [:set-fallback-polling :idle])
                                      (js/console.warn
                                       (str "[robogene] SignalR start failed: "
                                            (or (.-message err) err)))))))))))
        (.catch (fn [err]
                  (reset! realtime-connected?* false)
                  (rf/dispatch [:set-fallback-polling :idle])
                  (js/console.warn
                   (str "[robogene] SignalR negotiate failed: "
                        (or (.-message err) err))))))))

(defn stop-legacy-state-polling! []
  (when-let [timer-id @refresh-timer*]
    (js/clearInterval timer-id)
    (reset! refresh-timer* nil))
  (when-let [timer-id @state-poll-timer*]
    (js/clearInterval timer-id)
    (reset! state-poll-timer* nil)
    (reset! state-poll-interval-ms* nil)))

(rf/reg-fx
 :realtime-connect
 (fn [_]
   (stop-legacy-state-polling!)
   (start-realtime!)))

(defn stop-fallback-polling! []
  (when-let [timer-id @fallback-poll-timer*]
    (js/clearInterval timer-id)
    (reset! fallback-poll-timer* nil)
    (reset! fallback-poll-ms* nil)))

(defn start-fallback-polling! [interval-ms]
  (when (or (nil? @fallback-poll-timer*)
            (not= @fallback-poll-ms* interval-ms))
    (stop-fallback-polling!)
    (reset! fallback-poll-ms* interval-ms)
    (reset! fallback-poll-timer*
            (js/setInterval
             (fn []
               (when-not (.-hidden js/document)
                 (rf/dispatch [:fetch-state])))
             interval-ms))))

(rf/reg-fx
 :set-fallback-polling
 (fn [mode]
   (if @realtime-connected?*
     (stop-fallback-polling!)
     (case mode
       ;; Always allow active polling while generation is in progress so
       ;; completion updates arrive when SignalR is unavailable.
       :active (start-fallback-polling! 3000)
       ;; Idle polling remains opt-in to avoid constant background noise.
       :idle (if (fallback-polling-enabled?)
               (start-fallback-polling! 15000)
               (stop-fallback-polling!))
       :off (stop-fallback-polling!)
       nil))))

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
