(ns robogene.backend.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [robogene.backend.story :as story]
            [robogene.backend.realtime :as realtime]
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

(defn register-http! [name methods route route-name handler]
  (.http app name
         #js {:methods (clj->js methods)
              :authLevel "anonymous"
              :route route
              :handler (with-error-handling route-name handler)}))

(defn register-get! [name route handler]
  (register-http! name ["GET"] route route handler))

(defn register-post! [name route handler]
  (register-http! name ["POST"] route route handler))

(defn register-options! [name route handler]
  (register-http! name ["OPTIONS"] route route handler))

(defn request-json [request]
  (-> (.json request)
      (.catch (fn [_] #js {}))))

(defn with-synced-body [request handler]
  (-> (story/sync-state-from-storage!)
      (.then (fn [_] (request-json request)))
      (.then handler)))

(defn with-required-string [request body field-key missing-msg handler]
  (let [value (some-> (gobj/get body field-key) str str/trim)]
    (if (str/blank? value)
      (json-response 400 {:error missing-msg} request)
      (handler value))))

(defn queueable-frame-outcome [frame-id]
  (let [snapshot @story/state
        frames (:frames snapshot)
        idx (story/find-frame-index frames frame-id)]
    (cond
      (nil? idx)
      {:ok false :status 404 :error "Frame not found."}

      (or (= "queued" (:status (get frames idx)))
          (= "processing" (:status (get frames idx))))
      {:ok false :status 409 :error "Frame already in queue."}

      :else
      {:ok true :idx idx :frames frames})))

(defn queue-frame! [idx direction]
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
               (update :revision inc)))))

(defn queue-success-response [request idx]
  (let [post @story/state]
    (json-response 202
                   {:accepted true
                    :frame (get (:frames post) idx)
                    :revision (:revision post)
                    :pendingCount (story/active-queue-count (:frames post))}
                   request)))

(defn handle-get-state [request]
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
                                request))))))

(defn handle-generate-frame [request]
  (with-synced-body
   request
   (fn [body]
     (with-required-string
      request body "frameId" "Missing frameId."
      (fn [frame-id]
        (let [direction (some-> (gobj/get body "direction") str str/trim)
              outcome (queueable-frame-outcome frame-id)]
          (if-not (:ok outcome)
            (json-response (:status outcome) {:error (:error outcome)} request)
            (do
              (queue-frame! (:idx outcome) direction)
              (-> (story/persist-state!)
                  (.then (fn [_]
                           (story/emit-state-changed! "queued")
                           (story/process-queue!)
                           (queue-success-response request (:idx outcome)))))))))))))

(defn emit-persist-and-respond [request emit-reason status response-body]
  (story/emit-state-changed! emit-reason)
  (-> (story/persist-state!)
      (.then (fn [_]
               (let [snapshot @story/state]
                 (json-response status (response-body snapshot) request))))))

(defn with-revision [body snapshot]
  (assoc body :revision (:revision snapshot)))

(defn handle-add-episode [request]
  (with-synced-body
   request
   (fn [body]
     (let [raw-description (gobj/get body "description")
           description (if (some? raw-description)
                         (some-> raw-description str str/trim)
                         nil)
           {:keys [episode frame]} (story/add-episode! description)]
       (emit-persist-and-respond
        request
        "episode-added"
        201
        (fn [snapshot]
          (with-revision {:created true
                          :episode episode
                          :frame frame}
                         snapshot))))))

(defn run-mutation [request {:keys [mutate! default-error status-by-message on-success]}]
  (let [outcome (try
                  {:ok true :value (mutate!)}
                  (catch :default err
                    (let [msg (or (some-> err .-message str) default-error)
                          status (get status-by-message msg 500)]
                      {:ok false
                       :response (json-response status {:error msg} request)})))]
    (if (:ok outcome)
      (on-success (:value outcome))
      (:response outcome))))

(defn handle-add-frame [request]
  (with-synced-body
   request
   (fn [body]
     (with-required-string
      request body "episodeId" "Missing episodeId."
      (fn [episode-id]
        (run-mutation
         request
         {:mutate! #(story/add-frame! episode-id)
          :default-error "Episode not found."
          :status-by-message {"Episode not found." 404}
          :on-success (fn [frame]
                         (emit-persist-and-respond
                          request
                          "frame-added"
                          201
                          (fn [snapshot]
                            (with-revision {:created true
                                            :frame frame}
                                           snapshot))))})))))))

(defn run-frame-mutation [request frame-id {:keys [mutate! default-error conflict-messages emit-reason success-status success-body]}]
  (run-mutation
   request
   {:mutate! #(mutate! frame-id)
    :default-error default-error
    :status-by-message (into {} (map (fn [msg] [msg 409]) conflict-messages))
    :on-success (fn [frame]
        (emit-persist-and-respond
         request
         emit-reason
         success-status
         (fn [snapshot]
           (success-body frame snapshot))))}))

(defn make-frame-mutation-handler [mutation-options]
  (fn [request]
    (with-synced-body
     request
     (fn [body]
       (with-required-string
        request body "frameId" "Missing frameId."
        (fn [frame-id]
          (run-frame-mutation request frame-id mutation-options)))))))

(def handle-delete-frame
  (make-frame-mutation-handler
   {:mutate! story/delete-frame!
    :default-error "Delete failed."
    :conflict-messages #{"Frame not found."}
    :emit-reason "frame-deleted"
    :success-status 200
    :success-body (fn [frame snapshot]
                    (with-revision {:deleted true
                                    :frame frame}
                                   snapshot))}))

(def handle-clear-frame-image
  (make-frame-mutation-handler
   {:mutate! story/clear-frame-image!
    :default-error "Clear image failed."
    :conflict-messages #{"Frame not found."
                         "Cannot clear image while queued or processing."}
    :emit-reason "frame-image-cleared"
    :success-status 200
    :success-body (fn [frame snapshot]
                    (with-revision {:cleared true
                                    :frame frame}
                                   snapshot))}))

(defn handle-signalr-negotiate [request]
  (if-let [info (realtime/create-client-connection-info)]
    (json-response 200 info request)
    (json-response 200
                   {:disabled true
                    :reason (str "Missing " realtime/connection-setting-name)}
                   request)))

(defn handle-options-preflight [request]
  #js {:status 204
       :headers (cors-headers request)})

(defn register-route! [{:keys [method name route handler]}]
  (case method
    :get (register-get! name route handler)
    :post (register-post! name route handler)
    :options (register-options! name route handler)
    (throw (js/Error. (str "Unsupported route method: " method)))))

(def route-specs
  [{:method :get :name "get-state" :route "state" :handler handle-get-state}
   {:method :post :name "post-generate-frame" :route "generate-frame" :handler handle-generate-frame}
   {:method :post :name "post-add-frame" :route "add-frame" :handler handle-add-frame}
   {:method :post :name "post-add-episode" :route "add-episode" :handler handle-add-episode}
   {:method :post :name "post-delete-frame" :route "delete-frame" :handler handle-delete-frame}
   {:method :post :name "post-clear-frame-image" :route "clear-frame-image" :handler handle-clear-frame-image}
   {:method :post :name "signalr-negotiate" :route "negotiate" :handler handle-signalr-negotiate}
   {:method :options :name "options-preflight" :route "{*path}" :handler handle-options-preflight}])

(doseq [spec route-specs]
  (register-route! spec))

(defn init! [] true)
