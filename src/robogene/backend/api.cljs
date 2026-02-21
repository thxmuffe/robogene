(ns robogene.backend.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [robogene.backend.story :as story]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn allowed-origins []
  (let [raw (or (.. js/process -env -ROBOGENE_ALLOWED_ORIGIN)
                "https://thxmuffe.github.io,http://localhost:8080,http://127.0.0.1:8080,http://localhost:5500,http://127.0.0.1:5500,http://localhost:3000,http://127.0.0.1:3000,http://localhost:5173,http://127.0.0.1:5173")]
    (->> (str/split raw #",")
         (map str/trim)
         (filter seq)
         vec)))

(defn request-origin [request]
  (or (some-> request .-headers (.get "origin"))
      (some-> request .-headers (.get "Origin"))))

(defn cors-origin [request]
  (let [origins (allowed-origins)
        req-origin (request-origin request)]
    (cond
      (and (seq req-origin) (some #(= % req-origin) origins)) req-origin
      (seq origins) (first origins)
      :else "*")))

(defn cors-headers [request]
  (clj->js
   {"Content-Type" "application/json"
    "Access-Control-Allow-Origin" (cors-origin request)
    "Access-Control-Allow-Methods" "GET,POST,OPTIONS"
    "Access-Control-Allow-Headers" "Content-Type,Authorization"
    "Cache-Control" "no-store, no-cache, must-revalidate, proxy-revalidate"
    "Vary" "Origin"
    "Pragma" "no-cache"
    "Expires" "0"}))

(defn json-response [status data request]
  #js {:status status
       :jsonBody (clj->js data)
       :headers (cors-headers request)})

(defn error-message [err]
  (or (some-> err .-message str)
      (when (string? err) err)
      "Unknown internal error."))

(defn error-stack [err]
  (when-let [stack (some-> err .-stack)]
    (str stack)))

(defn internal-error-response [request route err]
  (let [message (error-message err)]
    (js/console.error (str "[robogene] handler failure route=" route " message=" message) err)
    (json-response 500
                   {:error "Internal server error."
                    :route route
                    :message message
                    :stack (error-stack err)}
                   request)))

(defn promise-like? [value]
  (fn? (some-> value (gobj/get "then"))))

(defn with-error-handling [route handler]
  (fn [request]
    (try
      (let [result (handler request)]
        (if (promise-like? result)
          (.catch result (fn [err] (internal-error-response request route err)))
          result))
      (catch :default err
        (internal-error-response request route err)))))

(defn request-json [request]
  (-> (.json request)
      (.catch (fn [_] #js {}))))

(.http app "get-state"
       #js {:methods #js ["GET"]
            :authLevel "anonymous"
            :route "state"
            :handler (with-error-handling
                      "state"
                      (fn [request]
                        (-> (story/sync-state-from-storage!)
                            (.then (fn [_]
                                     (let [before-revision (:revision @story/state)]
                                       (story/ensure-draft-frames!)
                                       (if (not= before-revision (:revision @story/state))
                                         (story/persist-state!)
                                         (js/Promise.resolve @story/state)))))
                            (.then (fn [_]
                                     (let [snapshot @story/state
                                           frames (:frames snapshot)
                                           pending-count (story/active-queue-count frames)]
                                       (json-response 200
                                                      {:storyId (:storyId snapshot)
                                                       :revision (:revision snapshot)
                                                       :processing (:processing snapshot)
                                                       :pendingCount pending-count
                                                       :episodes (:episodes snapshot)
                                                       :frames frames
                                                       :failed (:failedJobs snapshot)}
                                                      request)))))))})

(.http app "post-generate-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "generate-frame"
            :handler (with-error-handling
                      "generate-frame"
                      (fn [request]
                        (-> (story/sync-state-from-storage!)
                            (.then (fn [_] (request-json request)))
                            (.then
                             (fn [body]
                               (let [frame-id (some-> (gobj/get body "frameId") str str/trim)
                                     direction (some-> (gobj/get body "direction") str str/trim)]
                                 (if (str/blank? frame-id)
                                   (json-response 400 {:error "Missing frameId."} request)
                                   (let [snapshot @story/state
                                         frames (:frames snapshot)
                                         idx (story/find-frame-index frames frame-id)]
                                     (cond
                                       (nil? idx)
                                       (json-response 404 {:error "Frame not found."} request)

                                       (or (= "queued" (:status (get frames idx)))
                                           (= "processing" (:status (get frames idx))))
                                       (json-response 409 {:error "Frame already in queue."} request)

                                       :else
                                       (do
                                         (swap! story/state
                                                (fn [s]
                                                  (-> s
                                                      (assoc-in [:frames idx :status] "queued")
                                                      (assoc-in [:frames idx :queuedAt] (.toISOString (js/Date.)))
                                                      (assoc-in [:frames idx :error] nil)
                                                      (assoc-in [:frames idx :description]
                                                                (if (str/blank? (or direction ""))
                                                                  (or (get-in s [:frames idx :description]) "")
                                                                  direction))
                                                      (update :revision inc))))
                                         (-> (story/persist-state!)
                                             (.then (fn [_]
                                                      (story/emit-state-changed! "queued")
                                                      (story/process-queue!)
                                                      (let [post @story/state]
                                                        (json-response 202
                                                                       {:accepted true
                                                                        :frame (get (:frames post) idx)
                                                                        :revision (:revision post)
                                                                        :pendingCount (story/active-queue-count (:frames post))}
                                                                       request)))))))))))))))} )

(.http app "post-add-episode"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "add-episode"
            :handler (with-error-handling
                      "add-episode"
                      (fn [request]
                        (-> (story/sync-state-from-storage!)
                            (.then (fn [_] (request-json request)))
                            (.then
                             (fn [body]
                               (let [description (some-> (gobj/get body "description") str str/trim)
                                     {:keys [episode frame]} (story/add-episode! description)
                                     _ (story/emit-state-changed! "episode-added")]
                                 (-> (story/persist-state!)
                                     (.then (fn [_]
                                              (let [snapshot @story/state]
                                                (json-response 201
                                                               {:created true
                                                                :episode episode
                                                                :frame frame
                                                                :revision (:revision snapshot)}
                                                               request)))))))))))})

(defn handle-add-frame [request]
  (-> (story/sync-state-from-storage!)
      (.then (fn [_] (request-json request)))
      (.then (fn [body]
               (let [episode-id (some-> (gobj/get body "episodeId") str str/trim)]
                 (if (str/blank? episode-id)
                   (json-response 400 {:error "Missing episodeId."} request)
                   (let [outcome (try
                                   {:ok true :frame (story/add-frame! episode-id)}
                                   (catch :default err
                                     {:ok false
                                      :response (json-response 404
                                                               {:error (or (some-> err .-message str) "Episode not found.")}
                                                               request)}))]
                     (if-not (:ok outcome)
                       (:response outcome)
                       (let [frame (:frame outcome)]
                         (story/emit-state-changed! "frame-added")
                         (-> (story/persist-state!)
                             (.then (fn [_]
                                      (let [snapshot @story/state]
                                        (json-response 201
                                                       {:created true
                                                        :frame frame
                                                        :revision (:revision snapshot)}
                                                       request)))))))))))))
  )

(defn handle-delete-frame [request]
  (-> (story/sync-state-from-storage!)
      (.then (fn [_] (request-json request)))
      (.then (fn [body]
               (let [frame-id (some-> (gobj/get body "frameId") str str/trim)]
                 (if (str/blank? frame-id)
                   (json-response 400 {:error "Missing frameId."} request)
                   (let [outcome (try
                                   {:ok true :frame (story/delete-frame! frame-id)}
                                   (catch :default err
                                     (let [msg (or (some-> err .-message str) "Delete failed.")
                                           status (if (or (= msg "Frame not found.")
                                                          (= msg "Cannot delete frame while queued or processing."))
                                                    409
                                                    500)]
                                       {:ok false
                                        :response (json-response status {:error msg} request)})))]
                     (if-not (:ok outcome)
                       (:response outcome)
                       (let [deleted-frame (:frame outcome)]
                         (story/emit-state-changed! "frame-deleted")
                         (-> (story/persist-state!)
                             (.then (fn [_]
                                      (let [snapshot @story/state]
                                        (json-response 200
                                                       {:deleted true
                                                        :frame deleted-frame
                                                        :revision (:revision snapshot)}
                                                       request)))))))))))))
  )

(defn handle-clear-frame-image [request]
  (-> (story/sync-state-from-storage!)
      (.then (fn [_] (request-json request)))
      (.then (fn [body]
               (let [frame-id (some-> (gobj/get body "frameId") str str/trim)]
                 (if (str/blank? frame-id)
                   (json-response 400 {:error "Missing frameId."} request)
                   (let [outcome (try
                                   {:ok true :frame (story/clear-frame-image! frame-id)}
                                   (catch :default err
                                     (let [msg (or (some-> err .-message str) "Clear image failed.")
                                           status (if (or (= msg "Frame not found.")
                                                          (= msg "Cannot clear image while queued or processing."))
                                                    409
                                                    500)]
                                       {:ok false
                                        :response (json-response status {:error msg} request)})))]
                     (if-not (:ok outcome)
                       (:response outcome)
                       (let [frame (:frame outcome)]
                         (story/emit-state-changed! "frame-image-cleared")
                         (-> (story/persist-state!)
                             (.then (fn [_]
                                      (let [snapshot @story/state]
                                        (json-response 200
                                                       {:cleared true
                                                       :frame frame
                                                        :revision (:revision snapshot)}
                                                       request)))))))))))))
  )

(.http app "post-add-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "add-frame"
            :handler (with-error-handling "add-frame" handle-add-frame)})

(.http app "post-delete-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "delete-frame"
            :handler (with-error-handling "delete-frame" handle-delete-frame)})

(.http app "post-clear-frame-image"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "clear-frame-image"
            :handler (with-error-handling "clear-frame-image" handle-clear-frame-image)})

(.http app "options-preflight"
       #js {:methods #js ["OPTIONS"]
            :authLevel "anonymous"
            :route "{*path}"
            :handler (with-error-handling
                      "options-preflight"
                      (fn [request]
                        #js {:status 204
                             :headers (cors-headers request)}))})

(defn init! [] true)
