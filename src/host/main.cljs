(ns host.main
  (:require [clojure.string :as str]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn local-script-root? []
  (let [script-root (or (some-> js/process .-env (aget "AzureWebJobsScriptRoot")) "")]
    (or (str/includes? script-root "/src/host")
        (str/includes? script-root "\\src\\host"))))

(defn require-services-bundle! []
  (if (local-script-root?)
    (js/require "../../../dist/debug/webapi/webapi_compiled.js")
    (js/require "./webapi_compiled.js")))

(defn init!
  [& _]
  (require-services-bundle!)
  true)
