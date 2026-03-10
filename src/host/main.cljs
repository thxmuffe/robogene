(ns host.main
  (:require [clojure.string :as str]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn local-script-root? []
  (let [script-root (or (some-> js/process .-env (aget "AzureWebJobsScriptRoot")) "")]
    (or (str/includes? script-root "/src/host")
        (str/includes? script-root "\\src\\host"))))

(defn local-build-profile []
  (some-> js/process .-env (aget "ROBOGENE_BUILD_PROFILE") str/lower-case))

(defn require-services-bundle! []
  (if (local-script-root?)
    (case (local-build-profile)
      "release" (js/require "../../../dist/release/webapi/webapi_compiled.js")
      (js/require "../../../dist/debug/webapi/webapi_compiled.js"))
    (js/require "./webapi_compiled.js")))

(defn init!
  [& _]
  (require-services-bundle!)
  true)
