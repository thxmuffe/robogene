(ns services.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [services.chapter :as chapter]
            [services.character :as character]
            [services.image-handler :as image-handler]
            [services.realtime :as realtime]
            [host.settings :as settings]
            ["@azure/functions" :as azf]))

(def app (.-app azf))

(defn require-startup-env! []
  (when (empty? (settings/allowed-origins))
    (throw (js/Error. "Missing ROBOGENE_ALLOWED_ORIGIN in Function App settings."))))

(require-startup-env!)

(defn request-origin [request]
  (or (some-> request .-headers (.get "origin"))
      (some-> request .-headers (.get "Origin"))))

(defn cors-origin [request]
  (let [origins (settings/allowed-origins)
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

(def max-error-log-chars 200)

(defn truncate-string [s max-chars]
  (let [text (str s)]
    (if (> (count text) max-chars)
      (str (subs text 0 max-chars) "...(truncated)")
      text)))

(defn safe-json-length [value]
  (try
    (count (.stringify js/JSON (clj->js value)))
    (catch :default _
      -1)))

(defn parse-url-query [url]
  (try
    (let [search-params (some-> (js/URL. (or (some-> url str) "") "http://localhost") .-searchParams)
          entries (if search-params
                    (js/Array.from (.entries search-params))
                    #js [])
          pairs (js->clj entries)]
      (into {}
            (map (fn [[k v]] [k v]))
            pairs))
    (catch :default _
      {})))

(defn request-snapshot [request route-name]
  (let [method (or (some-> request .-method str/upper-case) "UNKNOWN")
        params (js->clj (or (gobj/get request "params") #js {}))
        query (parse-url-query (some-> request .-url))
        content-type (or (some-> request .-headers (.get "content-type"))
                         (some-> request .-headers (.get "Content-Type")))]
    {:route route-name
     :method method
     :paramCount (count params)
     :queryCount (count query)
     :hasBodyType (boolean (seq content-type))}))

(defn response-snapshot [response]
  (let [body (js->clj (or (some-> response (gobj/get "jsonBody")) #js {}))]
    {:status (or (some-> response (gobj/get "status")) 200)
     :bytes (safe-json-length body)
     :keyCount (if (map? body) (count body) nil)}))

(defn compact-id [raw-id]
  (let [id (or raw-id "-")]
    (if (> (count id) 12)
      (str (subs id 0 8) ".." (subs id (- (count id) 6)))
      id)))

(defn log-invocation! [{:keys [route-name started-at request response error context]}]
  (let [duration-ms (- (.now js/Date) started-at)
        function-name (or (some-> context (gobj/get "functionName")) route-name)
        invocation-id (compact-id (some-> context (gobj/get "invocationId")))
        status (or (:status response) "ERR")
        error-text (when error (truncate-string (error-message error) max-error-log-chars))
        line (str "[robogene] " function-name
                  " | id:" invocation-id
                  " | req:" (:method request)
                  " | res:" status
                  " | duration:" duration-ms "ms"
                  (if error-text (str " | err:" error-text) ""))]
    (cond
      (fn? (some-> context (gobj/get "info")))
      (.info context line)

      (fn? (some-> context (gobj/get "log")))
      (.log context line)

      :else
      (js/console.info line))))

(defn with-invocation-logging [route-name handler]
  (fn [request context]
    (let [started-at (.now js/Date)
          req-snapshot (request-snapshot request route-name)
          log-success! (fn [response]
                         (log-invocation! {:route-name route-name
                                           :started-at started-at
                                           :request req-snapshot
                                           :context context
                                           :response (response-snapshot response)})
                         response)
          log-error! (fn [err]
                       (log-invocation! {:route-name route-name
                                         :started-at started-at
                                         :request req-snapshot
                                         :context context
                                         :error err})
                       (throw err))]
      (try
        (let [result (handler request context)]
          (if (promise-like? result)
            (-> result
                (.then log-success!)
                (.catch log-error!))
            (do
              (log-success! result)
              result)))
        (catch :default err
          (log-error! err))))))

(defn with-error-handling [route handler]
  (fn [request context]
    (try
      (let [result (handler request context)]
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
              :handler (->> handler
                            (with-invocation-logging route-name)
                            (with-error-handling route-name))}))

(defn register-get! [name route handler]
  (register-http! name ["GET"] route route handler))

(defn register-post! [name route handler]
  (register-http! name ["POST"] route route handler))

(defn register-options! [name route handler]
  (register-http! name ["OPTIONS"] route route handler))

(defn request-json [request]
  (.json request))

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

(defn queueable-frame-outcome [frame-id]
  (let [snapshot @chapter/state
        frames (:frames snapshot)
        idx (chapter/find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))
        processing? (true? (:processing snapshot))
        queued? (= "queued" (:imageStatus frame))
        actively-processing? (and processing? (= "processing" (:imageStatus frame)))]
    (cond
      (nil? idx)
      {:ok false :status 404 :error "Frame not found."}

      (or queued? actively-processing?)
      {:ok false :status 409 :error "Frame already in queue."}

      :else
      {:ok true :idx idx :frames frames})))

(defn queue-frame! [idx direction without-roster]
  (swap! chapter/state
         (fn [s]
           (-> s
               (assoc-in [:frames idx :imageStatus] "queued")
               (assoc-in [:frames idx :queuedAt] (.toISOString (js/Date.)))
               (assoc-in [:frames idx :withoutRoster] (true? without-roster))
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
                                 :sagas (:sagas snapshot)
                                 :rosters (:rosters snapshot)
                                 :saga (:saga snapshot)
                                 :roster (:roster snapshot)
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
           without-roster (true? (gobj/get body "withoutRoster"))
           outcome (queueable-frame-outcome frame-id)]
       (if-not (:ok outcome)
         (json-response (:status outcome) {:error (:error outcome)} request)
         (do
           (queue-frame! (:idx outcome) direction without-roster)
           (let [queued-frame (get-in @chapter/state [:frames (:idx outcome)])]
             (chapter/emit-state-changed! "queued"
                                          {:frame queued-frame
                                           :requiresFetch false})
             (chapter/process-queue!)
             (-> (chapter/persist-state!)
                 (.catch (fn [err]
                           (js/console.error "[robogene] persist failed after queueing frame" err)
                           nil)))
             (queue-success-response request (:idx outcome)))))))))

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

(defn log-image-db-change! [kind frame snapshot]
  (js/console.info
   (str "[robogene] image db modified"
        " kind=" kind
        " frameId=" (:frameId frame)
        " imageStatus=" (:imageStatus frame)
        " revision=" (:revision snapshot)
        " ownerType=" (or (:ownerType frame) "saga")
        " ownerId=" (:chapterId frame))))

(defn handle-add-saga [request]
  (with-synced-body
   request
   (fn [body]
     (let [raw-name (or (gobj/get body "name") (gobj/get body "description"))
           raw-description (gobj/get body "description")
           name (if (some? raw-name) (some-> raw-name str str/trim) nil)
           description (if (some? raw-description) (some-> raw-description str str/trim) nil)]
       (run-command
        request
        {:run! #(chapter/add-saga! name description)
         :reason "saga-added"
         :default-error "Create saga failed."
         :status-by-message {}
         :on-success (fn [saga snapshot]
                       (json-response 201
                                      (with-revision {:created true
                                                      :saga saga}
                                                     snapshot)
                                      request))})))))

(defn handle-add-roster [request]
  (with-synced-body
   request
   (fn [body]
     (let [raw-name (or (gobj/get body "name") (gobj/get body "description"))
           raw-description (gobj/get body "description")
           name (if (some? raw-name) (some-> raw-name str str/trim) nil)
           description (if (some? raw-description) (some-> raw-description str str/trim) nil)]
       (run-command
        request
        {:run! #(chapter/add-roster! name description)
         :reason "roster-added"
         :default-error "Create roster failed."
         :status-by-message {}
         :on-success (fn [roster snapshot]
                       (json-response 201
                                      (with-revision {:created true
                                                      :rosterEntity roster}
                                                     snapshot)
                                      request))})))))

(defn handle-add-chapter [request]
  (with-synced-body
   request
   (fn [body]
     (let [saga-id (some-> (gobj/get body "sagaId") str str/trim)
           roster-id (some-> (gobj/get body "rosterId") str str/trim)
           raw-name (or (gobj/get body "name") (gobj/get body "description"))
           raw-description (gobj/get body "description")
           name (if (some? raw-name) (some-> raw-name str str/trim) nil)
           description (if (some? raw-description) (some-> raw-description str str/trim) nil)]
       (if (str/blank? saga-id)
         (json-response 400 {:error "Missing sagaId."} request)
         (if (str/blank? roster-id)
           (json-response 400 {:error "Missing rosterId."} request)
           (run-command
            request
            {:run! #(chapter/add-chapter-with-saga-roster! saga-id roster-id name description)
             :reason "chapter-added"
             :default-error "Create chapter failed."
             :status-by-message {"Saga not found." 404
                                 "Roster not found." 404
                                 "Missing rosterId." 400}
             :on-success (fn [result snapshot]
                           (let [{:keys [chapter frame]} result]
                             (json-response 201
                                            (with-revision {:created true
                                                            :chapter chapter
                                                            :frame frame}
                                                           snapshot)
                                            request)))})))))))

(defn handle-add-uploaded-frames [request]
  (with-synced-body
   request
   (fn [body]
     (let [chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           image-data-urls (js->clj (or (gobj/get body "imageDataUrls") #js []))]
       (cond
         (str/blank? chapter-id)
         (json-response 400 {:error "Missing chapterId."} request)

         (empty? image-data-urls)
         (json-response 400 {:error "Missing imageDataUrls."} request)

         :else
         (run-command
          request
          {:run! #(chapter/add-uploaded-frames! chapter-id image-data-urls)
           :reason "chapter-images-uploaded"
           :default-error "Upload images failed."
           :status-by-message {"Chapter not found." 404
                               "Missing image data." 400
                               "Invalid imageDataUrl." 400}
           :on-success (fn [frames snapshot]
                         (json-response 201
                                        (with-revision {:created true
                                                        :frames frames}
                                                       snapshot)
                                        request))}))))))

(defn handle-add-character [request]
  (with-synced-body
   request
   (fn [body]
     (let [roster-id (some-> (gobj/get body "rosterId") str str/trim)
           raw-name (or (gobj/get body "name") (gobj/get body "description"))
           raw-description (gobj/get body "description")
           name (if (some? raw-name) (some-> raw-name str str/trim) nil)
           description (if (some? raw-description) (some-> raw-description str str/trim) nil)]
       (if (str/blank? roster-id)
         (json-response 400 {:error "Missing rosterId."} request)
         (run-command
          request
          {:run! #(character/add-character-with-details! roster-id name description)
           :reason "character-added"
           :default-error "Create character failed."
           :status-by-message {"Roster not found." 404
                               "Missing rosterId." 400}
           :on-success (fn [result snapshot]
                         (let [{:keys [character frame]} result]
                           (json-response 201
                                          (with-revision {:created true
                                                          :character character
                                                          :frame frame}
                                                         snapshot)
                                          request)))}))))))

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
  (with-synced-body
   request
   (fn [body]
     (let [owner-type (some-> (gobj/get body "ownerType") str str/lower-case)
           chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           character-id (some-> (gobj/get body "characterId") str str/trim)
           frame-id (some-> (gobj/get body "frameId") str str/trim)
           target-owner-type (if (= owner-type "character") "character" "saga")
           owner-id (if (= target-owner-type "character") character-id chapter-id)]
       (if (str/blank? owner-id)
         (json-response 400 {:error (if (= target-owner-type "character")
                                      "Missing characterId."
                                      "Missing chapterId.")}
                        request)
         (run-command
          request
          {:run! #(if (= target-owner-type "character")
                    (character/add-frame-with-id! owner-id frame-id)
                    (chapter/add-frame! owner-id target-owner-type frame-id))
           :reason "frame-added"
           :default-error (if (= target-owner-type "character") "Character not found." "Chapter not found.")
           :status-by-message {"Chapter not found." 404
                               "Character not found." 404}
           :on-success (fn [frame snapshot]
                         (json-response 201
                                        (with-revision {:created true
                                                        :frame frame}
                                                       snapshot)
                                        request))}))))))

(defn handle-update-chapter [request]
  (with-synced-body
   request
   (fn [body]
     (let [chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           name (some-> (or (gobj/get body "name") (gobj/get body "description")) str str/trim)
           description (some-> (gobj/get body "description") str str/trim)]
       (cond
         (str/blank? chapter-id)
         (json-response 400 {:error "Missing chapterId."} request)

         (str/blank? name)
         (json-response 400 {:error "Missing chapter name."} request)

         :else
         (run-command
          request
          {:run! #(chapter/update-chapter-details! chapter-id name description)
           :reason "chapter-updated"
           :default-error "Update chapter failed."
           :status-by-message {"Chapter not found." 404
                               "Missing chapter name." 400}
           :on-success (fn [chapter snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :chapter chapter}
                                                       snapshot)
                                        request))}))))))

(defn handle-update-chapter-roster [request]
  (with-synced-body
   request
   (fn [body]
     (let [chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           roster-id (some-> (gobj/get body "rosterId") str str/trim)]
       (cond
         (str/blank? chapter-id)
         (json-response 400 {:error "Missing chapterId."} request)

         (str/blank? roster-id)
         (json-response 400 {:error "Missing rosterId."} request)

         :else
         (run-command
          request
          {:run! #(chapter/update-chapter-roster! chapter-id roster-id)
           :reason "chapter-roster-updated"
           :default-error "Update chapter roster failed."
           :status-by-message {"Chapter not found." 404
                               "Roster not found." 404
                               "Missing rosterId." 400}
           :on-success (fn [chapter snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :chapter chapter}
                                                       snapshot)
                                        request))}))))))

(defn handle-add-chapter-roster [request]
  (with-synced-body
   request
   (fn [body]
     (let [chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           roster-id (some-> (gobj/get body "rosterId") str str/trim)]
       (cond
         (str/blank? chapter-id)
         (json-response 400 {:error "Missing chapterId."} request)

         (str/blank? roster-id)
         (json-response 400 {:error "Missing rosterId."} request)

         :else
         (run-command
          request
          {:run! #(chapter/add-chapter-roster! chapter-id roster-id)
           :reason "chapter-roster-added"
           :default-error "Add chapter roster failed."
           :status-by-message {"Chapter not found." 404
                               "Roster not found." 404
                               "Missing rosterId." 400}
           :on-success (fn [chapter snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :chapter chapter}
                                                       snapshot)
                                        request))}))))))

(defn handle-update-character [request]
  (with-synced-body
   request
   (fn [body]
     (let [character-id (some-> (gobj/get body "characterId") str str/trim)
           name (some-> (or (gobj/get body "name") (gobj/get body "description")) str str/trim)
           description (some-> (gobj/get body "description") str str/trim)]
       (cond
         (str/blank? character-id)
         (json-response 400 {:error "Missing characterId."} request)

         (str/blank? name)
         (json-response 400 {:error "Missing character name."} request)

         :else
         (run-command
          request
          {:run! #(character/update-details! character-id name description)
           :reason "character-updated"
           :default-error "Update character failed."
           :status-by-message {"Character not found." 404
                               "Missing character name." 400}
           :on-success (fn [character snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :character character}
                                                       snapshot)
                                        request))}))))))

(defn handle-update-saga [request]
  (with-synced-body
   request
   (fn [body]
     (let [saga-id (some-> (gobj/get body "sagaId") str str/trim)
           name (some-> (or (gobj/get body "name") (gobj/get body "description")) str str/trim)
           description (some-> (gobj/get body "description") str str/trim)]
       (cond
         (str/blank? saga-id)
         (json-response 400 {:error "Missing sagaId."} request)

         (str/blank? name)
         (json-response 400 {:error "Missing saga name."} request)

         :else
         (run-command
          request
          {:run! #(chapter/update-saga-details! saga-id name description)
           :reason "saga-updated"
           :default-error "Update saga failed."
           :status-by-message {"Missing saga name." 400
                               "Saga not found." 404}
           :on-success (fn [saga snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :saga saga}
                                                       snapshot)
                                        request))}))))))

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

(defn handle-delete-empty-frames [request]
  (with-synced-body
   request
   (fn [body]
     (let [owner-type (some-> (gobj/get body "ownerType") str str/lower-case)
           chapter-id (some-> (gobj/get body "chapterId") str str/trim)
           character-id (some-> (gobj/get body "characterId") str str/trim)
           owner-id (if (= owner-type "character") character-id chapter-id)]
       (cond
         (not (#{"saga" "character"} owner-type))
         (json-response 400 {:error "Missing or invalid ownerType."} request)

         (str/blank? owner-id)
         (json-response 400
                        {:error (if (= owner-type "character")
                                  "Missing characterId."
                                  "Missing chapterId.")}
                        request)

         :else
         (-> (chapter/apply-command!
              {:reason "empty-frames-deleted"
               :run! #(chapter/delete-empty-frames! owner-id owner-type)})
             (.then (fn [{:keys [result snapshot]}]
                      (json-response 200
                                     (with-revision {:deleted true
                                                     :deletedCount (:deletedCount result)
                                                     :frameIds (:frameIds result)}
                                                    snapshot)
                                     request)))))))))

(def handle-clear-frame-image
  (make-required-mutation-handler
   {:field-key "frameId"
    :missing-msg "Missing frameId."
   :mutate! #(image-handler/clear-frame-image! chapter/state %)
    :default-error "Clear image failed."
    :status-by-message {"Frame not found." 404}
    :emit-reason "frame-image-cleared"
    :success-status 200
    :success-body (fn [frame snapshot]
                    (log-image-db-change! "cleared" frame snapshot)
                    (with-revision {:cleared true
                                    :frame frame}
                                   snapshot))}))

(defn handle-replace-frame-image [request]
  (with-synced-body
   request
   (fn [body]
     (let [frame-id (some-> (gobj/get body "frameId") str str/trim)
           image-data-url (some-> (gobj/get body "imageDataUrl") str str/trim)]
       (cond
         (str/blank? frame-id)
         (json-response 400 {:error "Missing frameId."} request)

         (str/blank? image-data-url)
         (json-response 400 {:error "Missing imageDataUrl."} request)

         :else
         (run-command
          request
          {:run! #(image-handler/replace-frame-image! chapter/state frame-id image-data-url)
           :reason "frame-image-replaced"
           :default-error "Replace image failed."
           :status-by-message {"Frame not found." 404
                               "Invalid imageDataUrl." 400}
           :on-success (fn [frame snapshot]
                         (log-image-db-change! "replaced" frame snapshot)
                         (json-response 200
                                        (with-revision {:updated true
                                                        :frame frame}
                                                       snapshot)
                                        request))}))))))

(defn handle-update-frame-description [request]
  (with-synced-body
   request
   (fn [body]
     (let [frame-id (some-> (gobj/get body "frameId") str str/trim)
           description (some-> (gobj/get body "description") str)]
       (if (str/blank? frame-id)
         (json-response 400 {:error "Missing frameId."} request)
         (run-command
          request
          {:run! #(chapter/update-frame-description! frame-id description)
           :reason "frame-description-updated"
           :default-error "Update frame description failed."
           :status-by-message {"Frame not found." 404}
           :on-success (fn [frame snapshot]
                         (json-response 200
                                        (with-revision {:updated true
                                                        :frame frame}
                                                       snapshot)
                                        request))}))))))

(def handle-delete-chapter
  (make-required-mutation-handler
   {:field-key "chapterId"
    :missing-msg "Missing chapterId."
    :mutate! chapter/delete-chapter!
    :default-error "Delete chapter failed."
    :status-by-message (messages->status-map #{"Chapter not found."} 404)
    :emit-reason "chapter-deleted"
    :success-status 200
    :success-body (fn [chapter snapshot]
                    (with-revision {:deleted true
                                    :chapter chapter}
                                   snapshot))}))

(def handle-delete-character
  (make-required-mutation-handler
   {:field-key "characterId"
    :missing-msg "Missing characterId."
    :mutate! character/delete-character!
    :default-error "Delete character failed."
    :status-by-message (messages->status-map #{"Character not found."} 404)
    :emit-reason "character-deleted"
    :success-status 200
    :success-body (fn [character snapshot]
                    (with-revision {:deleted true
                                    :character character}
                                   snapshot))}))

(def handle-delete-saga
  (make-required-mutation-handler
   {:field-key "sagaId"
    :missing-msg "Missing sagaId."
    :mutate! chapter/delete-saga!
    :default-error "Delete saga failed."
    :status-by-message (messages->status-map #{"Saga not found."} 404)
    :emit-reason "saga-deleted"
    :success-status 200
    :success-body (fn [saga snapshot]
                    (with-revision {:deleted true
                                    :saga saga}
                                   snapshot))}))

(defn handle-signalr-negotiate [request]
  (json-response 200
                 (or (realtime/create-client-connection-info)
                     {:disabled true})
                 request))

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
   {:method :post :name "post-update-frame-description" :route "update-frame-description" :handler handle-update-frame-description}
   {:method :post :name "post-replace-frame-image" :route "replace-frame-image" :handler handle-replace-frame-image}
   {:method :post :name "post-add-saga" :route "add-saga" :handler handle-add-saga}
   {:method :post :name "post-add-roster" :route "add-roster" :handler handle-add-roster}
   {:method :post :name "post-update-chapter" :route "update-chapter" :handler handle-update-chapter}
   {:method :post :name "post-update-chapter-roster" :route "update-chapter-roster" :handler handle-update-chapter-roster}
   {:method :post :name "post-add-chapter-roster" :route "add-chapter-roster" :handler handle-add-chapter-roster}
   {:method :post :name "post-update-character" :route "update-character" :handler handle-update-character}
   {:method :post :name "post-update-saga" :route "update-saga" :handler handle-update-saga}
   {:method :post :name "post-add-chapter" :route "add-chapter" :handler handle-add-chapter}
   {:method :post :name "post-add-uploaded-frames" :route "add-uploaded-frames" :handler handle-add-uploaded-frames}
   {:method :post :name "post-add-character" :route "add-character" :handler handle-add-character}
   {:method :post :name "post-delete-saga" :route "delete-saga" :handler handle-delete-saga}
   {:method :post :name "post-delete-chapter" :route "delete-chapter" :handler handle-delete-chapter}
   {:method :post :name "post-delete-character" :route "delete-character" :handler handle-delete-character}
   {:method :post :name "post-delete-frame" :route "delete-frame" :handler handle-delete-frame}
   {:method :post :name "post-delete-empty-frames" :route "delete-empty-frames" :handler handle-delete-empty-frames}
   {:method :post :name "post-clear-frame-image" :route "clear-frame-image" :handler handle-clear-frame-image}
   {:method :post :name "signalr-negotiate" :route "negotiate" :handler handle-signalr-negotiate}
   {:method :options :name "options-preflight" :route "{*path}" :handler handle-options-preflight}])

(doseq [spec route-specs]
  (register-route! spec))

(defn init!
  [& _]
  true)
