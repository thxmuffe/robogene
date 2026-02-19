(ns robogene.frontend.events.model
  (:require [clojure.string :as str]))

(def legacy-draft-id "__legacy_draft__")

(defn frame-number-of [frame]
  (or (:frameNumber frame) (:sceneNumber frame)))

(defn parse-hash-route [hash]
  (if-let [[_ episode frame] (re-matches #"^#/episode/([^/]+)/frame/(\d+)$" (or hash ""))]
    {:view :frame
     :episode episode
     :frame-number (js/Number frame)}
    {:view :index}))

(defn frame-hash [episode frame-number]
  (str "#/episode/" episode "/frame/" frame-number))

(defn parse-json-safe [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:error "Expected JSON from backend, got non-JSON response."})))

(defn frame-from-history [idx h]
  (let [frame-number (frame-number-of h)]
    {:frameId (or (:frameId h) (str "legacy-ready-" frame-number "-" idx))
     :frameNumber frame-number
     :beatText (:beatText h)
     :suggestedDirection ""
     :directionText ""
     :imageDataUrl (:imageDataUrl h)
     :status "ready"
     :reference (:reference h)
     :createdAt (:createdAt h)}))

(defn frame-from-pending [idx p]
  (let [frame-number (frame-number-of p)]
    {:frameId (or (:frameId p) (:jobId p) (str "legacy-pending-" frame-number "-" idx))
     :frameNumber frame-number
     :beatText (:beatText p)
     :suggestedDirection (:directionText p)
     :directionText (:directionText p)
     :error (:error p)
     :status (or (:status p) "queued")
     :createdAt (:queuedAt p)}))

(defn legacy-draft-frame [state existing-frames]
  (let [max-frame (reduce max 0 (map frame-number-of existing-frames))
        suggested (or (:nextDefaultDirection state) "")
        next-num (or (:nextFrameNumber state) (inc max-frame))]
    {:frameId legacy-draft-id
     :frameNumber next-num
     :beatText (str "Frame " next-num)
     :suggestedDirection suggested
     :directionText suggested
     :status "draft"
     :createdAt (.toISOString (js/Date.))}))

(defn normalize-state [state]
  (if (seq (:frames state))
    {:backend-mode :frames
     :frames (->> (:frames state)
                  (map (fn [f] (assoc f :frameNumber (frame-number-of f))))
                  (sort-by frame-number-of >)
                  vec)}
    (let [ready (->> (or (:history state) [])
                     (map-indexed frame-from-history))
          pending (->> (or (:pending state) [])
                       (map-indexed frame-from-pending))
          frames (vec (concat ready pending))
          frames-with-draft (if (some (fn [f] (str/blank? (or (:imageDataUrl f) ""))) frames)
                              frames
                              (conj frames (legacy-draft-frame state frames)))]
      {:backend-mode :legacy
       :frames (->> (if (seq frames-with-draft)
                      frames-with-draft
                      [(legacy-draft-frame state [])])
                    (sort-by frame-number-of >)
                    vec)})))

(defn merged-frame-inputs [existing frames]
  (reduce (fn [acc frame]
            (let [frame-id (:frameId frame)]
              (assoc acc frame-id
                     (or (get existing frame-id)
                         (:directionText frame)
                         (:suggestedDirection frame)
                         ""))))
          {}
          frames))

(defn status-line [state frames]
  (let [pending (or (:pendingCount state)
                    (count (filter (fn [f]
                                     (or (= "queued" (:status f))
                                         (= "processing" (:status f))))
                                   frames)))]
    (str "Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
