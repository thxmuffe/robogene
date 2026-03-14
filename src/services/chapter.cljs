(ns services.chapter
  (:require [clojure.string :as str]
            [services.image-generator :as image-generator]
            [services.realtime :as realtime]
            [host.settings :as settings]
            [services.azure-store :as store]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

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
           :sagaMeta {:name "Robot Emperor"
                      :description ""}
           :saga []
           :roster []
           :frames []
           :failedJobs []
           :processing false
           :revision 0
           :openaiOptions openai-options
           :referenceImageBytes nil})))

(defn make-chapter [chapter-number name description]
  {:chapterId (new-uuid)
   :chapterNumber chapter-number
   :name (str/trim (or name ""))
   :description (str/trim (or description ""))
   :createdAt (.toISOString (js/Date.))})

(defn make-character [character-number name description]
  {:characterId (new-uuid)
   :characterNumber character-number
   :name (str/trim (or name ""))
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

(defn make-draft-frame
  ([chapter-id frame-number]
   (make-draft-frame chapter-id frame-number "saga"))
  ([chapter-id frame-number owner-type]
  {:frameId (new-uuid)
   :chapterId chapter-id
   :ownerType (or owner-type "saga")
   :frameNumber frame-number
   :description (default-frame-description frame-number)
   :status "draft"
   :createdAt (.toISOString (js/Date.))}))

(defn next-chapter-number [saga]
  (inc (reduce max 0 (map :chapterNumber saga))))

(defn next-character-number [roster]
  (inc (reduce max 0 (map :characterNumber roster))))

(defn chapter-by-id [saga chapter-id]
  (some (fn [chapter] (when (= (:chapterId chapter) chapter-id) chapter)) saga))

(defn character-by-id [roster character-id]
  (some (fn [character] (when (= (:characterId character) character-id) character)) roster))

(defn frames-for-owner [frames owner-id owner-type]
  (->> frames
       (filter (fn [f]
                 (and (= (:chapterId f) owner-id)
                      (= (or (:ownerType f) "saga") (or owner-type "saga")))))
       (sort-by :frameNumber)
       vec))

(defn next-frame-number [frames owner-id owner-type]
  (inc (reduce max 0 (map :frameNumber (frames-for-owner frames owner-id owner-type)))))

(defn entity-type->meta [entity-label]
  (if (= "character" (str entity-label))
    {:label "character"
     :collection-key :roster
     :id-key :characterId
     :number-key :characterNumber
     :default-name "Character"
     :not-found-msg "Character not found."}
    {:label "chapter"
     :collection-key :saga
     :id-key :chapterId
     :number-key :chapterNumber
     :default-name "Chapter"
     :not-found-msg "Chapter not found."}))

(defn entity-by-id [entities id-key entity-id]
  (some (fn [entity] (when (= (id-key entity) entity-id) entity)) entities))

(defn next-entity-number [entities number-key]
  (inc (reduce max 0 (map number-key entities))))

(defn make-entity [entity-label number name description]
  (if (= "character" (str entity-label))
    (make-character number name description)
    (make-chapter number name description)))

(defn add-entity! [entity-label name description]
  (let [{:keys [collection-key id-key default-name]} (entity-type->meta entity-label)
        entities (collection-key @state)
        number-key (if (= "character" (str entity-label)) :characterNumber :chapterNumber)
        entity-number (next-entity-number entities number-key)
        normalized-name (str/trim (or name ""))
        normalized-description (str/trim (or description ""))
        entity (make-entity entity-label
                            entity-number
                            (if (str/blank? normalized-name)
                              (str default-name " " entity-number)
                              normalized-name)
                            normalized-description)
        entity-id (id-key entity)
        first-frame (assoc (make-draft-frame entity-id 1 (if (= "character" (str entity-label)) "character" "saga"))
                           :description (str/trim
                                         (or (not-empty normalized-description)
                                             (if (= "character" (str entity-label))
                                               "Character reference portrait."
                                               "Chapter opening scene."))))]
    (swap! state
           (fn [s]
             (-> s
                 (update collection-key conj entity)
                 (update :frames conj first-frame)
                 (update :revision inc))))
    {:entity entity
     :frame first-frame}))

(defn add-chapter! [name]
  (let [{:keys [entity frame]} (add-entity! "chapter" name "")]
    {:chapter entity
     :frame frame}))

(defn add-character! [name]
  (let [{:keys [entity frame]} (add-entity! "character" name "")]
    {:character entity
     :frame frame}))

(defn add-chapter-with-details! [name description]
  (let [{:keys [entity frame]} (add-entity! "chapter" name description)]
    {:chapter entity
     :frame frame}))

(defn add-chapter-with-saga-roster! [saga-id roster-id name description]
  (let [saga-meta (:sagaMeta @state)]
    (when-not (and saga-meta (= (:sagaId saga-meta) saga-id))
      (throw (js/Error. "Saga not found.")))
    (let [roster (entity-by-id (:roster @state) :characterId roster-id)]
      (when-not roster
        (throw (js/Error. "Roster not found.")))
      (add-chapter-with-details! name description))))

(defn add-roster! [name description]
  (let [{:keys [entity frame]} (add-entity! "character" name description)]
    entity))

(defn add-character-with-details! [name description]
  (let [{:keys [entity frame]} (add-entity! "character" name description)]
    {:character entity
     :frame frame}))

(defn add-frame!
  ([chapter-id]
   (add-frame! chapter-id "saga"))
  ([owner-id owner-type]
   (let [owner-type (or owner-type "saga")
         {:keys [collection-key id-key not-found-msg]} (entity-type->meta (if (= owner-type "character") "character" "chapter"))
         owner (entity-by-id (collection-key @state) id-key owner-id)]
    (when-not owner
      (throw (js/Error. not-found-msg)))
    (let [frame-number (next-frame-number (:frames @state) owner-id owner-type)
          frame (make-draft-frame owner-id frame-number owner-type)]
      (swap! state
             (fn [s]
               (-> s
                   (update :frames conj frame)
                   (update :revision inc))))
      frame))))

(defn update-entity-details! [entity-label entity-id name description]
  (let [{:keys [collection-key id-key not-found-msg]} (entity-type->meta entity-label)
        normalized-name (some-> (or name "") str str/trim)
        normalized-description (or (some-> (or description "") str str/trim) "")
        idx (first (keep-indexed (fn [i entity]
                                   (when (= (id-key entity) entity-id)
                                     i))
                                 (collection-key @state)))]
    (when-not (number? idx)
      (throw (js/Error. not-found-msg)))
    (when (str/blank? normalized-name)
      (throw (js/Error. (if (= "character" (str entity-label))
                          "Missing character name."
                          "Missing chapter name."))))
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [collection-key idx :name] normalized-name)
                 (assoc-in [collection-key idx :description] normalized-description)
                 (update :revision inc))))
    (get-in @state [collection-key idx])))

(defn update-chapter-details! [chapter-id name description]
  (update-entity-details! "chapter" chapter-id name description))

(defn update-character-details! [character-id name description]
  (update-entity-details! "character" character-id name description))

(defn update-saga-meta! [name description]
  (let [normalized-name (some-> (or name "") str str/trim)
        normalized-description (or (some-> (or description "") str str/trim) "")]
    (when (str/blank? normalized-name)
      (throw (js/Error. "Missing saga name.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc :sagaMeta {:name normalized-name
                                   :description normalized-description})
                 (update :revision inc))))
    (:sagaMeta @state)))

(defn add-saga! [name description]
  (let [saga-id (new-uuid)]
    (let [normalized-name (some-> (or name "") str str/trim)
          normalized-description (or (some-> (or description "") str str/trim) "")]
      (when (str/blank? normalized-name)
        (throw (js/Error. "Missing saga name.")))
      (swap! state
             (fn [s]
               (-> s
                   (assoc :sagaMeta {:sagaId saga-id
                                     :name normalized-name
                                     :description normalized-description})
                   (update :revision inc))))
      {:sagaId saga-id
       :name normalized-name
       :description normalized-description})))

(defn normalize-entity [entity-label entity]
  (let [name (some-> (or (:name entity) (:description entity)) str str/trim)
        description (if (contains? entity :name)
                      (some-> (:description entity) str str/trim)
                      "")]
    (assoc entity
           :name (or name "")
           :description (or description ""))))

(defn delete-entity! [entity-label entity-id]
  (let [{:keys [collection-key id-key not-found-msg]} (entity-type->meta entity-label)
        owner-type (if (= "character" (str entity-label)) "character" "saga")
        snapshot @state
        entity (entity-by-id (collection-key snapshot) id-key entity-id)]
    (when-not entity
      (throw (js/Error. not-found-msg)))
    (swap! state
           (fn [s]
             (-> s
                 (update collection-key (fn [rows]
                                          (->> (or rows [])
                                               (remove (fn [row] (= (id-key row) entity-id)))
                                               vec)))
                 (update :frames (fn [rows]
                                   (->> (or rows [])
                                        (remove (fn [frame]
                                                  (and (= (:chapterId frame) entity-id)
                                                       (= (or (:ownerType frame) "saga") owner-type))))
                                        vec)))
                 (update :revision inc))))
    entity))

(defn delete-chapter! [chapter-id]
  (delete-entity! "chapter" chapter-id))

(defn delete-character! [character-id]
  (delete-entity! "character" character-id))

(declare find-frame-index)

(defn delete-frames! [frame-ids]
  (let [frame-id-set (set frame-ids)
        snapshot @state
        frames (:frames snapshot)
        deleted-frames (->> frames
                            (filter (fn [frame]
                                      (contains? frame-id-set (:frameId frame))))
                            vec)]
    (when (not= (count deleted-frames) (count frame-id-set))
      (throw (js/Error. "Frame not found.")))
    (swap! state
           (fn [s]
             (-> s
                 (update :frames (fn [rows]
                                   (->> (or rows [])
                                        (remove (fn [frame]
                                                  (contains? frame-id-set (:frameId frame))))
                                        vec)))
                 (update :revision inc))))
    deleted-frames))

(defn delete-frame! [frame-id]
  (first (delete-frames! [frame-id])))

(defn delete-empty-frames! [owner-id owner-type]
  (let [normalized-owner-type (str/lower-case (str (or owner-type "saga")))
        snapshot @state
        frames (:frames snapshot)
        empty-frames (->> frames
                          (filter (fn [frame]
                                    (and (= (:chapterId frame) owner-id)
                                         (= (str/lower-case (str (or (:ownerType frame) "saga")))
                                            normalized-owner-type)
                                         (str/blank? (or (:imageUrl frame) "")))))
                          vec)
        deleted-frame-ids (mapv :frameId empty-frames)]
    (delete-frames! deleted-frame-ids)
    {:deletedCount (count deleted-frame-ids)
     :frameIds deleted-frame-ids}))

(defn update-frame-description! [frame-id description]
  (let [snapshot @state
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        normalized-description (or (some-> (or description "") str str/trim) "")
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (throw (js/Error. "Frame not found.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :description] normalized-description)
                 (update :revision inc))))
    (assoc frame :description normalized-description)))

(defn initialize-state! []
  (let [openai-options (settings/image-settings)]
    (reset! state
            {:chapterId (new-uuid)
             :descriptions {}
             :visual {}
             :sagaMeta {:name "Robot Emperor"
                        :description ""}
             :saga []
             :roster []
             :frames []
             :failedJobs []
             :processing false
             :revision 0
             :openaiOptions openai-options
             :referenceImageBytes nil})))

(initialize-state!)

(defn apply-persisted-state! [raw]
  (let [current @state
        persisted (js->clj raw :keywordize-keys true)]
    (when (or (nil? (:chapterId persisted))
              (nil? (:revision persisted))
              (nil? (:failedJobs persisted))
              (nil? (:saga persisted))
              (nil? (:frames persisted)))
      (throw (js/Error. "Persisted state missing required fields.")))
    (swap! state
           (fn [s]
             (-> s
                 (assoc :chapterId (:chapterId persisted))
                 (assoc :revision (:revision persisted))
                 (assoc :failedJobs (vec (:failedJobs persisted)))
                 (assoc :sagaMeta (let [meta* (:sagaMeta persisted)
                                        name (some-> (or (:name meta*) "Robot Emperor") str str/trim not-empty)
                                        description (some-> (or (:description meta*) "") str str/trim)
                                        saga-id (or (:sagaId meta*) "")]
                                    {:sagaId saga-id
                                     :name (or name "Robot Emperor")
                                     :description (or description "")}))
                 (assoc :saga (->> (or (:saga persisted) [])
                                   (map #(normalize-entity "chapter" %))
                                   vec))
                 (assoc :roster (->> (or (:roster persisted) [])
                                     (map #(normalize-entity "character" %))
                                     vec))
                 (assoc :frames (vec (:frames persisted)))
                 (assoc :descriptions (:descriptions current))
                 (assoc :visual (:visual current))
                 (assoc :referenceImageBytes (:referenceImageBytes current))
                 (assoc :openaiOptions (:openaiOptions current)))))
    @state))

(defn sync-state-from-storage! []
  (-> (store/load-or-init-state
       (clj->js (select-keys @state
                             [:chapterId :revision :failedJobs :saga :roster :frames
                              :sagaMeta :descriptions :visual])))
      (.then apply-persisted-state!)
      (.catch (fn [err]
                (js/console.error "[robogene] storage sync failed" err)
                (throw err)))))

(defn persist-state! []
  (-> (store/save-state
       (clj->js (select-keys @state
                             [:chapterId :revision :failedJobs :sagaMeta :saga :roster :frames])))
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

(defn chapter-order-map [saga]
  (into {} (map (fn [chapter] [(:chapterId chapter) (:chapterNumber chapter)]) saga)))

(defn sort-frames-for-chapter [saga frames]
  (let [order-by-chapter (chapter-order-map saga)]
    (->> frames
         (sort-by (fn [f]
                    [(get order-by-chapter (:chapterId f) 99999)
                     (:frameNumber f)]))
         vec)))

(defn completed-frames []
  (->> (sort-frames-for-chapter (:saga @state) (:frames @state))
       (filter (fn [f] (not (str/blank? (or (:imageUrl f) "")))))
       vec))

(defn completed-frames-for-owner [owner-id owner-type]
  (->> (completed-frames)
       (filter (fn [frame]
                 (and (= (:chapterId frame) owner-id)
                      (= (or (:ownerType frame) "saga") owner-type))))
       vec))

(defn continuity-window [limit owner-id owner-type]
  (let [tail (take-last limit (completed-frames-for-owner owner-id owner-type))]
    (if (empty? tail)
      "No previous frames yet."
      (str/join "\n" (map (fn [s] (str "Frame " (:frameNumber s) ": " (:description s) ".")) tail)))))

(defn build-prompt-for-frame [frame]
  (let [owner-type (or (:ownerType frame) "saga")
        chapter (chapter-by-id (:saga @state) (:chapterId frame))
        character (character-by-id (:roster @state) (:chapterId frame))
        chapter-label (when chapter
                        (str "Chapter " (:chapterNumber chapter)
                             " theme: " (or (:name chapter) "")
                             (when (seq (or (:description chapter) ""))
                               (str ". Details: " (:description chapter)))))]
    (str/join
     "\n\n"
     (filter seq
             ["Create ONE comic chapter image for the next frame."
              "Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer)."
              (when character
                (str "Character profile: "
                     (or (:name character) "")
                     (when (seq (or (:description character) ""))
                       (str ". " (:description character)))))
              chapter-label
              (get-in @state [:visual :globalStyle] "")
              (str "Frame description for this chapter: " (:description frame))
              (str "User direction for this frame:\n" (or (:description frame) ""))
              (str "Chapter continuity memory:\n" (continuity-window 6 (:chapterId frame) owner-type))
              "Keep this image as the next chronological frame in the same chapter world."
              "Avoid title/header text overlays."]))))

(defn reference-images []
  (let [character-ref (:referenceImageBytes @state)]
    (if character-ref
      [{:bytes character-ref :name "character_ref.png"}]
      [])))

(defn set-image-generator! [f]
  (image-generator/set-image-generator! f))

(defn generate-image-from-prompt-only! [frame]
  (image-generator/generate-image! {:prompt (build-prompt-for-frame frame)
                                    :refs []
                                    :options (:openaiOptions @state)}))

(defn generate-image! [frame]
  (let [owner-type (or (:ownerType frame) "saga")]
    (if (= "character" (str owner-type))
      (generate-image-from-prompt-only! frame)
      (image-generator/generate-image! {:prompt (build-prompt-for-frame frame)
                                        :refs (reference-images)
                                        :options (:openaiOptions @state)}))))

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
  (let [chapter-number (some->> (:saga @state)
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
  (let [chapter-number (some->> (:saga @state)
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

(defn log-image-persisted! [frame-id]
  (let [snapshot @state
        frame (some (fn [f]
                      (when (= (:frameId f) frame-id) f))
                    (:frames snapshot))]
    (when frame
      (js/console.info
       (str "[robogene] image persisted"
            " frameId=" frame-id
            " status=" (:status frame)
            " revision=" (:revision snapshot)
            " ownerType=" (or (:ownerType frame) "saga")
            " ownerId=" (:chapterId frame))))))

(defn log-generation-failed! [frame duration-ms err]
  (let [chapter-number (some->> (:saga @state)
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
                                        (log-image-persisted! frame-id)
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
