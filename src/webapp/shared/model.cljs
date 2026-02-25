(ns webapp.shared.model
  (:require [clojure.string :as str]))

(defn frame-id-of [frame]
  (:frameId frame))

(defn ordered-frames [frames]
  (->> (or frames [])
       (sort-by (fn [frame]
                  [(or (:frameNumber frame) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt frame) "")
                   (or (:frameId frame) "")]))
       vec))

(defn relative-frame-by-id [frames frame-id delta]
  (let [{:keys [prev next current]}
        (loop [remaining (seq frames)
               prev nil]
          (if-let [current (first remaining)]
            (if (= (frame-id-of current) frame-id)
              {:prev prev
               :next (second remaining)
               :current current}
              (recur (rest remaining) current))
            {:prev nil :next nil :current nil}))
        first-frame (first frames)
        last-frame (last frames)]
    (cond
      (or (empty? frames) (zero? delta)) nil
      (pos? delta) (or next first-frame)
      (neg? delta) (or prev last-frame)
      (some? current) current
      :else first-frame)))

(defn parse-hash-route [hash]
  (or
   (when-let [[_ chapter frame] (re-matches #"^#/chapter/([^/]+)/frame/([^/]+)$" (or hash ""))]
     {:view :frame
      :chapter chapter
      :frame-id frame})
   {:view :index}))

(defn frame-hash [chapter frame-id]
  (str "#/chapter/" chapter "/frame/" frame-id))

(defn parse-json-safe [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:error "Expected JSON from services, got non-JSON response."})))

(defn generic-frame-text? [text]
  (boolean (re-matches #"(?i)^frame\s+\d+$" (str/trim (or text "")))))

(defn clamp-text [text limit]
  (let [v (str/trim (or text ""))]
    (if (> (count v) limit)
      (str (subs v 0 limit) "...")
      v)))

(defn frame-description [frame]
  (let [description (str/trim (or (:description frame) ""))
        preferred (if (and (seq description) (not (generic-frame-text? description)))
                    description
                    "No description yet.")]
    (let [final-text (clamp-text preferred 180)]
      (if (str/blank? final-text) "No description yet." final-text))))

(defn enrich-frame [frame]
  (let [description (str/trim (or (:description frame) ""))]
    (if (or (str/blank? description) (generic-frame-text? description))
      (assoc frame :description description)
      frame)))

(defn derived-chapters [state]
  (->> (or (:chapters state) [])
       (sort-by (fn [chapter]
                  [(or (:chapterNumber chapter) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt chapter) "")
                   (or (:chapterId chapter) "")]))
       vec))

(defn frames-for-chapter [frames chapter-id]
  (->> (or frames [])
       (filter (fn [frame] (= (:chapterId frame) chapter-id)))
       ordered-frames))

(defn derived-state [state]
  (let [chapters (derived-chapters state)
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [enriched (enrich-frame f)]
                                      (assoc enriched :frameDescription (frame-description enriched)))))
                             vec)]
    {:chapters chapters
     :frames enriched-frames}))

(defn status-line [state chapters frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Chapters: " (count chapters)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
