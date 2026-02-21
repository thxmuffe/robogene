(ns robogene.backend.azure-store
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            ["@azure/data-tables" :refer [TableClient]]
            ["@azure/storage-blob" :refer [BlobServiceClient]]))

(def connection-string
  (or (.. js/process -env -ROBOGENE_STORAGE_CONNECTION_STRING)
      (.. js/process -env -AzureWebJobsStorage)))

(when-not (seq connection-string)
  (throw (js/Error. "Missing AzureWebJobsStorage or ROBOGENE_STORAGE_CONNECTION_STRING.")))

(when (= (str/trim connection-string) "UseDevelopmentStorage=true")
  (throw (js/Error.
          "UseDevelopmentStorage=true requires Azurite on 127.0.0.1:10000. Configure a real Azure Storage connection string.")))

(def table-meta (or (.. js/process -env -ROBOGENE_TABLE_META) "robogeneMeta"))
(def table-episodes (or (.. js/process -env -ROBOGENE_TABLE_EPISODES) "robogeneEpisodes"))
(def table-frames (or (.. js/process -env -ROBOGENE_TABLE_FRAMES) "robogeneFrames"))
(def container-name (or (.. js/process -env -ROBOGENE_IMAGE_CONTAINER) "robogene-images"))

(def client-options
  #js {:retryOptions
       #js {:maxTries 2
            :tryTimeoutInMs 5000}})

(def meta-client (.fromConnectionString TableClient connection-string table-meta client-options))
(def episodes-client (.fromConnectionString TableClient connection-string table-episodes client-options))
(def frames-client (.fromConnectionString TableClient connection-string table-frames client-options))
(def blob-service (.fromConnectionString BlobServiceClient connection-string client-options))
(def image-container (.getContainerClient blob-service container-name))

(defonce ensured? (atom false))

(defn parse-json [value fallback]
  (if (seq value)
    (try
      (.parse js/JSON value)
      (catch :default _
        fallback))
    fallback))

(defn normalize-image-path [story-id episode-id frame-id]
  (str "stories/" story-id "/episodes/" episode-id "/frames/" frame-id ".png"))

(defn reduce-promise [items step init]
  (reduce (fn [p item]
            (.then p (fn [acc] (step acc item))))
          (js/Promise.resolve init)
          items))

(defn list-entities [client partition-key]
  (let [list-entities-fn (gobj/get client "listEntities")
        iterable (.call list-entities-fn client #js {:queryOptions
                                                     #js {:filter (str "PartitionKey eq '" partition-key "'")}})
        iterator ((aget iterable (.-asyncIterator js/Symbol)))
        out (array)]
    (letfn [(step []
              (-> (.next iterator)
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
    (-> (js/Promise.all
         #js [(.catch (.createTable meta-client) (fn [_] nil))
              (.catch (.createTable episodes-client) (fn [_] nil))
              (.catch (.createTable frames-client) (fn [_] nil))])
        (.then (fn [_] (.createIfNotExists image-container)))
        (.then (fn [_]
                 (reset! ensured? true)
                 true)))))

(defn to-readable-image-url [image-path]
  (if-not (seq image-path)
    (js/Promise.resolve "")
    (let [blob (.getBlockBlobClient image-container image-path)
          generate-sas (gobj/get blob "generateSasUrl")]
      (if-not (fn? generate-sas)
        (js/Promise.resolve (.-url blob))
        (-> (.call generate-sas blob
                  #js {:permissions "r"
                       :startsOn (js/Date. (- (.now js/Date) (* 5 60 1000)))
                       :expiresOn (js/Date. (+ (.now js/Date) (* 365 24 60 60 1000 1000)))})
            (.catch (fn [_] (.-url blob))))))))

(defn upload-data-url-if-needed [story-id frame]
  (let [data (or (gobj/get frame "imageDataUrl") "")]
    (if-not (str/starts-with? data "data:image/png;base64,")
      (js/Promise.resolve frame)
      (let [episode-id (gobj/get frame "episodeId")
            frame-id (gobj/get frame "frameId")
            image-path (normalize-image-path story-id episode-id frame-id)
            blob (.getBlockBlobClient image-container image-path)
            content (js/Buffer.from (subs data (count "data:image/png;base64,")) "base64")]
        (-> (.uploadData blob content #js {:blobHTTPHeaders #js {:blobContentType "image/png"}})
            (.then (fn [_] (to-readable-image-url image-path)))
            (.then (fn [image-url]
                     (doto (js-obj)
                       (gobj/set "frameId" frame-id)
                       (gobj/set "episodeId" episode-id)
                       (gobj/set "frameNumber" (gobj/get frame "frameNumber"))
                       (gobj/set "description" (gobj/get frame "description"))
                       (gobj/set "status" (gobj/get frame "status"))
                       (gobj/set "createdAt" (gobj/get frame "createdAt"))
                       (gobj/set "startedAt" (gobj/get frame "startedAt"))
                       (gobj/set "completedAt" (gobj/get frame "completedAt"))
                       (gobj/set "error" (gobj/get frame "error"))
                       (gobj/set "reference" (gobj/get frame "reference"))
                       (gobj/set "imagePath" image-path)
                       (gobj/set "imageDataUrl" image-url)))))))))

(defn get-active-meta []
  (-> (.getEntity meta-client "meta" "active")
      (.catch (fn [_] nil))))

(defn set-active-meta! [payload]
  (.upsertEntity meta-client
                 #js {:partitionKey "meta"
                      :rowKey "active"
                      :storyId (gobj/get payload "storyId")
                      :revision (js/Number (or (gobj/get payload "revision") 0))
                      :failedJobsJson (.stringify js/JSON (or (gobj/get payload "failedJobs") #js []))}
                 "Replace"))

(defn save-episodes! [story-id episodes]
  (-> (list-entities episodes-client story-id)
      (.then (fn [existing]
               (let [keep (set (map #(gobj/get % "episodeId") episodes))]
                 (-> (reduce-promise episodes
                                     (fn [_ episode]
                                       (.upsertEntity episodes-client
                                                      #js {:partitionKey story-id
                                                           :rowKey (gobj/get episode "episodeId")
                                                           :payloadJson (.stringify js/JSON episode)}
                                                      "Replace"))
                                     nil)
                     (.then (fn [_]
                              (reduce-promise existing
                                              (fn [_ row]
                                                (if (contains? keep (gobj/get row "rowKey"))
                                                  (js/Promise.resolve nil)
                                                  (.catch (.deleteEntity episodes-client story-id (gobj/get row "rowKey"))
                                                          (fn [_] nil))))
                                              nil)))))))))

(defn save-frames! [story-id frames]
  (-> (list-entities frames-client story-id)
      (.then (fn [existing]
               (let [keep (set (map #(gobj/get % "frameId") frames))]
                 (-> (reduce-promise frames
                                     (fn [normalized frame]
                                       (-> (upload-data-url-if-needed story-id frame)
                                           (.then (fn [f]
                                                    (-> (.upsertEntity frames-client
                                                                       #js {:partitionKey story-id
                                                                            :rowKey (gobj/get f "frameId")
                                                                            :episodeId (gobj/get f "episodeId")
                                                                            :payloadJson (.stringify js/JSON f)}
                                                                       "Replace")
                                                        (.then (fn [_]
                                                                 (conj normalized f))))))))
                                     [])
                     (.then (fn [normalized]
                              (-> (reduce-promise existing
                                                  (fn [_ row]
                                                    (if (contains? keep (gobj/get row "rowKey"))
                                                      (js/Promise.resolve nil)
                                                      (let [payload (parse-json (gobj/get row "payloadJson") nil)
                                                            image-path (when payload (gobj/get payload "imagePath"))]
                                                        (-> (if (seq image-path)
                                                              (.catch (.deleteBlob image-container image-path) (fn [_] nil))
                                                              (js/Promise.resolve nil))
                                                            (.then (fn [_]
                                                                     (.catch (.deleteEntity frames-client story-id (gobj/get row "rowKey"))
                                                                             (fn [_] nil))))))))
                                                  nil)
                                  (.then (fn [_] normalized)))))))))))

(defn load-rows [story-id]
  (-> (js/Promise.all #js [(list-entities episodes-client story-id)
                           (list-entities frames-client story-id)])
      (.then (fn [pairs]
               (let [episode-rows (aget pairs 0)
                     frame-rows (aget pairs 1)]
                 (-> (reduce-promise frame-rows
                                     (fn [acc row]
                                       (let [frame (parse-json (gobj/get row "payloadJson") nil)]
                                         (if-not frame
                                           (js/Promise.resolve acc)
                                           (if-let [image-path (gobj/get frame "imagePath")]
                                             (-> (to-readable-image-url image-path)
                                                 (.then (fn [url]
                                                          (gobj/set frame "imageDataUrl" url)
                                                          (conj acc frame))))
                                             (js/Promise.resolve (conj acc frame))))))
                                     [])
                     (.then (fn [frames]
                              #js {:episodes (->> episode-rows
                                                  (map #(parse-json (gobj/get % "payloadJson") nil))
                                                  (filter some?)
                                                  clj->js)
                                   :frames (clj->js frames)}))))))))

(defn load-or-init-state [initial-state]
  (-> (ensure!)
      (.then (fn [_] (get-active-meta)))
      (.then
       (fn [meta]
         (if-not meta
           (-> (set-active-meta! #js {:storyId (gobj/get initial-state "storyId")
                                      :revision (or (gobj/get initial-state "revision") 1)
                                      :failedJobs (or (gobj/get initial-state "failedJobs") #js [])})
               (.then (fn [_]
                        (save-episodes! (gobj/get initial-state "storyId")
                                        (or (gobj/get initial-state "episodes") #js []))))
               (.then (fn [_]
                        (save-frames! (gobj/get initial-state "storyId")
                                      (or (gobj/get initial-state "frames") #js []))))
               (.then (fn [frames]
                        (gobj/set initial-state "frames" (clj->js frames))
                        initial-state)))
           (let [story-id (gobj/get meta "storyId")
                 revision (js/Number (or (gobj/get meta "revision") 1))
                 failed-jobs (or (parse-json (gobj/get meta "failedJobsJson") #js []) #js [])]
             (-> (load-rows story-id)
                 (.then
                  (fn [rows]
                    (doto (js-obj)
                      (gobj/set "storyId" story-id)
                      (gobj/set "revision" revision)
                      (gobj/set "failedJobs" failed-jobs)
                      (gobj/set "episodes" (gobj/get rows "episodes"))
                      (gobj/set "frames" (gobj/get rows "frames"))
                      (gobj/set "descriptions" (gobj/get initial-state "descriptions"))
                      (gobj/set "visual" (gobj/get initial-state "visual"))
                      (gobj/set "model" (gobj/get initial-state "model"))
                      (gobj/set "quality" (gobj/get initial-state "quality"))
                      (gobj/set "size" (gobj/get initial-state "size"))))))))))))

(defn save-state [state]
  (let [story-id (gobj/get state "storyId")]
    (-> (ensure!)
        (.then (fn [_]
                 (save-frames! story-id (or (gobj/get state "frames") #js []))))
        (.then
         (fn [frames]
           (-> (save-episodes! story-id (or (gobj/get state "episodes") #js []))
               (.then (fn [_]
                        (set-active-meta! #js {:storyId story-id
                                               :revision (or (gobj/get state "revision") 1)
                                               :failedJobs (or (gobj/get state "failedJobs") #js [])})))
               (.then (fn [_]
                        (gobj/set state "frames" (clj->js frames))
                        state))))))))
