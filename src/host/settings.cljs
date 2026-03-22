(ns host.settings
  (:require [clojure.string :as str]
            [host.config :as config]))

(defn- normalize-generator-entry [entry]
  (let [name (some-> (or (:name entry) (get entry "name")) str str/lower-case str/trim not-empty)
        api-key-env (some-> (or (:apiKeyEnv entry) (get entry "apiKeyEnv")) str str/trim not-empty)
        api-key (some-> api-key-env config/setting str str/trim not-empty)
        data-url (some-> (or (:dataUrl entry) (get entry "dataUrl")) str str/trim not-empty)]
    (when name
      (cond-> {:name name}
        api-key-env (assoc :apiKeyEnv api-key-env)
        api-key (assoc :apiKey api-key)
        data-url (assoc :dataUrl data-url)
        (contains? entry :model) (assoc :model (:model entry))
        (contains? entry "model") (assoc :model (get entry "model"))
        (contains? entry :quality) (assoc :quality (:quality entry))
        (contains? entry "quality") (assoc :quality (get entry "quality"))
        (contains? entry :size) (assoc :size (:size entry))
        (contains? entry "size") (assoc :size (get entry "size"))))))

(defn image-generators []
  (let [raw (config/setting "IMAGE_GENERATORS" [])
        entries (if (sequential? raw) raw [])]
    (->> entries
         (keep normalize-generator-entry)
         (filter (fn [{:keys [name apiKeyEnv apiKey]}]
                   (or (= name "mock")
                       (nil? apiKeyEnv)
                       (seq apiKey))))
         vec)))

(defn image-generator-ids []
  (mapv :name (image-generators)))

(defn image-generator-config [provider-id]
  (some (fn [entry]
          (when (= (:name entry) (some-> provider-id str str/lower-case str/trim))
            entry))
        (image-generators)))

(defn default-image-generator []
  (let [selected (some-> (config/setting "ROBOGENE_IMAGE_GENERATOR") str str/lower-case str/trim not-empty)]
    (when (some #(= % selected) (image-generator-ids))
      selected)))

(defn mock-data-url []
  (or (some-> (image-generator-config "mock") :dataUrl)
      (throw (js/Error. "Mock image generator requires IMAGE_GENERATORS[].dataUrl."))))

(defn mock-delay-ms []
  (config/parse-int (config/setting "ROBOGENE_IMAGE_GENERATOR_MOCK_DELAY_MS") 0))

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

(defn workspace-id []
  (or (some-> (config/setting "ROBOGENE_WORKSPACE_ID") str/trim not-empty)
      "default"))
