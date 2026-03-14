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
  (let [{:keys [prev next active-frame]}
        (loop [remaining (seq frames)
               prev nil]
          (if-let [active-frame (first remaining)]
            (if (= (frame-id-of active-frame) frame-id)
              {:prev prev
               :next (second remaining)
               :active-frame active-frame}
              (recur (rest remaining) active-frame))
            {:prev nil :next nil :active-frame nil}))
        first-frame (first frames)
        last-frame (last frames)]
    (cond
      (or (empty? frames) (zero? delta)) nil
      (pos? delta) (or next first-frame)
      (neg? delta) (or prev last-frame)
      (some? active-frame) active-frame
      :else first-frame)))

(defn parse-query-param [query key]
  (some-> (re-find (re-pattern (str "(^|&)" key "=([^&]+)")) query)
          (nth 2 nil)
          js/decodeURIComponent
          str/trim
          not-empty))

(defn parse-hash-route [hash]
  (let [raw (or hash "")]
    (or
     (when-let [[_ chapter frame query] (re-matches #"^#/chapter/([^/]+)/frame/([^?]+)(?:\?(.*))?$" raw)]
       (let [query* (or query "")
             fullscreen? (boolean (re-find #"(^|&)fullscreen=1(&|$)" query*))
             from-page (cond
                         (re-find #"(^|&)from=roster(&|$)" query*) :roster
                         (re-find #"(^|&)from=saga(&|$)" query*) :saga
                         :else nil)]
         {:view :frame
          :chapter chapter
          :frame-id frame
          :fullscreen? fullscreen?
          :from-page from-page
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ chapter query] (re-matches #"^#/chapter/([^/?#]+)(?:\?(.*))?$" raw)]
       {:view :chapter
        :chapter chapter
        :saga-id (parse-query-param (or query "") "sagaId")})
     (when-let [[_ query] (re-matches #"^#/roster/?(?:\?(.*))?$" raw)]
       (let [query* (or query "")]
         {:view :roster
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ saga-id] (re-matches #"^#/saga/([^/?#]+)(?:\?.*)?$" raw)]
       {:view :saga
        :saga-id (some-> saga-id js/decodeURIComponent str/trim not-empty)})
     (when (or (str/blank? raw)
               (re-matches #"^#/?$" raw))
       {:view :index})
     {:view :index})))

(defn index-hash []
  "#/")

(defn saga-hash [saga-id]
  (if (str/blank? (or saga-id ""))
    (index-hash)
    (str "#/saga/" (js/encodeURIComponent saga-id))))

(defn roster-hash
  ([] (roster-hash nil))
  ([saga-id]
   (if (str/blank? (or saga-id ""))
     "#/roster"
     (str "#/roster?sagaId=" (js/encodeURIComponent saga-id)))))

(defn chapter-hash
  ([chapter-id]
   (chapter-hash chapter-id nil))
  ([chapter-id saga-id]
   (str "#/chapter/" chapter-id
        (when-not (str/blank? (or saga-id ""))
          (str "?sagaId=" (js/encodeURIComponent saga-id))))))

(defn frame-hash
  ([chapter frame-id]
   (frame-hash chapter frame-id false nil nil))
  ([chapter frame-id fullscreen?]
   (frame-hash chapter frame-id fullscreen? nil nil))
  ([chapter frame-id fullscreen? from-page]
   (frame-hash chapter frame-id fullscreen? from-page nil))
  ([chapter frame-id fullscreen? from-page saga-id]
   (let [query-parts (cond-> []
                       fullscreen? (conj "fullscreen=1")
                       (#{:saga :roster} from-page) (conj (str "from=" (name from-page)))
                       (not (str/blank? (or saga-id ""))) (conj (str "sagaId=" (js/encodeURIComponent saga-id))))
         query (when (seq query-parts)
                 (str "?" (str/join "&" query-parts)))]
     (str "#/chapter/" chapter "/frame/" frame-id (or query "")))))

(defn parse-json-safe [text]
  (js->clj (.parse js/JSON text) :keywordize-keys true))

(defn generic-frame-text? [text]
  (boolean (re-matches #"(?i)^frame\s+\d+$" (str/trim (or text "")))))

(defn clamp-text [text limit]
  (let [v (str/trim (or text ""))]
    (if (> (count v) limit)
      (str (subs v 0 limit) "...")
      v)))

(defn frame-description [frame]
  (clamp-text (:description frame) 180))

(defn enrich-frame [frame]
  (let [description (str/trim (or (:description frame) ""))
        image-url (or (:imageUrl frame) (:imageDataUrl frame))
        image-status (or (:imageStatus frame)
                         (:status frame)
                         (if (str/blank? (or image-url ""))
                           "draft"
                           "ready"))
        normalized (-> frame
                       (dissoc :imageDataUrl)
                       (assoc :imageUrl image-url
                              :imageStatus image-status)
                       (dissoc :status))]
    (if (or (str/blank? description) (generic-frame-text? description))
      (assoc normalized :description description)
      normalized)))

(defn dedupe-by-id [id-key rows]
  (->> (or rows [])
       (reduce (fn [acc row]
                 (let [id (get row id-key)
                       map-key (if (and (string? id) (not (str/blank? id)))
                                 id
                                 (str "__idx__" (count acc)))]
                   (if (contains? acc map-key)
                     acc
                     (assoc acc map-key row))))
               {})
       vals))

(defn derived-sagas [state]
  (->> (or (:sagas state) [])
       (dedupe-by-id :sagaId)
       (sort-by (fn [saga]
                  [(or (:sagaNumber saga) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt saga) "")
                   (or (:sagaId saga) "")]))
       vec))

(defn derived-saga [state]
  (->> (or (:saga state) [])
       (dedupe-by-id :chapterId)
       (sort-by (fn [chapter]
                  [(or (:chapterNumber chapter) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt chapter) "")
                   (or (:chapterId chapter) "")]))
       vec))

(defn derived-roster [state]
  (->> (or (:roster state) [])
       (dedupe-by-id :characterId)
       (sort-by (fn [character]
                  [(or (:characterNumber character) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt character) "")
                   (or (:characterId character) "")]))
       vec))

(defn frame-owner-type [frame]
  (let [owner-type (or (:ownerType frame) "saga")]
    (if (keyword? owner-type)
      (name owner-type)
      (str owner-type))))

(defn frames-for-owner [frames owner-type owner-id]
  (let [owner-type (str owner-type)]
    (->> (or frames [])
         (filter (fn [frame]
                   (and (= owner-type (frame-owner-type frame))
                        (= (:chapterId frame) owner-id))))
         ordered-frames)))

(defn frames-for-chapter [frames chapter-id]
  (frames-for-owner (map (fn [frame]
                           (if (:ownerType frame)
                             frame
                             (assoc frame :ownerType "saga")))
                         (or frames []))
                    "saga"
                    chapter-id))

(defn derived-state [state]
  (let [sagas (derived-sagas state)
        saga (derived-saga state)
        roster (derived-roster state)
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [enriched (enrich-frame f)]
                                      (assoc (if (:ownerType enriched)
                                               enriched
                                               (assoc enriched :ownerType "saga"))
                                             :frameDescription (frame-description enriched)))))
                             vec)]
    {:sagas sagas
     :saga saga
     :roster roster
     :frames enriched-frames}))

(defn status-line [state sagas saga roster frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Sagas: " (count sagas)
         " | Chapters: " (count saga)
         " | Roster: " (count roster)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
