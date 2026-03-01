(ns services.chapter
  (:require [clojure.string :as str]
            [services.image-generator :as image-generator]
            [services.realtime :as realtime]
            [host.settings :as settings]
            [services.azure-store :as store]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

(defn existing-path? [p]
  (.existsSync fs p))

(defn story-script-path [root]
  (.join path root "ai" "robot emperor" "story" "28_Municipal_Firmware_script.md"))

(defn candidate-root? [root]
  (existing-path? (story-script-path root)))

(defn resolve-repo-root []
  (let [env-root (some-> js/process .-env .-ROBOGENE_REPO_ROOT)
        candidates (remove nil?
                           [env-root
                            (.cwd js/process)
                            (.resolve path js/__dirname "..")
                            (.resolve path js/__dirname ".." ".." "..")])]
    (or (some (fn [root]
                (when (candidate-root? root)
                  root))
              candidates)
        (.cwd js/process))))

(def repo-root (resolve-repo-root))
(def definitions-root (.join path repo-root "ai" "robot emperor"))
(def references-dir (.join path definitions-root "references"))

(def resolved-chapter-root (.join path definitions-root "story"))

(def default-chapter-script (.join path resolved-chapter-root "28_Municipal_Firmware_script.md"))
(def default-prompts (.join path resolved-chapter-root "28_Municipal_Firmware_image_prompts.md"))
(def default-reference-image (.join path references-dir "robot_emperor_ep22_p01.png"))
(def page1-reference-image (.join path resolved-chapter-root "28_page_01_openai_refined.png"))

(defn read-text [file-path]
  (.readFileSync fs file-path "utf8"))

(defn read-bytes [file-path]
  (.readFileSync fs file-path))

(image-generator/require-startup-env!)

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
  (let [openai-options (settings/image-settings)]
    (atom {:chapterId nil
           :descriptions []
           :visual {:globalStyle "" :pagePrompts {}}
           :chapters []
           :frames []
           :failedJobs []
           :processing false
           :revision 0
           :openaiOptions openai-options
           :referenceImageBytes nil})))

(defn make-chapter [chapter-number description]
  {:chapterId (new-uuid)
   :chapterNumber chapter-number
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

(defn make-draft-frame [chapter-id frame-number]
  {:frameId (new-uuid)
   :chapterId chapter-id
   :frameNumber frame-number
   :description (default-frame-description frame-number)
   :status "draft"
   :createdAt (.toISOString (js/Date.))})

(defn next-chapter-number [chapters]
  (inc (reduce max 0 (map :chapterNumber chapters))))

(defn chapter-by-id [chapters chapter-id]
  (some (fn [chapter] (when (= (:chapterId chapter) chapter-id) chapter)) chapters))

(defn frames-for-chapter [frames chapter-id]
  (->> frames
       (filter (fn [f] (= (:chapterId f) chapter-id)))
       (sort-by :frameNumber)
       vec))

(defn next-frame-number [frames chapter-id]
  (inc (reduce max 0 (map :frameNumber (frames-for-chapter frames chapter-id)))))

(defn add-chapter! [description]
  (let [chapter (make-chapter (next-chapter-number (:chapters @state))
                              (if (str/blank? (or description ""))
                                (str "Chapter " (next-chapter-number (:chapters @state)))
                                description))
        chapter-id (:chapterId chapter)
        first-frame (assoc (make-draft-frame chapter-id 1)
                           :description (str/trim (or description "Chapter opening scene.")))]
    (swap! state
           (fn [s]
             (-> s
                 (update :chapters conj chapter)
                 (update :frames conj first-frame)
                 (update :revision inc))))
    {:chapter chapter
     :frame first-frame}))

(defn add-frame! [chapter-id]
  (let [chapter (chapter-by-id (:chapters @state) chapter-id)]
    (when-not chapter
      (throw (js/Error. "Chapter not found.")))
    (let [frame-number (next-frame-number (:frames @state) chapter-id)
          frame (make-draft-frame chapter-id frame-number)]
      (swap! state
             (fn [s]
               (-> s
                   (update :frames conj frame)
                   (update :revision inc))))
      frame)))

(declare find-frame-index)

(defn delete-frame! [frame-id]
  (let [snapshot @state
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (throw (js/Error. "Frame not found.")))
    (swap! state
           (fn [s]
             (-> s
                 (update :frames (fn [rows]
                                   (vec (concat (subvec rows 0 idx)
                                                (subvec rows (inc idx)))))
                 )
                 (update :revision inc))))
    frame))

(defn clear-frame-image! [frame-id]
  (let [snapshot @state
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (throw (js/Error. "Frame not found.")))
    (when (or (= "queued" (:status frame))
              (= "processing" (:status frame)))
      (throw (js/Error. "Cannot clear image while queued or processing.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageUrl] nil)
                 (assoc-in [:frames idx :status] "draft")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] nil)
                 (update :revision inc))))
    (get (:frames @state) idx)))

(defn initialize-state! []
  (let [openai-options (settings/image-settings)
        chapter-script-text (read-text default-chapter-script)
        prompts-text (read-text default-prompts)
        descriptions (parse-descriptions chapter-script-text)
        visual (parse-visual-prompts prompts-text)
        ref-bytes (read-bytes default-reference-image)
        page1-bytes (read-bytes page1-reference-image)
        chapter1 (make-chapter 1 "Chapter 1")
        chapter-id (:chapterId chapter1)
        frame1 {:frameId (new-uuid)
                :chapterId chapter-id
                :frameNumber 1
                :description (best-frame-description descriptions visual 1)
                :imageUrl (when page1-bytes (png-data-url page1-bytes))
                :status (if page1-bytes "ready" "draft")
                :reference true
                :createdAt (.toISOString (js/Date.))}
        frames (if page1-bytes
                 [frame1 (assoc (make-draft-frame chapter-id 2)
                               :description (best-frame-description descriptions visual 2))]
                 [(assoc (make-draft-frame chapter-id 1)
                         :description (best-frame-description descriptions visual 1))])]
    (reset! state
            {:chapterId (new-uuid)
             :descriptions descriptions
             :visual visual
             :chapters [chapter1]
             :frames frames
             :failedJobs []
             :processing false
             :revision 1
             :openaiOptions openai-options
             :referenceImageBytes ref-bytes})))

(initialize-state!)

(defn apply-persisted-state! [raw]
  (let [current @state
        persisted (js->clj raw :keywordize-keys true)]
    (when (or (nil? (:chapterId persisted))
              (nil? (:revision persisted))
              (nil? (:failedJobs persisted))
              (nil? (:chapters persisted))
              (nil? (:frames persisted)))
      (throw (js/Error. "Persisted state missing required fields.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc :chapterId (:chapterId persisted))
                 (assoc :revision (:revision persisted))
                 (assoc :failedJobs (vec (:failedJobs persisted)))
                 (assoc :chapters (vec (:chapters persisted)))
                 (assoc :frames (vec (:frames persisted)))
                 (assoc :descriptions (:descriptions current))
                 (assoc :visual (:visual current))
                 (assoc :referenceImageBytes (:referenceImageBytes current))
                 (assoc :openaiOptions (:openaiOptions current)))))
    @state))

(defn sync-state-from-storage! []
  (-> (store/load-or-init-state
       (clj->js (select-keys @state
                             [:chapterId :revision :failedJobs :chapters :frames
                              :descriptions :visual])))
      (.then apply-persisted-state!)
      (.catch (fn [err]
                (js/console.error "[robogene] storage sync failed" err)
                (throw err)))))

(defn persist-state! []
  (-> (store/save-state
       (clj->js (select-keys @state
                             [:chapterId :revision :failedJobs :chapters :frames])))
      (.then apply-persisted-state!)
      (.catch (fn [err]
                (js/console.error "[robogene] storage persist failed" err)
                (throw err)))))

(declare emit-state-changed!)

(defn apply-command!
  "Runs a domain mutation, persists state, and emits realtime stateChanged."
  [{:keys [run! reason]}]
  (try
    (let [result (run!)]
      (-> (persist-state!)
          (.then (fn [_]
                   (when (some? reason)
                     (emit-state-changed! reason))
                   {:result result
                    :snapshot @state}))))
    (catch :default err
      (js/Promise.reject err))))

(sync-state-from-storage!)

(defn chapter-order-map [chapters]
  (into {} (map (fn [chapter] [(:chapterId chapter) (:chapterNumber chapter)]) chapters)))

(defn sort-frames-for-chapter [chapters frames]
  (let [order-by-chapter (chapter-order-map chapters)]
    (->> frames
         (sort-by (fn [f]
                    [(get order-by-chapter (:chapterId f) 99999)
                     (:frameNumber f)]))
         vec)))

(defn completed-frames []
  (->> (sort-frames-for-chapter (:chapters @state) (:frames @state))
       (filter (fn [f] (not (str/blank? (or (:imageUrl f) "")))))
       vec))

(defn continuity-window [limit]
  (let [tail (take-last limit (completed-frames))]
    (if (empty? tail)
      "No previous frames yet."
      (str/join "\n" (map (fn [s] (str "Frame " (:frameNumber s) ": " (:description s) ".")) tail)))))

(defn build-prompt-for-frame [frame]
  (let [chapter (chapter-by-id (:chapters @state) (:chapterId frame))
        chapter-label (when chapter
                        (str "Chapter " (:chapterNumber chapter)
                             " theme: " (:description chapter)))]
    (str/join
     "\n\n"
     (filter seq
             ["Create ONE comic chapter image for the next frame."
              "Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer)."
              chapter-label
              (get-in @state [:visual :globalStyle] "")
              (str "Frame description for this chapter: " (:description frame))
              (str "User direction for this frame:\n" (or (:description frame) ""))
              (str "Chapter continuity memory:\n" (continuity-window 6))
              "Keep this image as the next chronological frame in the same chapter world."
              "Avoid title/header text overlays."]))))

(defn reference-images []
  (let [character-ref (:referenceImageBytes @state)]
    (if character-ref
      [{:bytes character-ref :name "character_ref.png"}]
      [])))

(defn set-image-generator! [f]
  (image-generator/set-image-generator! f))

(defn generate-image! [frame]
  (image-generator/generate-image! {:prompt (build-prompt-for-frame frame)
                                    :refs (reference-images)
                                    :options (:openaiOptions @state)}))

(defn next-queued-frame-index [frames]
  (first (keep-indexed (fn [idx frame]
                         (when (= "queued" (:status frame)) idx))
                       frames)))

(defn mark-frame-processing! [frame-id]
  (let [idx (find-frame-index (:frames @state) frame-id)]
    (when (number? idx)
      (swap! state
             (fn [s]
               (-> s
                   (assoc-in [:frames idx :status] "processing")
                   (assoc-in [:frames idx :startedAt] (.toISOString (js/Date.)))
                   (update :revision inc))))
      true)))

(defn mark-frame-ready! [frame-id image-data-url]
  (let [idx (find-frame-index (:frames @state) frame-id)]
    (when (number? idx)
      (swap! state
             (fn [s]
               (-> s
                   (assoc-in [:frames idx :imageUrl] image-data-url)
                   (assoc-in [:frames idx :status] "ready")
                   (assoc-in [:frames idx :error] nil)
                   (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
                   (update :revision inc))))
      true)))

(defn mark-frame-failed! [frame-id frame message]
  (let [idx (find-frame-index (:frames @state) frame-id)]
    (when (number? idx)
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
                     (update :revision inc)))))
      true)))

(declare process-step! active-queue-count)

(defn emit-state-changed! [reason]
  (let [snapshot @state
        payload (clj->js {:reason reason
                          :chapterId (:chapterId snapshot)
                          :revision (:revision snapshot)
                          :processing (:processing snapshot)
                          :pendingCount (active-queue-count (:frames snapshot))
                          :emittedAt (.toISOString (js/Date.))})]
    (js/console.info
     (str "[robogene] emit stateChanged"
          " reason=" reason
          " revision=" (:revision snapshot)
          " pending=" (active-queue-count (:frames snapshot))))
    (-> (realtime/publish-state-update! payload)
        (.catch (fn [err]
                  (js/console.warn
                   (str "[robogene] SignalR publish skipped/failed: "
                        (or (some-> err .-message) err))))))))

(defn log-generation-start! [frame queue-size]
  (let [chapter-number (some->> (:chapters @state)
                                (some (fn [chapter]
                                        (when (= (:chapterId chapter) (:chapterId frame))
                                          (:chapterNumber chapter)))))]
    (js/console.info
     (str "[robogene] generation started"
          " chapterNumber=" (or chapter-number "-")
          " frameNumber=" (:frameNumber frame)
          " frameId=" (:frameId frame)
          " queueSize=" queue-size))))

(defn log-generation-success! [frame duration-ms]
  (let [chapter-number (some->> (:chapters @state)
                                (some (fn [chapter]
                                        (when (= (:chapterId chapter) (:chapterId frame))
                                          (:chapterNumber chapter)))))]
    (js/console.info
     (str "[robogene] generation finished"
          " chapterNumber=" (or chapter-number "-")
          " frameNumber=" (:frameNumber frame)
          " frameId=" (:frameId frame)
          " durationMs=" duration-ms))))

(defn data-url-size-bytes [data-url]
  (let [prefix "data:image/png;base64,"]
    (if (and (string? data-url) (str/starts-with? data-url prefix))
      (try
        (.-length (js/Buffer.from (subs data-url (count prefix)) "base64"))
        (catch :default _
          -1))
      -1)))

(defn log-generation-result-summary! [frame duration-ms image-data-url]
  (let [opts (:openaiOptions @state)
        bytes (data-url-size-bytes image-data-url)]
    (js/console.info
     (str "[robogene] generation result"
          " frameId=" (:frameId frame)
          " frameNumber=" (:frameNumber frame)
          " durationMs=" duration-ms
          " bytes=" bytes
          " options=" (pr-str opts)))))

(defn log-generation-failed! [frame duration-ms err]
  (let [chapter-number (some->> (:chapters @state)
                                (some (fn [chapter]
                                        (when (= (:chapterId chapter) (:chapterId frame))
                                          (:chapterNumber chapter)))))]
    (js/console.error
     (str "[robogene] generation failed"
          " chapterNumber=" (or chapter-number "-")
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
            frame-id (:frameId frame)
            started-ms (.now js/Date)
            queue-size (active-queue-count (:frames snapshot))]
        (log-generation-start! frame queue-size)
        (if-not (mark-frame-processing! frame-id)
          (do
            (js/console.warn (str "[robogene] generation skipped; frame deleted before processing frameId=" frame-id))
            (process-step!))
          (-> (persist-state!)
              (.then (fn [_]
                       (emit-state-changed! "processing")
                       (generate-image! frame)))
              (.then (fn [image-data-url]
                       (let [duration-ms (- (.now js/Date) started-ms)]
                         (log-generation-success! frame duration-ms)
                         (log-generation-result-summary! frame duration-ms image-data-url))
                       (if (mark-frame-ready! frame-id image-data-url)
                         (do
                           (-> (persist-state!)
                               (.then (fn [_]
                                        (emit-state-changed! "ready")
                                        (process-step!)
                                        nil))))
                         (do
                           (js/console.warn (str "[robogene] generation result dropped; frame deleted frameId=" frame-id))
                           (process-step!)
                           nil))))
              (.catch (fn [err]
                        (log-generation-failed! frame (- (.now js/Date) started-ms) err)
                        (if (mark-frame-failed! frame-id frame (str (or (.-message err) err)))
                          (-> (persist-state!)
                              (.then (fn [_]
                                       (emit-state-changed! "failed")
                                       (process-step!)
                                       nil))
                              (.catch (fn [persist-err]
                                        (js/console.error "[robogene] persist failed after generation error" persist-err)
                                        (emit-state-changed! "failed")
                                        (process-step!)
                                        nil)))
                          (do
                            (js/console.warn (str "[robogene] generation failure dropped; frame deleted frameId=" frame-id))
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
