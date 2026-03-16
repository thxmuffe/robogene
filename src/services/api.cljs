(ns services.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [services.entity :as entity]
            [services.realtime :as realtime]
            [host.settings :as settings]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn require-startup-env! []
  (when (empty? (settings/allowed-origins))
    (throw (js/Error. "Missing ROBOGENE_ALLOWED_ORIGIN in Function App settings."))))

(defn cors-headers [request]
  (let [origins (settings/allowed-origins)
        req-origin (or (some-> request .-headers (.get "origin"))
                       (some-> request .-headers (.get "Origin")))
        origin (if (and (seq req-origin) (some #(= % req-origin) origins)) req-origin (or (first origins) "*"))]
    (clj->js
     {"Content-Type" "application/json"
      "Access-Control-Allow-Origin" origin
      "Access-Control-Allow-Methods" "GET,POST,PATCH,DELETE,OPTIONS"
      "Access-Control-Allow-Headers" "Content-Type,Authorization"
      "Cache-Control" "no-store, no-cache, must-revalidate, proxy-revalidate"})))

(defn json-response [status data request]
  #js {:status status
       :jsonBody (clj->js data)
       :headers (cors-headers request)})

(defn handle-get-state [request]
  (-> (entity/sync-state!)
      (.then (fn [snapshot]
               (json-response 200 snapshot request)))))

(defn handle-save-entity [request]
  (-> (.json request)
      (.then (fn [body]
               (let [entity-data (js->clj body :keywordize-keys true)]
                 (-> (entity/save-entity! entity-data)
                     (.then (fn [saved]
                              (json-response 200 saved request)))))))))

(defn handle-delete-entity [request]
  (let [params (gobj/get request "params")
        id (gobj/get params "id")]
    (-> (entity/delete-entity! id)
        (.then (fn [_]
                 (json-response 200 {:deleted true :id id} request))))))

(defn handle-signalr-negotiate [request]
  (json-response 200 (or (realtime/create-client-connection-info) {:disabled true}) request))

(defn handle-options-preflight [request]
  #js {:status 204 :headers (cors-headers request)})

;; Register routes at top level so they run when the module is loaded
(require-startup-env!)

(.http app "get-state"
       #js {:methods #js ["GET"]
            :authLevel "anonymous"
            :route "state"
            :handler handle-get-state})

(.http app "post-entity"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "entity"
            :handler handle-save-entity})

(.http app "patch-entity"
       #js {:methods #js ["PATCH"]
            :authLevel "anonymous"
            :route "entity/{id}"
            :handler handle-save-entity})

(.http app "delete-entity"
       #js {:methods #js ["DELETE"]
            :authLevel "anonymous"
            :route "entity/{id}"
            :handler handle-delete-entity})

(.http app "signalr-negotiate"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "negotiate"
            :handler handle-signalr-negotiate})

(.http app "options-preflight"
       #js {:methods #js ["OPTIONS"]
            :authLevel "anonymous"
            :route "{*path}"
            :handler handle-options-preflight})

(defn init! [& _]
  true)
