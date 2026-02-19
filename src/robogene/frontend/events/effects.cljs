(ns robogene.frontend.events.effects
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [robogene.frontend.events.model :as model]))

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

(defn handle-state-response [{:keys [ok status text]}]
  (let [data (model/parse-json-safe text)]
    (if ok
      (rf/dispatch [:state-loaded data])
      (rf/dispatch [:state-failed (or (:error data)
                                      (str "HTTP " status))]))))

(defn handle-generate-response [{:keys [ok status text]}]
  (let [data (model/parse-json-safe text)]
    (if (or ok (= 409 status))
      (rf/dispatch [:generate-accepted data])
      (rf/dispatch [:generate-failed (or (:error data)
                                         (str "HTTP " status))]))))

(defn post-json [path body]
  (-> (js/fetch (api-url path)
                (clj->js {:method "POST"
                          :cache "no-store"
                          :headers {"Content-Type" "application/json"}
                          :body (.stringify js/JSON (clj->js body))}))
      (.then response->map)
      (.then handle-generate-response)
      (.catch (fn [e]
                (rf/dispatch [:generate-failed (str (.-message e))])))))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (set! (.-hash js/location) hash)))

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
   (post-json "/api/generate-frame" {:frameId frame-id
                                      :direction direction})))

(rf/reg-fx
 :post-generate-next
 (fn [direction]
   (post-json "/api/generate-next" {:direction direction})))
