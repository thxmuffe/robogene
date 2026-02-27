(ns host.settings
  (:require [clojure.string :as str]
            [host.config :as config]))

(def default-mock-data-url
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7+Jc8AAAAASUVORK5CYII=")

(defn image-generator []
  (some-> (config/setting "ROBOGENE_IMAGE_GENERATOR" "openai")
          str/lower-case
          not-empty))

(defn image-generator-key []
  (config/setting "ROBOGENE_IMAGE_GENERATOR_KEY"))

(defn mock-data-url []
  (or (config/setting "ROBOGENE_IMAGE_GENERATOR_MOCK_DATA_URL")
      default-mock-data-url))

(defn mock-delay-ms []
  (config/parse-int (config/setting "ROBOGENE_IMAGE_GENERATOR_MOCK_DELAY_MS") 0))

(defn image-settings []
  (let [raw (config/setting "OPENAI_IMAGE_OPTIONS_JSON")]
    (cond
      (map? raw) raw
      (str/blank? (or raw "")) {}
      :else
      (let [parsed (try
                     (js->clj (.parse js/JSON raw))
                     (catch :default _
                       nil))]
        (if (map? parsed) parsed {})))))

(defn allowed-origins []
  (config/parse-csv (config/setting "ROBOGENE_ALLOWED_ORIGIN" "")))

(defn signalr-hub-name []
  (config/setting "ROBOGENE_SIGNALR_HUB" "robogene"))

(defn signalr-connection-setting-name []
  (config/setting "ROBOGENE_SIGNALR_CONNECTION_SETTING" "AzureSignalRConnectionString"))

(defn signalr-client-token-ttl-seconds []
  (config/parse-int (config/setting "ROBOGENE_SIGNALR_CLIENT_TOKEN_TTL_SECONDS") 3600))

(defn signalr-connection-string []
  (config/setting (signalr-connection-setting-name)))

(defn storage-connection-string []
  (or (config/setting "ROBOGENE_STORAGE_CONNECTION_STRING")
      (config/setting "AzureWebJobsStorage")))

(defn allow-dev-storage-for-smoke? []
  (= "1" (or (config/setting "ROBOGENE_ALLOW_DEV_STORAGE_FOR_SMOKE") "")))
