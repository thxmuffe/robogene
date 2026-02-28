(ns webapp.shared.events.transport
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.model :as model]
            ["@microsoft/signalr" :as signalr]))

(defonce realtime-conn* (atom nil))
(defonce realtime-connected?* (atom false))
(defonce realtime-starting?* (atom false))
(defonce realtime-epoch* (atom 0))
(defonce coalesced-fetch-state!* (atom nil))

(defn create-coalesced-runner [task]
  (let [inflight?* (atom false)
        queued?* (atom false)]
    (letfn [(run []
              (if @inflight?*
                (do
                  (reset! queued?* true)
                  (js/Promise.resolve false))
                (do
                  (reset! inflight?* true)
                  (-> (js/Promise.resolve)
                      (.then (fn [] (task)))
                      (.finally
                       (fn []
                         (reset! inflight?* false)
                         (when @queued?*
                           (reset! queued?* false)
                           (run))))))))]
      run)))

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

(defn dispatch-event! [event payload]
  (if (vector? event)
    (rf/dispatch (conj event payload))
    (rf/dispatch [event payload])))

(defn dispatch-api-response
  [{:keys [ok status text]} success-event fail-event ok? request-label]
  (let [data (model/parse-json-safe text)]
    (rf/dispatch
     [:wait-lights-log
      (if (ok? ok status) :incoming :error)
      (str "Incoming: " request-label " -> HTTP " status)])
    (if (ok? ok status)
      (dispatch-event! success-event data)
      (dispatch-event! fail-event (or (:error data)
                                      (str "HTTP " status))))))

(defn dispatch-network-error [fail-event e request-label]
  (rf/dispatch [:wait-lights-log
                :error
                (str "Network error: " request-label " -> " (or (.-message e) e))])
  (dispatch-event! fail-event (str (.-message e))))

(defn request-json
  [url request-options success-event fail-event ok?]
  (let [method (str/upper-case (str (or (:method request-options) "GET")))
        request-label (str method " " url)]
    (rf/dispatch [:api-request-start request-label])
    (-> (js/fetch url
                  (clj->js request-options))
        (.then response->map)
        (.then #(dispatch-api-response % success-event fail-event ok? request-label))
        (.catch #(dispatch-network-error fail-event % request-label))
        (.finally #(rf/dispatch [:api-request-finish request-label])))))

(defn post-json
  [path payload success-event fail-event ok?]
  (request-json (api-url path)
                {:method "POST"
                 :cache "no-store"
                 :headers {"Content-Type" "application/json"}
                 :body (.stringify js/JSON (clj->js payload))}
                success-event
                fail-event
                ok?))

(defn negotiate-realtime! []
  (let [request-label "POST /api/negotiate"]
    (rf/dispatch [:api-request-start request-label])
    (-> (js/fetch (api-url "/api/negotiate")
                  #js {:method "POST"
                       :cache "no-store"})
        (.then response->map)
        (.then (fn [{:keys [ok status text]}]
                 (rf/dispatch
                  [:wait-lights-log
                   (if ok :incoming :error)
                   (str "Incoming: " request-label " -> HTTP " status)])
                 (if ok
                   (model/parse-json-safe text)
                   (throw (js/Error. (str "Negotiate failed: HTTP " status))))))
        (.catch (fn [e]
                  (rf/dispatch [:wait-lights-log
                                :error
                                (str "Network error: " request-label " -> " (or (.-message e) e))])
                  (throw e)))
        (.finally #(rf/dispatch [:api-request-finish request-label])))))

(defn epoch-current? [epoch]
  (= epoch @realtime-epoch*))

(defn recover-with-fetch! [epoch message]
  (when (epoch-current? epoch)
    (reset! realtime-connected?* false)
    (rf/dispatch [:state-failed message])))

(defn build-connection [url access-token]
  (-> (signalr/HubConnectionBuilder.)
      (.withUrl url #js {:accessTokenFactory (fn [] access-token)})
      (.build)))

(defn subscribe-connection-events! [conn epoch]
  (.onclose conn
            (fn [_]
              (when (epoch-current? epoch)
                (js/console.warn "[robogene] SignalR closed.")
                (reset! realtime-connected?* false)
                (reset! realtime-conn* nil)
                (rf/dispatch [:state-failed "Realtime connection closed."]))))
  (.on conn "stateChanged"
       (fn [_]
         (when (epoch-current? epoch)
           (js/console.log "[robogene] SignalR stateChanged event received.")
           (reset! realtime-connected?* true)
           (rf/dispatch [:fetch-state])))))

(defn start-connection! [conn epoch]
  (-> (.start conn)
      (.then (fn []
               (when (epoch-current? epoch)
                 (reset! realtime-conn* conn)
                 (reset! realtime-connected?* true)
                 (js/console.log "[robogene] SignalR connected.")
                 (rf/dispatch [:fetch-state]))))
      (.catch (fn [err]
                (when (epoch-current? epoch)
                  (reset! realtime-conn* nil))
                (recover-with-fetch! epoch (str "Realtime start failed: " (or (.-message err) err)))
                (js/console.warn
                 (str "[robogene] SignalR start failed: "
                      (or (.-message err) err)))))))

(defn connect-from-negotiate! [info epoch]
  (let [url (:url info)
        access-token (:accessToken info)]
    (when (str/blank? (or url ""))
      (throw (js/Error. "Negotiate response missing SignalR URL.")))
    (when (str/blank? (or access-token ""))
      (throw (js/Error. "Negotiate response missing SignalR access token.")))
    (let [conn (build-connection url access-token)]
      (subscribe-connection-events! conn epoch)
      (start-connection! conn epoch))))

(defn start-realtime! []
  (when (and (nil? @realtime-conn*)
             (not @realtime-starting?*))
    (reset! realtime-starting?* true)
    (js/console.log "[robogene] SignalR connect attempt...")
    (let [epoch (swap! realtime-epoch* inc)]
      (-> (negotiate-realtime!)
          (.then (fn [info]
                   (connect-from-negotiate! info epoch)))
          (.catch (fn [err]
                    (recover-with-fetch! epoch (str "Realtime negotiate failed: " (or (.-message err) err)))
                    (js/console.warn
                     (str "[robogene] SignalR negotiate failed: "
                          (or (.-message err) err)))))
          (.finally (fn []
                      (reset! realtime-starting?* false)))))))

(rf/reg-fx
 :realtime-connect
 (fn [_]
   (start-realtime!)))

(rf/reg-fx
 :fetch-state
 (fn [_]
   (when-not @coalesced-fetch-state!*
     (reset! coalesced-fetch-state!*
             (create-coalesced-runner
              (fn []
                (request-json (state-url)
                              {:cache "no-store"}
                              :state-loaded
                              :state-failed
                              (fn [ok _] ok))))))
   (@coalesced-fetch-state!*)))

(rf/reg-fx
 :post-generate-frame
 (fn [{:keys [frame-id direction on-success on-failure]}]
   (post-json "/api/generate-frame"
              {:frameId frame-id
               :direction direction}
              on-success
              on-failure
              (fn [ok status] (or ok (= 409 status))))))

(rf/reg-fx
 :post-add-chapter
 (fn [{:keys [description on-success on-failure]}]
   (post-json "/api/add-chapter"
              {:description description}
              on-success
              on-failure
              (fn [ok _] ok))))

(rf/reg-fx
 :post-add-frame
 (fn [{:keys [chapter-id on-success on-failure]}]
   (post-json "/api/add-frame"
              {:chapterId chapter-id}
              on-success
              on-failure
              (fn [ok _] ok))))

(rf/reg-fx
 :post-delete-frame
 (fn [{:keys [frame-id on-success on-failure]}]
   (post-json "/api/delete-frame"
              {:frameId frame-id}
              on-success
              on-failure
              (fn [ok _] ok))))

(rf/reg-fx
 :post-clear-frame-image
 (fn [{:keys [frame-id on-success on-failure]}]
   (post-json "/api/clear-frame-image"
              {:frameId frame-id}
              on-success
              on-failure
              (fn [ok _] ok))))
