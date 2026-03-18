(ns services.azure-store
  (:require [clojure.string :as str]
            [host.settings :as settings]
            [goog.object :as gobj]
            ["@azure/data-tables" :refer [TableClient]]
            ["@azure/storage-blob" :refer [BlobServiceClient]]))

(def connection-string
  (settings/storage-connection-string))

(when-not (seq connection-string)
  (throw (js/Error. "Missing AzureWebJobsStorage or ROBOGENE_STORAGE_CONNECTION_STRING.")))

(def smoke-dev-storage?
  (and (= (str/trim connection-string) "UseDevelopmentStorage=true")
       (settings/allow-dev-storage-for-smoke?)))

(when (and (= (str/trim connection-string) "UseDevelopmentStorage=true")
           (not (settings/allow-dev-storage-for-smoke?)))
  (throw (js/Error.
          "UseDevelopmentStorage=true requires Azurite on 127.0.0.1:10000. Configure a real Azure Storage connection string. For CI packaging smoke checks only, set ROBOGENE_ALLOW_DEV_STORAGE_FOR_SMOKE=1.")))

(def table-entities "robogeneEntities")
(def container-name "robogene-images")

(def client-options
  #js {:retryOptions
       #js {:maxTries 2
            :tryTimeoutInMs 5000}})

(def entities-client (.fromConnectionString TableClient connection-string table-entities client-options))
(def blob-service (.fromConnectionString BlobServiceClient connection-string client-options))
(def image-container (.getContainerClient blob-service container-name))

(defonce ensured? (atom false))
(defonce smoke-state* (atom nil))
(defonce image-url-cache* (atom {}))

(defn parse-json [value fallback]
  (if (seq value)
    (try
      (js->clj (.parse js/JSON value) :keywordize-keys true)
      (catch :default _
        fallback))
    fallback))

(defn normalize-image-path [workspace-id entity-id extension]
  (str "workspaces/" workspace-id "/entities/" entity-id "/image." (or extension "png")))

(defn reduce-promise [items step init]
  (reduce (fn [p item]
            (.then p (fn [acc] (step acc item))))
          (js/Promise.resolve init)
          items))

(defn list-entities [partition-key]
  (let [list-entities-fn (gobj/get entities-client "listEntities")
        iterable (.call list-entities-fn entities-client #js {:queryOptions
                                                              #js {:filter (str "PartitionKey eq '" partition-key "'")}})
        out (array)]
    (letfn [(step []
              (-> (.next iterable)
                  (.then (fn [res]
                           (if (.-done res)
                             (vec out)
                             (do
                               (.push out (.-value res))
                               (step)))))))]
      (step))))

(defn ensure! []
  (if @ensured?
    (js/Promise.resolve true)
    (-> (.catch (.createTable entities-client) (fn [_] nil))
        (.then (fn [_] (.createIfNotExists image-container)))
        (.then (fn [_]
                 (reset! ensured? true)
                 true)))))

(defn now-ms []
  (.now js/Date))

(defn cache-valid? [{:keys [expires-at]}]
  (and (number? expires-at)
       (> expires-at (+ (now-ms) (* 5 60 1000)))))

(defn cache-get-url [image-path]
  (when-let [entry (get @image-url-cache* image-path)]
    (when (cache-valid? entry)
      (:url entry))))

(defn cache-put-url! [image-path url expires-at]
  (swap! image-url-cache* assoc image-path {:url url :expires-at expires-at})
  url)

(defn generate-sas-url [blob]
  (let [generate-sas (gobj/get blob "generateSasUrl")]
    (if-not (fn? generate-sas)
      (js/Promise.resolve nil)
      (let [starts-on (js/Date. (- (now-ms) (* 5 60 1000)))
            expires-at (+ (now-ms) (* 6 60 60 1000))
            expires-on (js/Date. expires-at)]
        (-> (.call generate-sas blob
                   #js {:permissions "r"
                        :startsOn starts-on
                        :expiresOn expires-on})
            (.then (fn [url]
                     {:url url :expires-at expires-at}))
            (.catch (fn [_] nil)))))))

(defn set-cached-image-url! [image-path]
  (let [blob (.getBlockBlobClient image-container image-path)]
    (-> (generate-sas-url blob)
        (.then (fn [sas]
                 (if-let [url (:url sas)]
                   (cache-put-url! image-path url (:expires-at sas))
                   (cache-put-url! image-path (.-url blob) js/Number.MAX_SAFE_INTEGER)))))))

(defn to-readable-image-url [image-path]
  (if-not (seq image-path)
    (js/Promise.resolve "")
    (if-let [cached-url (cache-get-url image-path)]
      (js/Promise.resolve cached-url)
      (set-cached-image-url! image-path))))

(defn parse-image-data-url [data]
  (when-let [[_ mime-type payload] (re-matches #"^data:(image/[^;]+);base64,(.+)$" (or data ""))]
    {:mime-type mime-type
     :payload payload}))

(defn mime-type->extension [mime-type]
  (case (str/lower-case (or mime-type ""))
    "image/jpeg" "jpg"
    "image/jpg" "jpg"
    "image/png" "png"
    "image/webp" "webp"
    "image/gif" "gif"
    "image/svg+xml" "svg"
    "image/avif" "avif"
    "image/bmp" "bmp"
    "bin"))

(defn upload-image-if-needed [workspace-id entity-id image-data]
  (if-let [{:keys [mime-type payload]} (parse-image-data-url image-data)]
    (let [image-path (normalize-image-path workspace-id entity-id (mime-type->extension mime-type))
          blob (.getBlockBlobClient image-container image-path)
          content (js/Buffer.from payload "base64")]
      (-> (.uploadData blob content #js {:blobHTTPHeaders #js {:blobContentType mime-type}})
          (.then (fn [_] (set-cached-image-url! image-path)))
          (.then (fn [image-url]
                   {:imagePath image-path
                    :imageUrl image-url}))))
    (js/Promise.resolve nil)))

(defn save-entity! [workspace-id entity]
  (let [id (or (:id entity) (:entityId entity) (:frameId entity) (:chapterId entity) (:sagaId entity) (:rosterId entity) (:characterId entity))
        vanity-role (or (:vanityRole entity)
                        (cond
                          (:frameId entity) "frame"
                          (:chapterId entity) "chapter"
                          (:sagaId entity) "saga"
                          (:rosterId entity) "roster"
                          (:characterId entity) "character"
                          :else "item"))
        image-data (or (:imageUrl entity) (:imageDataUrl entity))
        entity* (assoc entity :vanityRole vanity-role :id id)]
    (-> (if (and (= vanity-role "frame") (seq image-data) (str/starts-with? image-data "data:"))
          (-> (upload-image-if-needed workspace-id id image-data)
              (.then (fn [image-info]
                       (if image-info
                         (merge entity* image-info)
                         entity*))))
          (js/Promise.resolve entity*))
        (.then (fn [final-entity]
                 (.upsertEntity entities-client
                                #js {:partitionKey workspace-id
                                     :rowKey (str id)
                                     :vanityRole vanity-role
                                     :payloadJson (.stringify js/JSON (clj->js final-entity))}
                                "Replace"))))))

(defn delete-entity! [workspace-id entity-id]
  (.catch (.deleteEntity entities-client workspace-id (str entity-id))
          (fn [_] nil)))

(defn load-entities [workspace-id]
  ;; Ensure backing table/container exists before listing to avoid TableNotFound on fresh dev storage.
  (-> (ensure!)
      (.then (fn [_] (list-entities workspace-id)))
      (.then (fn [rows]
               (reduce-promise rows
                               (fn [acc row]
                                 (let [payload (parse-json (gobj/get row "payloadJson") {})
                                       image-path (get payload :imagePath)]
                                   (if (seq image-path)
                                     (-> (to-readable-image-url image-path)
                                         (.then (fn [url]
                                                  (conj acc (assoc payload :imageUrl url)))))
                                     (js/Promise.resolve (conj acc payload)))))
                               [])))))

;; Legacy adapters for load-or-init-state and save-state
(defn load-or-init-state [initial-state]
  (let [workspace-id (or (gobj/get initial-state "chapterId") "default")]
    (if smoke-dev-storage?
      (let [current @smoke-state*]
        (if current
          (js/Promise.resolve current)
          (do (reset! smoke-state* initial-state) (js/Promise.resolve initial-state))))
      (-> (ensure!)
          (.then (fn [_] (load-entities workspace-id)))
          (.then (fn [entities]
                   (if (empty? entities)
                     ;; Initialize from initial-state
                     (let [all-entities (concat (js->clj (or (gobj/get initial-state "sagas") #js []) :keywordize-keys true)
                                                (js->clj (or (gobj/get initial-state "rosters") #js []) :keywordize-keys true)
                                                (js->clj (or (gobj/get initial-state "saga") #js []) :keywordize-keys true)
                                                (js->clj (or (gobj/get initial-state "roster") #js []) :keywordize-keys true)
                                                (js->clj (or (gobj/get initial-state "frames") #js []) :keywordize-keys true))]
                       (-> (reduce-promise all-entities
                                           (fn [_ e] (save-entity! workspace-id e))
                                           nil)
                           (.then (fn [_] initial-state))))
                     (let [sagas (filter #(= (:vanityRole %) "saga") entities)
                           rosters (filter #(= (:vanityRole %) "roster") entities)
                           chapters (filter #(= (:vanityRole %) "chapter") entities)
                           characters (filter #(= (:vanityRole %) "character") entities)
                           frames (filter #(= (:vanityRole %) "frame") entities)]
                       (doto (js-obj)
                         (gobj/set "chapterId" workspace-id)
                         (gobj/set "revision" 1)
                         (gobj/set "sagas" (clj->js sagas))
                         (gobj/set "rosters" (clj->js rosters))
                         (gobj/set "saga" (clj->js chapters))
                         (gobj/set "roster" (clj->js characters))
                         (gobj/set "frames" (clj->js frames)))))))))))

(defn save-state [state]
  (let [workspace-id (or (gobj/get state "chapterId") "default")]
    (if smoke-dev-storage?
      (do (reset! smoke-state* state) (js/Promise.resolve state))
      (let [all-entities (concat (js->clj (or (gobj/get state "sagas") #js []) :keywordize-keys true)
                                 (js->clj (or (gobj/get state "rosters") #js []) :keywordize-keys true)
                                 (js->clj (or (gobj/get state "saga") #js []) :keywordize-keys true)
                                 (js->clj (or (gobj/get state "roster") #js []) :keywordize-keys true)
                                 (js->clj (or (gobj/get state "frames") #js []) :keywordize-keys true))]
        (-> (ensure!)
            (.then (fn [_]
                     (reduce-promise all-entities
                                     (fn [_ e] (save-entity! workspace-id e))
                                     nil)))
            (.then (fn [_] state)))))))
