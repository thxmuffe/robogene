(ns robogene.backend.story
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [robogene.backend.realtime :as realtime]
            [robogene.backend.azure-store :as store]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

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

(defn clear-frame-image! [frame-id]
  (let [snapshot @state
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (throw (js/Error. "Frame not found.")))
    (when-not (deletable-frame-status? (:status frame))
      (throw (js/Error. "Cannot clear image while queued or processing.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageDataUrl] nil)
                 (assoc-in [:frames idx :status] "draft")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] nil)
                 (update :revision inc))))
    (get (:frames @state) idx)))

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

(defn apply-persisted-state! [raw]
  (let [current @state
        persisted (js->clj raw :keywordize-keys true)]
    (swap! state
           (fn [s]
             (-> s
                 (assoc :storyId (:storyId persisted))
                 (assoc :revision (or (:revision persisted) 1))
                 (assoc :failedJobs (or (:failedJobs persisted) []))
                 (assoc :episodes (or (:episodes persisted) []))
                 (assoc :frames (or (:frames persisted) []))
                 (assoc :descriptions (:descriptions current))
                 (assoc :visual (:visual current))
                 (assoc :referenceImageBytes (:referenceImageBytes current))
                 (assoc :model (:model current))
                 (assoc :quality (:quality current))
                 (assoc :size (:size current)))))
    @state))

(defn sync-state-from-storage! []
  (-> (store/load-or-init-state
       (clj->js (select-keys @state
                             [:storyId :revision :failedJobs :episodes :frames
                              :descriptions :visual :model :quality :size])))
      (.then apply-persisted-state!)
      (.catch (fn [err]
                (js/console.error "[robogene] storage sync failed" err)
                (throw err)))))

(defn persist-state! []
  (-> (store/save-state
       (clj->js (select-keys @state
                             [:storyId :revision :failedJobs :episodes :frames])))
      (.then apply-persisted-state!)
      (.catch (fn [err]
                (js/console.error "[robogene] storage persist failed" err)
                (throw err)))))

(-> (sync-state-from-storage!)
    (.catch (fn [err]
              (js/console.warn "[robogene] startup storage sync skipped" err))))

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

(defn invalid-second-reference? [status body]
  (let [err (gobj/get body "error")
        code (some-> err (gobj/get "code"))
        message (some-> err (gobj/get "message") str)]
    (and (= status 400)
         (= code "invalid_image_file")
         (str/includes? (or message "") "image 2"))))

(defn openai-response->result [{:keys [ok status body]}]
  (if-not ok
    (throw (js/Error. (str "OpenAI error " status ": " (.stringify js/JSON body))))
    (openai-image-response->data-url body)))

(defn generate-image! [frame]
  (let [api-key (.. js/process -env -OPENAI_API_KEY)]
    (if (not (seq api-key))
      (js/Promise.reject (js/Error. "Missing OPENAI_API_KEY in Function App settings."))
      (let [prompt (build-prompt-for-frame frame)
            refs (reference-images)]
        (-> (openai-image-request! api-key prompt refs)
            (.then (fn [{:keys [ok status body]}]
                     (if (and (not ok) (invalid-second-reference? status body) (> (count refs) 1))
                       (do
                         (js/console.warn
                          "[robogene] OpenAI rejected secondary reference image; retrying with single reference image.")
                         (-> (openai-image-request! api-key prompt (vec (take 1 refs)))
                             (.then openai-response->result)))
                       (openai-response->result {:ok ok :status status :body body})))))))))

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
    (-> (realtime/publish-state-update! payload)
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
        (if previously-processing?
          (-> (persist-state!)
              (.then (fn [_]
                       (emit-state-changed! "queue-idle")
                       nil))
              (.catch (fn [err]
                        (js/console.error "[robogene] persist failed while queue-idle" err)
                        nil)))
          nil))
      (let [frame (get (:frames snapshot) idx)
            started-ms (.now js/Date)
            queue-size (active-queue-count (:frames snapshot))]
        (log-generation-start! frame queue-size)
        (mark-frame-processing! idx)
        (-> (persist-state!)
            (.then (fn [_]
                     (emit-state-changed! "processing")
                     (generate-image! frame)))
            (.then (fn [image-data-url]
                     (log-generation-success! frame (- (.now js/Date) started-ms))
                     (mark-frame-ready! idx image-data-url)
                     (ensure-draft-frame-for-episode! (:episodeId frame))
                     (-> (persist-state!)
                         (.then (fn [_]
                                  (emit-state-changed! "ready")
                                  (process-step!)
                                  nil)))))
            (.catch (fn [err]
                      (log-generation-failed! frame (- (.now js/Date) started-ms) err)
                      (mark-frame-failed! idx frame (str (or (.-message err) err)))
                      (-> (persist-state!)
                          (.then (fn [_]
                                   (emit-state-changed! "failed")
                                   (process-step!)
                                   nil))
                          (.catch (fn [persist-err]
                                    (js/console.error "[robogene] persist failed after generation error" persist-err)
                                    (emit-state-changed! "failed")
                                    (process-step!)
                                    nil))))))))))

(defn process-queue! []
  (when-not (:processing @state)
    (swap! state assoc :processing true)
    (-> (persist-state!)
        (.then (fn [_]
                 (process-step!)
                 nil))
        (.catch (fn [err]
                  (js/console.error "[robogene] persist failed when queue started" err)
                  (process-step!)
                  nil)))))

(defn active-queue-count [frames]
  (count (filter (fn [f]
                   (or (= "queued" (:status f))
                       (= "processing" (:status f))))
                 frames)))

(defn find-frame-index [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))
