(ns services.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [services.chapter :as chapter]
            [services.realtime :as realtime]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn allowed-origins []
  (let [raw (or (.. js/process -env -ROBOGENE_ALLOWED_ORIGIN) "")]
    (->> (str/split raw #",")
         (map str/trim)
         (filter seq)
         vec)))

(defn require-startup-env! []
  (when (empty? (allowed-origins))
    (throw (js/Error. "Missing ROBOGENE_ALLOWED_ORIGIN in Function App settings."))))

(require-startup-env!)

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
  (-> (chapter/sync-state-from-storage!)
      (.then (fn [_] (request-json request)))
      (.then handler)))

(defn with-required-string [request body field-key missing-msg handler]
  (let [value (some-> (gobj/get body field-key) str str/trim)]
    (if (str/blank? value)
      (json-response 400 {:error missing-msg} request)
      (handler value))))

(defn with-synced-required-string [request field-key missing-msg handler]
  (with-synced-body
   request
   (fn [body]
     (with-required-string request body field-key missing-msg handler))))

(defn with-synced-required-string+body [request field-key missing-msg handler]
  (with-synced-body
   request
   (fn [body]
     (with-required-string
      request body field-key missing-msg
      (fn [value]
        (handler value body))))))

(defn with-required-any-string [request body field-keys missing-msg handler]
  (let [value (some (fn [field-key]
                      (some-> (gobj/get body field-key) str str/trim not-empty))
                    field-keys)]
    (if (str/blank? (or value ""))
      (json-response 400 {:error missing-msg} request)
      (handler value))))

(defn with-synced-required-any-string [request field-keys missing-msg handler]
  (with-synced-body
   request
   (fn [body]
     (with-required-any-string request body field-keys missing-msg handler))))

(defn queueable-frame-outcome [frame-id]
  (let [snapshot @chapter/state
        frames (:frames snapshot)
        idx (chapter/find-frame-index frames frame-id)]
    (cond
      (nil? idx)
      {:ok false :status 404 :error "Frame not found."}

      (or (= "queued" (:status (get frames idx)))
          (= "processing" (:status (get frames idx))))
      {:ok false :status 409 :error "Frame already in queue."}

      :else
      {:ok true :idx idx :frames frames})))

(defn queue-frame! [idx direction]
  (swap! chapter/state
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
  (let [post @chapter/state]
    (json-response 202
                   {:accepted true
                    :frame (get (:frames post) idx)
                    :revision (:revision post)
                    :pendingCount (chapter/active-queue-count (:frames post))}
                   request)))

(defn handle-get-state [request]
  (-> (chapter/sync-state-from-storage!)
      (.then (fn [_]
               (js/Promise.resolve @chapter/state)))
      (.then (fn [_]
               (let [snapshot @chapter/state
                     frames (:frames snapshot)
                     pending-count (chapter/active-queue-count frames)]
                 (json-response 200
                                {:chapterId (:chapterId snapshot)
                                 :revision (:revision snapshot)
                                 :processing (:processing snapshot)
                                 :pendingCount pending-count
                                 :chapters (:chapters snapshot)
                                 :frames frames
                                 :failed (:failedJobs snapshot)}
                                request))))))

(defn handle-generate-frame [request]
  (with-synced-required-string+body
   request
   "frameId"
   "Missing frameId."
   (fn [frame-id body]
     (let [direction (some-> (gobj/get body "direction") str str/trim)
           outcome (queueable-frame-outcome frame-id)]
       (if-not (:ok outcome)
         (json-response (:status outcome) {:error (:error outcome)} request)
         (do
           (queue-frame! (:idx outcome) direction)
           (-> (chapter/persist-state!)
               (.then (fn [_]
                        (chapter/emit-state-changed! "queued")
                        (chapter/process-queue!)
                        (queue-success-response request (:idx outcome)))))))))))

(defn with-revision [body snapshot]
  (assoc body :revision (:revision snapshot)))

(defn command-error-response [request default-error status-by-message err]
  (let [message (or (some-> err .-message str) default-error)
        status (get status-by-message message 500)]
    (json-response status {:error message} request)))

(defn run-command [request {:keys [run! reason default-error status-by-message on-success]}]
  (-> (chapter/apply-command! {:run! run! :reason reason})
      (.then (fn [{:keys [result snapshot]}]
               (on-success result snapshot)))
      (.catch (fn [err]
                (command-error-response request default-error status-by-message err)))))

(defn handle-add-chapter [request]
  (with-synced-body
   request
   (fn [body]
     (let [raw-description (gobj/get body "description")
           description (if (some? raw-description)
                         (some-> raw-description str str/trim)
                         nil)]
       (run-command
        request
        {:run! #(chapter/add-chapter! description)
         :reason "chapter-added"
         :default-error "Create chapter failed."
         :status-by-message {}
         :on-success (fn [result snapshot]
                       (let [{:keys [chapter frame]} result]
                         (json-response 201
                                        (with-revision {:created true
                                                        :chapter chapter
                                                        :frame frame}
                                                       snapshot)
                                        request)))})))))

(defn messages->status-map [messages status]
  (into {} (map (fn [msg] [msg status]) messages)))

(defn make-required-mutation-handler [{:keys [field-key missing-msg mutate! default-error status-by-message emit-reason success-status success-body]}]
  (fn [request]
    (with-synced-required-string
     request
     field-key
     missing-msg
     (fn [field-value]
       (run-command
        request
        {:run! #(mutate! field-value)
         :reason emit-reason
         :default-error default-error
         :status-by-message status-by-message
         :on-success (fn [result snapshot]
                       (json-response success-status
                                      (success-body result snapshot)
                                      request))})))))

(defn handle-add-frame [request]
  (with-synced-required-any-string
   request
   ["chapterId" "episodeId"]
   "Missing chapterId."
   (fn [chapter-id]
     (run-command
      request
      {:run! #(chapter/add-frame! chapter-id)
       :reason "frame-added"
       :default-error "Chapter not found."
       :status-by-message {"Chapter not found." 404}
       :on-success (fn [frame snapshot]
                     (json-response 201
                                    (with-revision {:created true
                                                    :frame frame}
                                                   snapshot)
                                    request))}))))

(def handle-delete-frame
  (make-required-mutation-handler
   {:field-key "frameId"
    :missing-msg "Missing frameId."
    :mutate! chapter/delete-frame!
    :default-error "Delete failed."
    :status-by-message (messages->status-map #{"Frame not found."} 409)
    :emit-reason "frame-deleted"
    :success-status 200
    :success-body (fn [frame snapshot]
                    (with-revision {:deleted true
                                    :frame frame}
                                   snapshot))}))

(def handle-clear-frame-image
  (make-required-mutation-handler
   {:field-key "frameId"
    :missing-msg "Missing frameId."
    :mutate! chapter/clear-frame-image!
    :default-error "Clear image failed."
    :status-by-message (messages->status-map #{"Frame not found."
                                               "Cannot clear image while queued or processing."}
                                             409)
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
   {:method :post :name "post-add-chapter" :route "add-chapter" :handler handle-add-chapter}
   {:method :post :name "post-delete-frame" :route "delete-frame" :handler handle-delete-frame}
   {:method :post :name "post-clear-frame-image" :route "clear-frame-image" :handler handle-clear-frame-image}
   {:method :post :name "signalr-negotiate" :route "negotiate" :handler handle-signalr-negotiate}
   {:method :options :name "options-preflight" :route "{*path}" :handler handle-options-preflight}])

(doseq [spec route-specs]
  (register-route! spec))

(defn init!
  [& _]
  true)
