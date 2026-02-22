(ns webapp.events.effects
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [re-frame.core :as rf]
            [webapp.events.model :as model]
            ["./fetch_coalescer.js" :as fetch-coalescer]
            ["@microsoft/signalr" :as signalr]))

(defonce realtime-conn* (atom nil))
(defonce realtime-connected?* (atom false))
(defonce realtime-starting?* (atom false))
(defonce realtime-epoch* (atom 0))
(defonce coalesced-fetch-state!* (atom nil))
(defonce realtime-retry-timeout-id* (atom nil))
(defonce realtime-retry-delay-ms* (atom 1000))

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
  [{:keys [ok status text]} success-event fail-event ok? request-label]
  (let [data (model/parse-json-safe text)]
    (rf/dispatch
     [:wait-lights-log
      (if (ok? ok status) :incoming :error)
      (str "Incoming: " request-label " -> HTTP " status)])
    (if (ok? ok status)
      (rf/dispatch [success-event data])
      (rf/dispatch [fail-event (or (:error data)
                                   (str "HTTP " status))]))))

(defn dispatch-network-error [fail-event e request-label]
  (rf/dispatch [:wait-lights-log
                :error
                (str "Network error: " request-label " -> " (or (.-message e) e))])
  (rf/dispatch [fail-event (str (.-message e))]))

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

(defn frame-node-list []
  (array-seq (.querySelectorAll js/document ".frame[data-frame-id]")))

(defn frame-id-of [el]
  (.getAttribute el "data-frame-id"))

(defn frame-centers [el]
  (let [r (.getBoundingClientRect el)]
    {:x (+ (.-left r) (/ (.-width r) 2))
     :y (+ (.-top r) (/ (.-height r) 2))
     :el el}))

(defn nearest-vertical-frame-id [current-id direction]
  (let [nodes (frame-node-list)
        current-el (some (fn [el] (when (= current-id (frame-id-of el)) el)) nodes)]
    (when current-el
      (let [{cx :x cy :y} (frame-centers current-el)
            candidates (->> nodes
                            (filter (fn [el]
                                      (let [{x :x y :y} (frame-centers el)]
                                        (case direction
                                          :up (< y (- cy 8))
                                          :down (> y (+ cy 8))
                                          false))))
                            (map (fn [el]
                                   (let [{x :x y :y} (frame-centers el)
                                         dy (js/Math.abs (- y cy))
                                         dx (js/Math.abs (- x cx))]
                                     {:id (frame-id-of el)
                                      :dy dy
                                      :dx dx})))
                            (sort-by (fn [{:keys [dy dx]}] [dy dx])))]
        (or (:id (first candidates))
            (case direction
              :up (some-> nodes last frame-id-of)
              :down (some-> nodes first frame-id-of)
              nil))))))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (set! (.-hash js/location) hash)))

(rf/reg-fx
 :scroll-frame-into-view
 (fn [frame-id]
   (when (seq (or frame-id ""))
     (when-let [el (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"]"))]
       (.scrollIntoView el #js {:behavior "smooth"
                                :block (if (= frame-id "__new_episode__") "center" "nearest")
                                :inline "nearest"})))))

(rf/reg-fx
 :move-active-frame-vertical
 (fn [{:keys [frame-id direction]}]
   (when (seq (or frame-id ""))
     (when-let [next-id (nearest-vertical-frame-id frame-id direction)]
       (rf/dispatch [:set-active-frame next-id])))))

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

(defn recover-with-fetch! [epoch]
  (when (epoch-current? epoch)
    (reset! realtime-connected?* false)
    (rf/dispatch [:fetch-state])))

(defn cancel-realtime-retry! []
  (when-let [id @realtime-retry-timeout-id*]
    (js/clearTimeout id)
    (reset! realtime-retry-timeout-id* nil)))

(defn reset-realtime-retry! []
  (cancel-realtime-retry!)
  (reset! realtime-retry-delay-ms* 1000))

(declare start-realtime!)

(defn schedule-realtime-retry! [why]
  (when (and (nil? @realtime-retry-timeout-id*)
             (nil? @realtime-conn*)
             (not @realtime-starting?*))
    (let [delay @realtime-retry-delay-ms*]
      (js/console.warn
       (str "[robogene] SignalR reconnect scheduled in " delay "ms (" why ")"))
      (reset! realtime-retry-timeout-id*
              (js/setTimeout
               (fn []
                 (reset! realtime-retry-timeout-id* nil)
                 (when (and (nil? @realtime-conn*)
                            (not @realtime-starting?*))
                   (start-realtime!)))
               delay))
      (swap! realtime-retry-delay-ms* (fn [ms] (min 30000 (* 2 (max 1000 ms))))))))

(defn build-connection [url access-token]
  (-> (signalr/HubConnectionBuilder.)
      (.withUrl url #js {:accessTokenFactory (fn [] access-token)})
      (.withAutomaticReconnect)
      (.build)))

(defn subscribe-connection-events! [conn epoch]
  (let [onreconnecting (gobj/get conn "onreconnecting")
        onreconnected (gobj/get conn "onreconnected")]
    (when (fn? onreconnecting)
      (.call onreconnecting conn
             (fn [_]
               (when (epoch-current? epoch)
                 (js/console.warn "[robogene] SignalR reconnecting...")
                 (reset! realtime-connected?* false)))))
    (when (fn? onreconnected)
      (.call onreconnected conn
             (fn [_]
               (when (epoch-current? epoch)
                 (js/console.log "[robogene] SignalR reconnected.")
                 (reset! realtime-connected?* true)
                 (reset-realtime-retry!)
                 (rf/dispatch [:fetch-state]))))))
  (.onclose conn
            (fn [_]
              (when (epoch-current? epoch)
                (js/console.warn "[robogene] SignalR closed.")
                (reset! realtime-connected?* false)
                (reset! realtime-conn* nil)
                (schedule-realtime-retry! "connection closed"))))
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
                 (reset-realtime-retry!)
                 (js/console.log "[robogene] SignalR connected.")
                 (rf/dispatch [:fetch-state]))))
      (.catch (fn [err]
                (when (epoch-current? epoch)
                  (reset! realtime-conn* nil))
                (recover-with-fetch! epoch)
                (schedule-realtime-retry! "start failed")
                (js/console.warn
                 (str "[robogene] SignalR start failed: "
                      (or (.-message err) err)))))))

(defn connect-from-negotiate! [info epoch]
  (if (true? (:disabled info))
    (recover-with-fetch! epoch)
    (let [url (or (:url info) (get info "url"))
          access-token (or (:accessToken info) (get info "accessToken"))]
      (when (str/blank? (or url ""))
        (throw (js/Error. "Negotiate response missing SignalR URL.")))
      (let [conn (build-connection url access-token)]
        (subscribe-connection-events! conn epoch)
        (start-connection! conn epoch)))))

(defn start-realtime! []
  (when (and (nil? @realtime-conn*)
             (not @realtime-starting?*))
    (reset! realtime-starting?* true)
    (js/console.log "[robogene] SignalR connect attempt...")
    (let [epoch (swap! realtime-epoch* inc)]
      (-> (negotiate-realtime!)
          (.then (fn [info]
                   (when (true? (:disabled info))
                     (js/console.warn "[robogene] SignalR disabled by negotiate response."))
                   (connect-from-negotiate! info epoch)))
        (.catch (fn [err]
                  (recover-with-fetch! epoch)
                  (schedule-realtime-retry! "negotiate failed")
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
             (.createCoalescedRunner
              fetch-coalescer
              (fn []
                (request-json (state-url)
                              {:cache "no-store"}
                              :state-loaded
                              :state-failed
                              (fn [ok _] ok))))))
   (@coalesced-fetch-state!*)))

(rf/reg-fx
 :post-generate-frame
 (fn [{:keys [frame-id direction]}]
   (post-json "/api/generate-frame"
              {:frameId frame-id
               :direction direction}
              :generate-accepted
              :generate-failed
              (fn [ok status] (or ok (= 409 status))))))

(rf/reg-fx
 :post-add-episode
 (fn [{:keys [description]}]
   (post-json "/api/add-episode"
              {:description description}
              :add-episode-accepted
              :add-episode-failed
              (fn [ok _] ok))))

(rf/reg-fx
 :post-add-frame
 (fn [{:keys [episode-id]}]
   (post-json "/api/add-frame"
              {:episodeId episode-id}
              :add-frame-accepted
              :add-frame-failed
              (fn [ok _] ok))))

(rf/reg-fx
 :post-delete-frame
 (fn [{:keys [frame-id]}]
   (post-json "/api/delete-frame"
              {:frameId frame-id}
              :delete-frame-accepted
              :delete-frame-failed
              (fn [ok _] ok))))

(rf/reg-fx
 :post-clear-frame-image
 (fn [{:keys [frame-id]}]
   (post-json "/api/clear-frame-image"
              {:frameId frame-id}
              :clear-frame-image-accepted
              :clear-frame-image-failed
              (fn [ok _] ok))))

(rf/reg-fx
 :start-episode-celebration
 (fn [_]
   (js/setTimeout
    (fn []
      (rf/dispatch [:episode-celebration-ended]))
    2200)))
