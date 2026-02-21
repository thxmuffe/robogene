(ns robogene.backend.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            ["@azure/functions" :as azf]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

(def app (.-app azf))
(def realtime (js/require "./realtime"))

(def backend-root (.resolve path js/__dirname ".." ".."))
(def assets-dir (.join path backend-root "assets"))

(def default-storyboard (.join path assets-dir "28_Municipal_Firmware_script.md"))
(def default-prompts (.join path assets-dir "28_Municipal_Firmware_image_prompts.md"))
(def default-reference-image (.join path assets-dir "robot_emperor_ep22_p01.png"))
(def page1-reference-image (.join path assets-dir "28_page_01_openai_refined.png"))

(defn read-file-or
  ([file-path fallback]
   (read-file-or file-path fallback nil))
  ([file-path fallback encoding]
   (try
     (if (some? encoding)
       (.readFileSync fs file-path encoding)
       (.readFileSync fs file-path))
     (catch :default _
       fallback))))

(defn read-text [file-path]
  (read-file-or file-path "" "utf8"))

(defn read-bytes [file-path]
  (read-file-or file-path nil))

(defn parse-descriptions [markdown]
  (let [section (or (second (re-find #"(?is)##\s*Page-by-page descriptions([\s\S]*?)(?:\n##\s|$)" markdown))
                    markdown)]
    (->> (str/split-lines section)
         (keep (fn [line]
                 (when-let [[_ idx txt] (re-find #"^\s*(\d+)\.\s+(.+)$" line)]
                   {:index (js/Number idx) :text (str/trim txt)})))
         (sort-by :index)
         vec)))

(defn parse-visual-prompts [markdown]
  (let [global-style (or (some-> (re-find #"(?is)##\s*Global style prompt.*?\n([^\n]+)" markdown)
                                 second
                                 str/trim)
                         "")
        section (or (second (re-find #"(?is)##\s*Page prompts([\s\S]*)" markdown)) markdown)
        page-prompts (->> (str/split-lines section)
                          (keep (fn [line]
                                  (when-let [[_ idx txt] (re-find #"^\s*(\d+)\.\s+(.+)$" line)]
                                    [(js/Number idx) (str/trim txt)])))
                          (into {}))]
    {:globalStyle global-style :pagePrompts page-prompts}))

(defn png-data-url [buffer]
  (str "data:image/png;base64," (.toString buffer "base64")))

(defn new-uuid []
  (.randomUUID crypto))

(defonce state
  (atom {:storyId nil
         :descriptions []
         :visual {:globalStyle "" :pagePrompts {}}
         :episodes []
         :frames []
         :failedJobs []
         :processing false
         :revision 0
         :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1-mini")
         :quality (or (.. js/process -env -ROBOGENE_IMAGE_QUALITY) "low")
         :size (or (.. js/process -env -ROBOGENE_IMAGE_SIZE) "1024x1024")
         :referenceImageBytes nil}))

(defn make-episode [episode-number description]
  {:episodeId (new-uuid)
   :episodeNumber episode-number
   :description (str/trim (or description ""))
   :createdAt (.toISOString (js/Date.))})

(defn best-frame-description [descriptions visual frame-number]
  (let [description-text (some (fn [b] (when (= (:index b) frame-number) (:text b))) descriptions)
        page-prompt (get-in visual [:pagePrompts frame-number] "")]
    (or (some-> page-prompt str/trim not-empty)
        (some-> description-text str/trim not-empty)
        (str "Frame " frame-number))))

(defn default-frame-description [frame-number]
  (best-frame-description (:descriptions @state)
                          (:visual @state)
                          frame-number))

(defn make-draft-frame [episode-id frame-number]
  {:frameId (new-uuid)
   :episodeId episode-id
   :frameNumber frame-number
   :description (default-frame-description frame-number)
   :status "draft"
   :createdAt (.toISOString (js/Date.))})

(defn next-episode-number [episodes]
  (inc (reduce max 0 (map :episodeNumber episodes))))

(defn episode-by-id [episodes episode-id]
  (some (fn [episode] (when (= (:episodeId episode) episode-id) episode)) episodes))

(defn frames-for-episode [frames episode-id]
  (->> frames
       (filter (fn [f] (= (:episodeId f) episode-id)))
       (sort-by :frameNumber)
       vec))

(defn next-frame-number [frames episode-id]
  (inc (reduce max 0 (map :frameNumber (frames-for-episode frames episode-id)))))

(defn ensure-draft-frame-for-episode! [episode-id]
  (let [frames (frames-for-episode (:frames @state) episode-id)
        missing-image? (some (fn [f] (str/blank? (or (:imageDataUrl f) ""))) frames)]
    (when (or (empty? frames) (not missing-image?))
      (let [frame-number (next-frame-number (:frames @state) episode-id)]
        (swap! state
               (fn [s]
                 (-> s
                     (update :frames conj (make-draft-frame episode-id frame-number))
                     (update :revision inc))))))))

(defn ensure-draft-frames! []
  (doseq [episode (:episodes @state)]
    (ensure-draft-frame-for-episode! (:episodeId episode))))

(defn add-episode! [description]
  (let [episode (make-episode (next-episode-number (:episodes @state))
                              (if (str/blank? (or description ""))
                                (str "Episode " (next-episode-number (:episodes @state)))
                                description))
        episode-id (:episodeId episode)
        first-frame (assoc (make-draft-frame episode-id 1)
                           :description (str/trim (or description "Episode opening scene.")))]
    (swap! state
           (fn [s]
             (-> s
                 (update :episodes conj episode)
                 (update :frames conj first-frame)
                 (update :revision inc))))
    {:episode episode
     :frame first-frame}))

(defn add-frame! [episode-id]
  (let [episode (episode-by-id (:episodes @state) episode-id)]
    (when-not episode
      (throw (js/Error. "Episode not found.")))
    (let [frame-number (next-frame-number (:frames @state) episode-id)
          frame (make-draft-frame episode-id frame-number)]
      (swap! state
             (fn [s]
               (-> s
                   (update :frames conj frame)
                   (update :revision inc))))
      frame)))

(declare find-frame-index)

(defn deletable-frame-status? [status]
  (not (or (= status "queued")
           (= status "processing"))))

(defn delete-frame! [frame-id]
  (let [snapshot @state
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (throw (js/Error. "Frame not found.")))
    (when-not (deletable-frame-status? (:status frame))
      (throw (js/Error. "Cannot delete frame while queued or processing.")))
    (swap! state
           (fn [s]
             (-> s
                 (update :frames (fn [rows]
                                   (vec (concat (subvec rows 0 idx)
                                                (subvec rows (inc idx)))))
                 )
                 (update :revision inc))))
    (ensure-draft-frame-for-episode! (:episodeId frame))
    frame))

(defn initialize-state! []
  (let [storyboard-text (read-text default-storyboard)
        prompts-text (read-text default-prompts)
        descriptions (parse-descriptions storyboard-text)
        visual (parse-visual-prompts prompts-text)
        ref-bytes (read-bytes default-reference-image)
        page1-bytes (read-bytes page1-reference-image)
        episode1 (make-episode 1 "Episode 1")
        episode-id (:episodeId episode1)
        frame1 {:frameId (new-uuid)
                :episodeId episode-id
                :frameNumber 1
                :description (best-frame-description descriptions visual 1)
                :imageDataUrl (when page1-bytes (png-data-url page1-bytes))
                :status (if page1-bytes "ready" "draft")
                :reference true
                :createdAt (.toISOString (js/Date.))}
        frames (if page1-bytes
                 [frame1 (assoc (make-draft-frame episode-id 2)
                               :description (best-frame-description descriptions visual 2))]
                 [(assoc (make-draft-frame episode-id 1)
                         :description (best-frame-description descriptions visual 1))])]
    (reset! state
            {:storyId (new-uuid)
             :descriptions descriptions
             :visual visual
             :episodes [episode1]
             :frames frames
             :failedJobs []
             :processing false
             :revision 1
             :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1-mini")
             :quality (or (.. js/process -env -ROBOGENE_IMAGE_QUALITY) "low")
             :size (or (.. js/process -env -ROBOGENE_IMAGE_SIZE) "1024x1024")
             :referenceImageBytes ref-bytes})))

(initialize-state!)

(defn episode-order-map [episodes]
  (into {} (map (fn [episode] [(:episodeId episode) (:episodeNumber episode)]) episodes)))

(defn sort-frames-for-story [episodes frames]
  (let [order-by-episode (episode-order-map episodes)]
    (->> frames
         (sort-by (fn [f]
                    [(get order-by-episode (:episodeId f) 99999)
                     (:frameNumber f)]))
         vec)))

(defn completed-frames []
  (->> (sort-frames-for-story (:episodes @state) (:frames @state))
       (filter (fn [f] (not (str/blank? (or (:imageDataUrl f) "")))))
       vec))

(defn continuity-window [limit]
  (let [tail (take-last limit (completed-frames))]
    (if (empty? tail)
      "No previous frames yet."
      (str/join "\n" (map (fn [s] (str "Frame " (:frameNumber s) ": " (:description s) ".")) tail)))))

(defn build-prompt-for-frame [frame]
  (let [episode (episode-by-id (:episodes @state) (:episodeId frame))
        episode-label (when episode
                        (str "Episode " (:episodeNumber episode)
                             " theme: " (:description episode)))]
    (str/join
     "\n\n"
     (filter seq
             ["Create ONE comic story image for the next frame."
              "Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer)."
              episode-label
              (get-in @state [:visual :globalStyle] "")
              (str "Storyboard description for this frame: " (:description frame))
              (str "User direction for this frame:\n" (or (:description frame) ""))
              (str "Story continuity memory:\n" (continuity-window 6))
              "Keep this image as the next chronological frame in the same story world."
              "Avoid title/header text overlays."]))))

(defn image-data-url->bytes [data-url]
  (when (and (string? data-url) (str/starts-with? data-url "data:image/png;base64,"))
    (js/Buffer.from (subs data-url (count "data:image/png;base64,")) "base64")))

(defn fetch-json [url options]
  (-> (js/fetch url options)
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [body]
                            {:ok (.-ok response)
                             :status (.-status response)
                             :body body})))))))

(defn openai-image-response->data-url [body]
  (let [data (gobj/get body "data")
        first-item (when (and data (> (.-length data) 0)) (aget data 0))
        b64 (when first-item (gobj/get first-item "b64_json"))]
    (if (not b64)
      (throw (js/Error. (str "Unexpected OpenAI response: " (.stringify js/JSON body))))
      (str "data:image/png;base64," b64))))

(defn reference-images []
  (let [prev-frame (last (completed-frames))]
    (vec
     (filter some?
             [{:bytes (:referenceImageBytes @state) :name "character_ref.png"}
              {:bytes (image-data-url->bytes (:imageDataUrl prev-frame)) :name "previous_frame.png"}]))))

(defn append-reference-image! [form {:keys [bytes name]}]
  (let [blob (js/Blob. (clj->js [bytes]) #js {:type "image/png"})]
    (.append form "image[]" blob name)))

(defn openai-image-request! [api-key prompt refs]
  (if (seq refs)
    (let [form (js/FormData.)]
      (.append form "model" (:model @state))
      (.append form "prompt" prompt)
      (.append form "quality" (:quality @state))
      (.append form "size" (:size @state))
      (doseq [ref refs]
        (append-reference-image! form ref))
      (fetch-json "https://api.openai.com/v1/images/edits"
                  #js {:method "POST"
                       :headers #js {:Authorization (str "Bearer " api-key)}
                       :body form}))
    (fetch-json "https://api.openai.com/v1/images/generations"
                #js {:method "POST"
                     :headers #js {:Authorization (str "Bearer " api-key)
                                   "Content-Type" "application/json"}
                     :body (.stringify js/JSON
                                       (clj->js {:model (:model @state)
                                                 :prompt prompt
                                                 :quality (:quality @state)
                                                 :size (:size @state)}))})))

(defn generate-image! [frame]
  (let [api-key (.. js/process -env -OPENAI_API_KEY)]
    (if (not (seq api-key))
      (js/Promise.reject (js/Error. "Missing OPENAI_API_KEY in Function App settings."))
      (let [prompt (build-prompt-for-frame frame)
            refs (reference-images)]
        (-> (openai-image-request! api-key prompt refs)
            (.then (fn [{:keys [ok status body]}]
                     (if-not ok
                       (throw (js/Error. (str "OpenAI error " status ": " (.stringify js/JSON body))))
                       (openai-image-response->data-url body)))))))))

(defn next-queued-frame-index [frames]
  (first (keep-indexed (fn [idx frame]
                         (when (= "queued" (:status frame)) idx))
                       frames)))

(defn mark-frame-processing! [idx]
  (swap! state
         (fn [s]
           (-> s
               (assoc-in [:frames idx :status] "processing")
               (assoc-in [:frames idx :startedAt] (.toISOString (js/Date.)))
               (update :revision inc)))))

(defn mark-frame-ready! [idx image-data-url]
  (swap! state
         (fn [s]
           (-> s
               (assoc-in [:frames idx :imageDataUrl] image-data-url)
               (assoc-in [:frames idx :status] "ready")
               (assoc-in [:frames idx :error] nil)
               (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
               (update :revision inc)))))

(defn mark-frame-failed! [idx frame message]
  (swap! state
         (fn [s]
           (let [failed {:jobId (new-uuid)
                         :frameId (:frameId frame)
                         :frameNumber (:frameNumber frame)
                         :description (:description frame)
                         :error message
                         :createdAt (.toISOString (js/Date.))}]
             (-> s
                 (assoc-in [:frames idx :status] "failed")
                 (assoc-in [:frames idx :error] message)
                 (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
                 (update :failedJobs (fn [rows] (vec (take 20 (cons failed rows)))))
                 (update :revision inc))))))

(declare process-step! active-queue-count)

(def ansi-reset "\u001b[0m")
(def ansi-white "\u001b[97m")
(def ansi-green "\u001b[32m")

(defn colorize [ansi-color text]
  (str ansi-color text ansi-reset))

(defn emit-state-changed! [reason]
  (let [snapshot @state
        payload (clj->js {:reason reason
                          :storyId (:storyId snapshot)
                          :revision (:revision snapshot)
                          :processing (:processing snapshot)
                          :pendingCount (active-queue-count (:frames snapshot))
                          :emittedAt (.toISOString (js/Date.))})]
    (-> (.publishStateUpdate realtime payload)
        (.catch (fn [err]
                  (js/console.warn
                   (str "[robogene] SignalR publish skipped/failed: "
                        (or (some-> err .-message) err))))))))

(defn log-generation-start! [frame queue-size]
  (js/console.log
   (colorize ansi-white
             (str "[robogene] generation started"
                  " frameNumber=" (:frameNumber frame)
                  " frameId=" (:frameId frame)
                  " queueSize=" queue-size))))

(defn log-generation-success! [frame duration-ms]
  (js/console.log
   (colorize ansi-green
             (str "[robogene] generation finished"
                  " frameNumber=" (:frameNumber frame)
                  " frameId=" (:frameId frame)
                  " durationMs=" duration-ms))))

(defn log-generation-failed! [frame duration-ms err]
  (js/console.error
   (colorize ansi-white
             (str "[robogene] generation failed"
                  " frameNumber=" (:frameNumber frame)
                  " frameId=" (:frameId frame)
                  " durationMs=" duration-ms
                  " error=" (or (some-> err .-message str) err)))))

(defn process-step! []
  (let [snapshot @state
        idx (next-queued-frame-index (:frames snapshot))]
    (if (nil? idx)
      (let [previously-processing? (:processing snapshot)]
        (swap! state assoc :processing false)
        (when previously-processing?
          (emit-state-changed! "queue-idle")))
      (let [frame (get (:frames snapshot) idx)
            started-ms (.now js/Date)
            queue-size (active-queue-count (:frames snapshot))]
        (log-generation-start! frame queue-size)
        (mark-frame-processing! idx)
        (emit-state-changed! "processing")
        (-> (generate-image! frame)
            (.then (fn [image-data-url]
                     (log-generation-success! frame (- (.now js/Date) started-ms))
                     (mark-frame-ready! idx image-data-url)
                     (ensure-draft-frame-for-episode! (:episodeId frame))
                     (emit-state-changed! "ready")
                     (process-step!)
                     nil))
            (.catch (fn [err]
                      (log-generation-failed! frame (- (.now js/Date) started-ms) err)
                      (mark-frame-failed! idx frame (str (or (.-message err) err)))
                      (emit-state-changed! "failed")
                      (process-step!)
                      nil)))))))

(defn process-queue! []
  (when-not (:processing @state)
    (swap! state assoc :processing true)
    (process-step!)))

(defn active-queue-count [frames]
  (count (filter (fn [f]
                   (or (= "queued" (:status f))
                       (= "processing" (:status f))))
                 frames)))

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

(defn find-frame-index [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))

(.http app "get-state"
       #js {:methods #js ["GET"]
            :authLevel "anonymous"
            :route "state"
            :handler (with-error-handling
                      "state"
                      (fn [request]
                        (ensure-draft-frames!)
                        (let [snapshot @state
                              frames (:frames snapshot)
                              pending-count (active-queue-count frames)]
                          (json-response 200
                                         {:storyId (:storyId snapshot)
                                          :revision (:revision snapshot)
                                          :processing (:processing snapshot)
                                          :pendingCount pending-count
                                          :episodes (:episodes snapshot)
                                          :frames frames
                                          :failed (:failedJobs snapshot)}
                                         request))))})

(.http app "post-generate-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "generate-frame"
            :handler (with-error-handling
                      "generate-frame"
                      (fn [request]
                        (-> (request-json request)
                            (.then
                             (fn [body]
                               (let [frame-id (some-> (gobj/get body "frameId") str str/trim)
                                     direction (some-> (gobj/get body "direction") str str/trim)]
                                 (if (str/blank? frame-id)
                                   (json-response 400 {:error "Missing frameId."} request)
                                   (let [snapshot @state
                                         frames (:frames snapshot)
                                         idx (find-frame-index frames frame-id)]
                                     (cond
                                       (nil? idx)
                                       (json-response 404 {:error "Frame not found."} request)

                                       (or (= "queued" (:status (get frames idx)))
                                           (= "processing" (:status (get frames idx))))
                                       (json-response 409 {:error "Frame already in queue."} request)

                                       :else
                                       (do
                                       (swap! state
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
                                         (emit-state-changed! "queued")
                                         (process-queue!)
                                         (let [post @state]
                                           (json-response 202
                                                          {:accepted true
                                                           :frame (get (:frames post) idx)
                                                           :revision (:revision post)
                                                           :pendingCount (active-queue-count (:frames post))}
                                                          request))))))))))))})

(.http app "post-add-episode"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "add-episode"
            :handler (with-error-handling
                      "add-episode"
                      (fn [request]
                        (-> (request-json request)
                            (.then
                             (fn [body]
                               (let [description (some-> (gobj/get body "description") str str/trim)
                                     {:keys [episode frame]} (add-episode! description)
                                     snapshot @state]
                                 (emit-state-changed! "episode-added")
                                 (json-response 201
                                                {:created true
                                                 :episode episode
                                                 :frame frame
                                                 :revision (:revision snapshot)}
                                                request)))))))})

(.http app "post-add-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "add-frame"
            :handler (with-error-handling
                      "add-frame"
                      (fn [request]
                        (-> (request-json request)
                            (.then
                             (fn [body]
                               (let [episode-id (some-> (gobj/get body "episodeId") str str/trim)]
                                 (if (str/blank? episode-id)
                                   (json-response 400 {:error "Missing episodeId."} request)
                                   (try
                                     (let [frame (add-frame! episode-id)
                                           snapshot @state]
                                       (emit-state-changed! "frame-added")
                                       (json-response 201
                                                      {:created true
                                                       :frame frame
                                                       :revision (:revision snapshot)}
                                                      request))
                                     (catch :default err
                                       (json-response 404
                                                      {:error (or (some-> err .-message str) "Episode not found.")}
                                                      request))))))))))})

(.http app "post-delete-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "delete-frame"
            :handler (with-error-handling
                      "delete-frame"
                      (fn [request]
                        (-> (request-json request)
                            (.then
                             (fn [body]
                               (let [frame-id (some-> (gobj/get body "frameId") str str/trim)]
                                 (if (str/blank? frame-id)
                                   (json-response 400 {:error "Missing frameId."} request)
                                   (try
                                     (let [deleted-frame (delete-frame! frame-id)
                                           snapshot @state]
                                       (emit-state-changed! "frame-deleted")
                                       (json-response 200
                                                      {:deleted true
                                                       :frame deleted-frame
                                                       :revision (:revision snapshot)}
                                                      request))
                                     (catch :default err
                                       (let [msg (or (some-> err .-message str) "Delete failed.")
                                             status (if (or (= msg "Frame not found.")
                                                            (= msg "Cannot delete frame while queued or processing."))
                                                      409
                                                      500)]
                                         (json-response status {:error msg} request)))))))))))})

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
