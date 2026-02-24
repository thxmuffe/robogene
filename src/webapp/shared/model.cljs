(ns webapp.shared.model
  (:require [clojure.string :as str]))

(defn frame-id-of [frame]
  (:frameId frame))

(defn ordered-chapters [chapters]
  (vec chapters))

(defn ordered-frames [frames]
  (vec frames))

(defn prev-next-by-id [frames frame-id]
  (loop [remaining (seq frames)
         prev nil]
    (if-let [current (first remaining)]
      (if (= (frame-id-of current) frame-id)
        {:prev prev
         :next (second remaining)
         :current current}
        (recur (rest remaining) current))
      {:prev nil :next nil :current nil})))

(defn relative-frame-by-id [frames frame-id delta]
  (let [{:keys [prev next current]} (prev-next-by-id frames frame-id)
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
   (when-let [[_ chapter frame] (re-matches #"^#/episode/([^/]+)/frame/(\d+)$" (or hash ""))]
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

(defn chapter-description [chapter]
  (let [desc (str/trim (or (:description chapter) ""))]
    (if (seq desc)
      desc
      "Chapter")))

(defn enrich-frame [state frame]
  (let [description (str/trim (or (:description frame) ""))]
    (if (or (str/blank? description) (generic-frame-text? description))
      (assoc frame :description description)
      frame)))

(defn normalize-chapters [state]
  (let [legacy-chapters (map (fn [chapter]
                               (-> chapter
                                   (assoc :chapterId (or (:chapterId chapter) (:episodeId chapter)))
                                   (dissoc :episodeId :episodeNumber)))
                             (or (:chapters state) (:episodes state) []))
        raw-chapters (vec legacy-chapters)
        raw-frames (or (:frames state) [])
        fallback-chapter-id (or (:chapterId (first raw-chapters))
                                (:chapterId (first raw-frames))
                                "chapter-1")
        chapters (if (seq raw-chapters)
                   (mapv (fn [chapter]
                           (-> chapter
                               (assoc :description (chapter-description chapter))))
                         raw-chapters)
                   [{:chapterId fallback-chapter-id
                     :description "Chapter"}])]
    chapters))

(defn normalize-state [state]
  (let [chapters (normalize-chapters state)
        default-chapter-id (:chapterId (first chapters))
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [chapter-id (or (:chapterId f) (:episodeId f) default-chapter-id)
                                          enriched (-> (enrich-frame state f)
                                                       (assoc :chapterId chapter-id))]
                                      (assoc enriched :frameDescription (frame-description enriched)))))
                             vec)
        frames-by-chapter (group-by :chapterId enriched-frames)
        chapters-with-frames (mapv (fn [chapter]
                                     (assoc chapter :frames (ordered-frames (get frames-by-chapter (:chapterId chapter) []))))
                                   chapters)]
    {:chapters chapters-with-frames
     :frames enriched-frames}))

(defn status-line [state chapters frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Chapters: " (count chapters)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
