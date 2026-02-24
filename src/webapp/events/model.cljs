(ns webapp.events.model
  (:require [clojure.string :as str]))

(defn chapter-number-of [chapter]
  (:chapterNumber chapter))

(defn frame-number-of [frame]
  (:frameNumber frame))

(defn ordered-chapters [chapters]
  (->> chapters
       (sort-by chapter-number-of)
       vec))

(defn ordered-frames [frames]
  (->> frames
       (sort-by frame-number-of)
       vec))

(defn frame-index-by-number [frames frame-number]
  (first (keep-indexed (fn [idx frame]
                         (when (= (frame-number-of frame) frame-number) idx))
                       frames)))

(defn parse-hash-route [hash]
  (or
   (when-let [[_ chapter frame] (re-matches #"^#/chapter/([^/]+)/frame/(\d+)$" (or hash ""))]
     {:view :frame
      :chapter chapter
      :frame-number (js/Number frame)})
   (when-let [[_ chapter frame] (re-matches #"^#/episode/([^/]+)/frame/(\d+)$" (or hash ""))]
     {:view :frame
      :chapter chapter
      :frame-number (js/Number frame)})
   {:view :index}))

(defn frame-hash [chapter frame-number]
  (str "#/chapter/" chapter "/frame/" frame-number))

(defn parse-json-safe [text]
  (try
    (js->clj (.parse js/JSON text) :keywordize-keys true)
    (catch :default _
      {:error "Expected JSON from services, got non-JSON response."})))

(defn generic-frame-text? [text frame-number]
  (= (str/lower-case (str/trim (or text "")))
     (str "frame " frame-number)))

(defn clamp-text [text limit]
  (let [v (str/trim (or text ""))]
    (if (> (count v) limit)
      (str (subs v 0 limit) "...")
      v)))

(defn frame-description [frame]
  (let [frame-number (frame-number-of frame)
        description (str/trim (or (:description frame) ""))
        preferred (if (and (seq description) (not (generic-frame-text? description frame-number)))
                    description
                    "No description yet.")]
    (let [final-text (clamp-text preferred 180)]
      (if (str/blank? final-text) "No description yet." final-text))))

(defn chapter-description [chapter]
  (let [desc (str/trim (or (:description chapter) ""))]
    (if (seq desc)
      desc
      (str "Chapter " (:chapterNumber chapter)))))

(defn enrich-frame [state frame]
  (let [frame-number (frame-number-of frame)
        description-fallback (some->> (:descriptions state)
                                      (some (fn [b] (when (= (:index b) frame-number) (:text b))))
                                      str/trim)
        page-prompt (some-> (get-in state [:visual :pagePrompts frame-number]) str/trim)
        base (assoc frame :frameNumber frame-number)
        description (str/trim (or (:description base) ""))]
    (if (or (str/blank? description) (generic-frame-text? description frame-number))
      (assoc base :description (or page-prompt description-fallback description))
      base)))

(defn normalize-chapters [state]
  (let [legacy-chapters (map (fn [chapter]
                               (-> chapter
                                   (assoc :chapterId (or (:chapterId chapter) (:episodeId chapter)))
                                   (assoc :chapterNumber (or (:chapterNumber chapter) (:episodeNumber chapter) 1))
                                   (dissoc :episodeId :episodeNumber)))
                             (or (:chapters state) (:episodes state) []))
        raw-chapters (ordered-chapters legacy-chapters)
        raw-frames (or (:frames state) [])
        fallback-chapter-id (or (:chapterId (first raw-chapters))
                                (:chapterId (first raw-frames))
                                "chapter-1")
        chapters (if (seq raw-chapters)
                   (mapv (fn [chapter]
                           (-> chapter
                               (assoc :chapterNumber (or (:chapterNumber chapter) 1))
                               (assoc :description (chapter-description chapter))))
                         raw-chapters)
                   [{:chapterId fallback-chapter-id
                     :chapterNumber 1
                     :description "Chapter 1"}])]
    (ordered-chapters chapters)))

(defn normalize-state [state]
  (let [chapters (normalize-chapters state)
        chapter-number-by-id (into {}
                                   (map (fn [chapter] [(:chapterId chapter) (:chapterNumber chapter)])
                                        chapters))
        default-chapter-id (:chapterId (first chapters))
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [chapter-id (or (:chapterId f) (:episodeId f) default-chapter-id)
                                          enriched (-> (enrich-frame state f)
                                                       (assoc :chapterId chapter-id))]
                                      (assoc enriched :frameDescription (frame-description enriched)))))
                             (sort-by (fn [frame]
                                        [(get chapter-number-by-id (:chapterId frame) 9999)
                                         (frame-number-of frame)]))
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
