(ns host.main
  (:require [clojure.string :as str]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn error-details [err]
  #js {:name (or (some-> err .-name) "Error")
       :message (or (some-> err .-message) (str err))
       :stack (or (some-> err .-stack) nil)})

(defn request-origin [request]
  (or (some-> request .-headers (.get "origin"))
      (some-> request .-headers (.get "Origin"))))

(defn diagnostic-headers [request]
  #js {"Content-Type" "application/json"
       "Access-Control-Allow-Origin" (or (request-origin request) "*")
       "Access-Control-Allow-Methods" "GET,POST,OPTIONS"
       "Access-Control-Allow-Headers" "Content-Type,Authorization"
       "Vary" "Origin"
       "Cache-Control" "no-store, no-cache, must-revalidate, proxy-revalidate"})

(defn startup-failure-response [request err]
  #js {:status 500
       :jsonBody #js {:error "Web API failed during startup."
                      :detail (error-details err)
                      :hint "Run `npm run build:webapi` and fix compile/runtime errors in ClojureScript services."}
       :headers (diagnostic-headers request)})

(defn missing-module-error? [err requested-path]
  (and err
       (= "MODULE_NOT_FOUND" (some-> err .-code))
       (string? (some-> err .-message))
       (str/includes? (.-message err) (str "'" requested-path "'"))))

(defn fatal-startup-env-error? [err]
  (let [message (str (or (some-> err .-message) ""))]
    (or (str/includes? message "Missing ROBOGENE_IMAGE_GENERATOR_KEY")
        (str/includes? message "Unsupported ROBOGENE_IMAGE_GENERATOR")
        (str/includes? message "Missing AzureWebJobsStorage")
        (str/includes? message "Missing ROBOGENE_STORAGE_CONNECTION_STRING")
        (str/includes? message "UseDevelopmentStorage=true"))))

(defn require-services-bundle! []
  (try
    (js/require "./webapi_compiled.js")
    (catch :default deployed-err
      (if-not (missing-module-error? deployed-err "./webapi_compiled.js")
        (throw deployed-err)
        (try
          (js/require "../../../dist/debug/webapi/webapi_compiled.js")
          (catch :default debug-err
            (if-not (missing-module-error? debug-err "../../../dist/debug/webapi/webapi_compiled.js")
              (throw debug-err)
              (js/require "../../../dist/release/webapi/webapi_compiled.js"))))))))

(defn register-startup-diagnostics! [err]
  (let [handler (fn [request _] (startup-failure-response request err))]
    (.http app "startup-diagnostic-state"
           #js {:methods #js ["GET"]
                :authLevel "anonymous"
                :route "state"
                :handler handler})

    (.http app "startup-diagnostic-generate-frame"
           #js {:methods #js ["POST"]
                :authLevel "anonymous"
                :route "generate-frame"
                :handler handler})

    (.http app "startup-diagnostic-options"
           #js {:methods #js ["OPTIONS"]
                :authLevel "anonymous"
                :route "{*path}"
                :handler (fn [request _]
                           #js {:status 204
                                :headers (diagnostic-headers request)})})))

(defn init!
  [& _]
  (try
    (require-services-bundle!)
    (catch :default err
      (.error js/console "[robogene] Failed to load services compiled bundle from host/dist or dist/debug|release/webapi.")
      (.error js/console err)
      (if (fatal-startup-env-error? err)
        (throw err)
        (register-startup-diagnostics! err))))
  true)
