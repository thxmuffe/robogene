(ns robogene.backend.api
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            ["@azure/functions" :as azf]
            ["crypto" :as crypto]
            ["fs" :as fs]
            ["path" :as path]))

(def app (.-app azf))

(def backend-root (.resolve path js/__dirname ".." ".."))
(def assets-dir (.join path backend-root "assets"))

(def default-storyboard (.join path assets-dir "28_Municipal_Firmware_script.md"))
(def default-prompts (.join path assets-dir "28_Municipal_Firmware_image_prompts.md"))
(def default-reference-image (.join path assets-dir "robot_emperor_ep22_p01.png"))
(def page1-reference-image (.join path assets-dir "28_page_01_openai_refined.png"))

(defn read-text [file-path]
  (try
    (.readFileSync fs file-path "utf8")
    (catch :default _
      "")))

(defn read-bytes [file-path]
  (try
    (.readFileSync fs file-path)
    (catch :default _
      nil)))

(defn parse-beats [markdown]
  (let [section (or (second (re-find #"(?is)##\\s*Page-by-page beats([\\s\\S]*?)(?:\\n##\\s|$)" markdown))
                    markdown)]
    (->> (str/split-lines section)
         (keep (fn [line]
                 (when-let [[_ idx txt] (re-find #"^\\s*(\\d+)\\.\\s+(.+)$" line)]
                   {:index (js/Number idx) :text (str/trim txt)})))
         (sort-by :index)
         vec)))

(defn parse-visual-prompts [markdown]
  (let [global-style (or (some-> (re-find #"(?is)##\\s*Global style prompt.*?\\n([^\\n]+)" markdown)
                                 second
                                 str/trim)
                         "")
        section (or (second (re-find #"(?is)##\\s*Page prompts([\\s\\S]*)" markdown)) markdown)
        page-prompts (->> (str/split-lines section)
                          (keep (fn [line]
                                  (when-let [[_ idx txt] (re-find #"^\\s*(\\d+)\\.\\s+(.+)$" line)]
                                    [(js/Number idx) (str/trim txt)])))
                          (into {}))]
    {:globalStyle global-style :pagePrompts page-prompts}))

(defn png-data-url [buffer]
  (str "data:image/png;base64," (.toString buffer "base64")))

(defn new-uuid []
  (.randomUUID crypto))

(defonce state
  (atom {:storyId nil
         :beats []
         :visual {:globalStyle "" :pagePrompts {}}
         :history []
         :pendingJobs []
         :failedJobs []
         :cursor 2
         :processing false
         :revision 0
         :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1")
         :referenceImageBytes nil}))

(defn scene-beat-text [scene-number]
  (or (some (fn [b] (when (= (:index b) scene-number) (:text b))) (:beats @state))
      (str "Scene " scene-number)))

(defn default-direction-text [scene-number]
  (let [beat-text (scene-beat-text scene-number)
        page-prompt (get-in @state [:visual :pagePrompts scene-number] "")]
    (str/join "\n" (filter seq [beat-text page-prompt "Keep continuity with previous scenes."]))))

(defn initialize-state! []
  (let [storyboard-text (read-text default-storyboard)
        prompts-text (read-text default-prompts)
        beats (parse-beats storyboard-text)
        visual (parse-visual-prompts prompts-text)
        ref-bytes (read-bytes default-reference-image)
        page1-bytes (read-bytes page1-reference-image)
        page1-beat (or (some (fn [b] (when (= (:index b) 1) (:text b))) beats) "Scene 1")
        page1-scene (when page1-bytes
                      {:sceneNumber 1
                       :beatText page1-beat
                       :continuityNote page1-beat
                       :imageDataUrl (png-data-url page1-bytes)
                       :reference true
                       :createdAt (.toISOString (js/Date.))})]
    (reset! state
            {:storyId (new-uuid)
             :beats beats
             :visual visual
             :history (if page1-scene [page1-scene] [])
             :pendingJobs []
             :failedJobs []
             :cursor 2
             :processing false
             :revision 1
             :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1")
             :referenceImageBytes ref-bytes})))

(initialize-state!)

(defn continuity-window [limit]
  (let [tail (take-last limit (:history @state))]
    (if (empty? tail)
      "No previous scenes yet."
      (str/join "\n" (map (fn [s] (str "Scene " (:sceneNumber s) ": " (:beatText s) ".")) tail)))))

(defn build-prompt-for-scene [scene-number beat-text direction-text]
  (str/join
   "\n\n"
   (filter seq
           ["Create ONE comic story image for the next scene."
            "Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer)."
            (get-in @state [:visual :globalStyle] "")
            (str "Storyboard beat for this scene: " beat-text)
            (str "User direction for this scene:\n" (if (seq direction-text) direction-text (default-direction-text scene-number)))
            (str "Story continuity memory:\n" (continuity-window 6))
            "Keep this image as the next chronological scene in the same story world."
            "Avoid title/header text overlays."])))

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

(defn generate-image! [scene-number beat-text direction-text]
  (let [api-key (.. js/process -env -OPENAI_API_KEY)]
    (if (not (seq api-key))
      (js/Promise.reject (js/Error. "Missing OPENAI_API_KEY in Function App settings."))
      (let [prompt (build-prompt-for-scene scene-number beat-text direction-text)
            ref-bytes (:referenceImageBytes @state)
            prev-scene (last (:history @state))
            prev-bytes (image-data-url->bytes (:imageDataUrl prev-scene))
            refs (vec (filter some? [{:bytes ref-bytes :name "character_ref.png"}
                                     {:bytes prev-bytes :name "previous_scene.png"}]))
            request-promise
            (if (seq refs)
              (let [form (js/FormData.)]
                (.append form "model" (:model @state))
                (.append form "prompt" prompt)
                (.append form "size" "1536x1024")
                (doseq [ref refs]
                  (let [blob (js/Blob. (clj->js [(:bytes ref)]) #js {:type "image/png"})]
                    (.append form "image[]" blob (:name ref))))
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
                                                           :size "1536x1024"}))}))]
        (-> request-promise
            (.then (fn [{:keys [ok status body]}]
                     (if-not ok
                       (throw (js/Error. (str "OpenAI error " status ": " (.stringify js/JSON body))))
                       (let [data (gobj/get body "data")
                             first-item (when (and data (> (.-length data) 0)) (aget data 0))
                             b64 (when first-item (gobj/get first-item "b64_json"))]
                         (if (not b64)
                           (throw (js/Error. (str "Unexpected OpenAI response: " (.stringify js/JSON body))))
                           {:sceneNumber scene-number
                            :beatText beat-text
                            :continuityNote beat-text
                            :imageDataUrl (str "data:image/png;base64," b64)
                            :createdAt (.toISOString (js/Date.))}))))))))))

(defn next-queued-index [jobs]
  (first (keep-indexed (fn [idx job] (when (= "queued" (:status job)) idx)) jobs)))

(defn cleanup-pending-jobs! []
  (let [now (.now js/Date)
        hold-ms 9000
        before (count (:pendingJobs @state))]
    (swap! state update :pendingJobs
           (fn [jobs]
             (vec (filter (fn [job]
                            (if (or (= "queued" (:status job)) (= "processing" (:status job)))
                              true
                              (if-let [completed-at (:completedAt job)]
                                (< (- now (.parse js/Date completed-at)) hold-ms)
                                true)))
                          jobs))))
    (when (not= before (count (:pendingJobs @state)))
      (swap! state update :revision inc))))

(defn process-queue! []
  (when-not (:processing @state)
    (swap! state assoc :processing true)
    (letfn [(step []
              (let [snapshot @state
                    idx (next-queued-index (:pendingJobs snapshot))]
                (if (nil? idx)
                  (swap! state assoc :processing false)
                  (let [job (get (:pendingJobs snapshot) idx)]
                    (swap! state (fn [s]
                                   (-> s
                                       (assoc-in [:pendingJobs idx :status] "processing")
                                       (assoc-in [:pendingJobs idx :startedAt] (.toISOString (js/Date.)))
                                       (update :revision inc))))
                    (-> (generate-image! (:sceneNumber job) (:beatText job) (:directionText job))
                        (.then (fn [scene]
                                 (swap! state
                                        (fn [s]
                                          (-> s
                                              (update :history (fn [history]
                                                                 (->> (conj history scene)
                                                                      (sort-by :sceneNumber)
                                                                      vec)))
                                              (assoc-in [:pendingJobs idx :status] "completed")
                                              (assoc-in [:pendingJobs idx :completedAt] (.toISOString (js/Date.)))
                                              (update :revision inc))))
                                 (step)
                                 nil))
                        (.catch (fn [err]
                                  (swap! state
                                         (fn [s]
                                           (let [message (str (or (.-message err) err))
                                                 failed {:jobId (:jobId job)
                                                         :sceneNumber (:sceneNumber job)
                                                         :beatText (:beatText job)
                                                         :error message
                                                         :createdAt (.toISOString (js/Date.))}]
                                             (-> s
                                                 (assoc-in [:pendingJobs idx :status] "failed")
                                                 (assoc-in [:pendingJobs idx :error] message)
                                                 (assoc-in [:pendingJobs idx :completedAt] (.toISOString (js/Date.)))
                                                 (update :failedJobs (fn [rows] (vec (take 20 (cons failed rows)))))
                                                 (update :revision inc)))))
                                  (step)
                                  nil)))))))]
      (step))))

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

(defn state-response [request]
  (cleanup-pending-jobs!)
  (let [snapshot @state
        cursor (:cursor snapshot)
        total-scenes (count (:beats snapshot))]
    (json-response 200
                   {:storyId (:storyId snapshot)
                    :revision (:revision snapshot)
                    :cursor cursor
                    :totalScenes total-scenes
                    :nextSceneNumber cursor
                    :nextDefaultDirection (if (<= cursor total-scenes)
                                            (default-direction-text cursor)
                                            "")
                    :processing (:processing snapshot)
                    :pendingCount (count (:pendingJobs snapshot))
                    :pending (:pendingJobs snapshot)
                    :history (:history snapshot)
                    :failed (:failedJobs snapshot)}
                   request)))

(defn request-json [request]
  (-> (.json request)
      (.catch (fn [_] #js {}))))

(defn generate-next-response [request]
  (-> (request-json request)
      (.then
       (fn [body]
         (cleanup-pending-jobs!)
         (let [snapshot @state
               cursor (:cursor snapshot)
               total-scenes (count (:beats snapshot))]
           (if (> cursor total-scenes)
             (json-response 409 {:done true
                                 :error "Storyboard complete."
                                 :history (:history snapshot)}
                            request)
             (let [job {:jobId (new-uuid)
                        :sceneNumber cursor
                        :beatText (scene-beat-text cursor)
                        :directionText (str/trim (str (or (.-direction body) "")))
                        :status "queued"
                        :queuedAt (.toISOString (js/Date.))}]
               (swap! state
                      (fn [s]
                        (-> s
                            (update :pendingJobs conj job)
                            (update :cursor inc)
                            (update :revision inc))))
               (process-queue!)
               (let [post @state
                     next-cursor (:cursor post)
                     total (count (:beats post))]
                 (json-response 202
                                {:accepted true
                                 :job job
                                 :revision (:revision post)
                                 :pendingCount (count (:pendingJobs post))
                                 :nextSceneNumber next-cursor
                                 :nextDefaultDirection (if (<= next-cursor total)
                                                         (default-direction-text next-cursor)
                                                         "")}
                                request)))))))))

(.http app "state"
       #js {:methods #js ["GET"]
            :authLevel "anonymous"
            :route "state"
            :handler state-response})

(.http app "generate-next"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "generate-next"
            :handler generate-next-response})

(.http app "preflight"
       #js {:methods #js ["OPTIONS"]
            :authLevel "anonymous"
            :route "{*path}"
            :handler (fn [request]
                       #js {:status 204
                            :headers (cors-headers request)})})

(defn init! [] true)
