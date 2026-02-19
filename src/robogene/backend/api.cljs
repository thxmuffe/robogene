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
         :frames []
         :failedJobs []
         :processing false
         :revision 0
         :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1")
         :referenceImageBytes nil}))

(defn frame-beat-text [frame-number]
  (or (some (fn [b] (when (= (:index b) frame-number) (:text b))) (:beats @state))
      (str "Frame " frame-number)))

(defn default-frame-direction-text [frame-number]
  (let [beat-text (frame-beat-text frame-number)
        page-prompt (get-in @state [:visual :pagePrompts frame-number] "")]
    (str/join "\n" (filter seq [beat-text page-prompt "Keep continuity with previous frames."]))))

(defn direction-text-for [beats visual frame-number]
  (let [beat-text (or (some (fn [b] (when (= (:index b) frame-number) (:text b))) beats)
                      (str "Frame " frame-number))
        page-prompt (get-in visual [:pagePrompts frame-number] "")]
    (str/join "\n" (filter seq [beat-text page-prompt "Keep continuity with previous frames."]))))

(defn make-draft-frame [frame-number]
  {:frameId (new-uuid)
   :frameNumber frame-number
   :beatText (frame-beat-text frame-number)
   :suggestedDirection (default-frame-direction-text frame-number)
   :directionText ""
   :status "draft"
   :createdAt (.toISOString (js/Date.))})

(defn next-frame-number [frames]
  (inc (reduce max 0 (map :frameNumber frames))))

(defn ensure-draft-frame! []
  (let [frames (:frames @state)
        missing-image? (some (fn [f] (str/blank? (or (:imageDataUrl f) ""))) frames)]
    (when-not missing-image?
      (let [frame-number (next-frame-number frames)]
        (swap! state
               (fn [s]
                 (-> s
                     (update :frames conj (make-draft-frame frame-number))
                     (update :revision inc))))))))

(defn initialize-state! []
  (let [storyboard-text (read-text default-storyboard)
        prompts-text (read-text default-prompts)
        beats (parse-beats storyboard-text)
        visual (parse-visual-prompts prompts-text)
        ref-bytes (read-bytes default-reference-image)
        page1-bytes (read-bytes page1-reference-image)
        frame1-beat (or (some (fn [b] (when (= (:index b) 1) (:text b))) beats) "Frame 1")
        frame1 {:frameId (new-uuid)
                :frameNumber 1
                :beatText frame1-beat
                :suggestedDirection (direction-text-for beats visual 1)
                :directionText frame1-beat
                :imageDataUrl (when page1-bytes (png-data-url page1-bytes))
                :status (if page1-bytes "ready" "draft")
                :reference true
                :createdAt (.toISOString (js/Date.))}
        frames (if page1-bytes
                 [frame1 (assoc (make-draft-frame 2)
                               :beatText (or (some (fn [b] (when (= (:index b) 2) (:text b))) beats)
                                             "Frame 2")
                               :suggestedDirection (direction-text-for beats visual 2))]
                 [(assoc (make-draft-frame 1)
                         :beatText frame1-beat
                         :suggestedDirection (direction-text-for beats visual 1))])]
    (reset! state
            {:storyId (new-uuid)
             :beats beats
             :visual visual
             :frames frames
             :failedJobs []
             :processing false
             :revision 1
             :model (or (.. js/process -env -ROBOGENE_IMAGE_MODEL) "gpt-image-1")
             :referenceImageBytes ref-bytes})))

(initialize-state!)

(defn completed-frames []
  (->> (:frames @state)
       (filter (fn [f] (not (str/blank? (or (:imageDataUrl f) "")))))
       (sort-by :frameNumber)
       vec))

(defn continuity-window [limit]
  (let [tail (take-last limit (completed-frames))]
    (if (empty? tail)
      "No previous frames yet."
      (str/join "\n" (map (fn [s] (str "Frame " (:frameNumber s) ": " (:beatText s) ".")) tail)))))

(defn build-prompt-for-frame [frame]
  (str/join
   "\n\n"
   (filter seq
           ["Create ONE comic story image for the next frame."
            "Character lock: Robot Emperor must match the attached reference identity (powdered white wig with side curls, pale robotic face, cyan glowing eyes, red cape with blue underlayer)."
            (get-in @state [:visual :globalStyle] "")
            (str "Storyboard beat for this frame: " (:beatText frame))
            (str "User direction for this frame:\n" (or (:directionText frame)
                                                         (:suggestedDirection frame)
                                                         ""))
            (str "Story continuity memory:\n" (continuity-window 6))
            "Keep this image as the next chronological frame in the same story world."
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

(defn generate-image! [frame]
  (let [api-key (.. js/process -env -OPENAI_API_KEY)]
    (if (not (seq api-key))
      (js/Promise.reject (js/Error. "Missing OPENAI_API_KEY in Function App settings."))
      (let [prompt (build-prompt-for-frame frame)
            ref-bytes (:referenceImageBytes @state)
            prev-frame (last (completed-frames))
            prev-bytes (image-data-url->bytes (:imageDataUrl prev-frame))
            refs (vec (filter some? [{:bytes ref-bytes :name "character_ref.png"}
                                     {:bytes prev-bytes :name "previous_frame.png"}]))
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
                           (str "data:image/png;base64," b64))))))))))

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
                         :beatText (:beatText frame)
                         :error message
                         :createdAt (.toISOString (js/Date.))}]
             (-> s
                 (assoc-in [:frames idx :status] "failed")
                 (assoc-in [:frames idx :error] message)
                 (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
                 (update :failedJobs (fn [rows] (vec (take 20 (cons failed rows)))))
                 (update :revision inc))))))

(declare process-step!)

(defn process-step! []
  (let [snapshot @state
        idx (next-queued-frame-index (:frames snapshot))]
    (if (nil? idx)
      (swap! state assoc :processing false)
      (let [frame (get (:frames snapshot) idx)]
        (mark-frame-processing! idx)
        (-> (generate-image! frame)
            (.then (fn [image-data-url]
                     (mark-frame-ready! idx image-data-url)
                     (ensure-draft-frame!)
                     (process-step!)
                     nil))
            (.catch (fn [err]
                      (mark-frame-failed! idx frame (str (or (.-message err) err)))
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

(defn state-response [request]
  (ensure-draft-frame!)
  (let [snapshot @state
        frames (:frames snapshot)
        pending-count (active-queue-count frames)]
    (json-response 200
                   {:storyId (:storyId snapshot)
                    :revision (:revision snapshot)
                    :processing (:processing snapshot)
                    :pendingCount pending-count
                    :frames frames
                    :failed (:failedJobs snapshot)}
                   request)))

(defn request-json [request]
  (-> (.json request)
      (.catch (fn [_] #js {}))))

(defn find-frame-index [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))

(defn queue-frame-response [request]
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
                                (assoc-in [:frames idx :directionText]
                                          (if (str/blank? (or direction ""))
                                            (or (get-in s [:frames idx :suggestedDirection]) "")
                                            direction))
                                (update :revision inc))))
                   (process-queue!)
                   (let [post @state]
                     (json-response 202
                                    {:accepted true
                                     :frame (get (:frames post) idx)
                                     :revision (:revision post)
                                     :pendingCount (active-queue-count (:frames post))}
                                    request))))))))))))

(.http app "get-state"
       #js {:methods #js ["GET"]
            :authLevel "anonymous"
            :route "state"
            :handler (fn [request]
                       (state-response request))})

(.http app "post-generate-frame"
       #js {:methods #js ["POST"]
            :authLevel "anonymous"
            :route "generate-frame"
            :handler (fn [request]
                       (queue-frame-response request))})

(.http app "options-preflight"
       #js {:methods #js ["OPTIONS"]
            :authLevel "anonymous"
            :route "{*path}"
            :handler (fn [request]
                       #js {:status 204
                            :headers (cors-headers request)})})

(defn init! [] true)
