(ns robogene.frontend.events.effects
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]
            ["@microsoft/signalr" :as signalr]))

(defonce realtime-conn* (atom nil))

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
                 (let [url (or (:url info) (get info "url"))
                       access-token (or (:accessToken info) (get info "accessToken"))]
                   (when (str/blank? (or url ""))
                     (throw (js/Error. "Negotiate response missing SignalR URL.")))
                   (let [builder (signalr/HubConnectionBuilder.)
                         conn (-> builder
                                  (.withUrl url #js {:accessTokenFactory (fn [] access-token)})
                                  (.withAutomaticReconnect)
                                  (.build))]
                     (.on conn "stateChanged"
                          (fn [_]
                            (rf/dispatch [:fetch-state])))
                     (-> (.start conn)
                         (.then (fn []
                                  (reset! realtime-conn* conn)
                                  (rf/dispatch [:fetch-state])))
                         (.catch (fn [err]
                                   (js/console.warn
                                    (str "[robogene] SignalR start failed: "
                                         (or (.-message err) err))))))))))
        (.catch (fn [err]
                  (js/console.warn
                   (str "[robogene] SignalR negotiate failed: "
                        (or (.-message err) err))))))))

(rf/reg-fx
 :realtime-connect
 (fn [_]
   (start-realtime!)))

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
