(ns robogene.frontend.events.effects
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]
            ["./fetch_coalescer.js" :as fetch-coalescer]
            ["@microsoft/signalr" :as signalr]))

(defonce realtime-conn* (atom nil))
(defonce realtime-connected?* (atom false))
(defonce realtime-starting?* (atom false))
(defonce realtime-epoch* (atom 0))
(defonce coalesced-fetch-state!* (atom nil))

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
  (-> (js/fetch (api-url "/api/negotiate")
                #js {:method "POST"
                     :cache "no-store"})
      (.then response->map)
      (.then (fn [{:keys [ok status text]}]
               (if ok
                 (model/parse-json-safe text)
                 (throw (js/Error. (str "Negotiate failed: HTTP " status))))))))

(defn start-realtime! []
  (when (and (nil? @realtime-conn*)
             (not @realtime-starting?*))
    (reset! realtime-starting?* true)
    (let [epoch (swap! realtime-epoch* inc)
          current? (fn [] (= epoch @realtime-epoch*))]
    (-> (negotiate-realtime!)
        (.then (fn [info]
                 (if (true? (:disabled info))
                   (do
                     (when (current?)
                       (reset! realtime-connected?* false)
                       (rf/dispatch [:fetch-state])))
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
                                           (when (current?)
                                             (reset! realtime-connected?* false))))
                        (.onclose conn
                                  (fn [_]
                                    (when (current?)
                                      (reset! realtime-connected?* false)
                                      (reset! realtime-conn* nil))))
                        (.onreconnected conn
                                        (fn [_]
                                          (when (current?)
                                            (reset! realtime-connected?* true)
                                            (rf/dispatch [:fetch-state]))))
                        (.on conn "stateChanged"
                             (fn [_]
                               (when (current?)
                                 (reset! realtime-connected?* true)
                                 (rf/dispatch [:fetch-state]))))
                        (-> (.start conn)
                            (.then (fn []
                                     (when (current?)
                                       (reset! realtime-conn* conn)
                                       (reset! realtime-connected?* true)
                                       (rf/dispatch [:fetch-state]))))
                            (.catch (fn [err]
                                      (when (current?)
                                        (reset! realtime-connected?* false)
                                        (reset! realtime-conn* nil)
                                        (rf/dispatch [:fetch-state]))
                                      (js/console.warn
                                       (str "[robogene] SignalR start failed: "
                                            (or (.-message err) err)))))))))))
        (.catch (fn [err]
                  (when (current?)
                    (reset! realtime-connected?* false)
                    (rf/dispatch [:fetch-state]))
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
