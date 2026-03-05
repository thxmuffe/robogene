(ns webapp.shared.model
  (:require [clojure.string :as str]
            [webapp.pages.gallery-page :as gallery-page]))

(defn default-saga-route-name []
  (or (some-> gallery-page/saga-config :route-name str/trim not-empty)
      "robot emperor"))

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
          :from-page from-page}))
     (when-let [[_ query] (re-matches #"^#/roster/?(?:\?(.*))?$" raw)]
       (let [query* (or query "")
             saga-name (some-> (re-find #"(^|&)saga=([^&]+)" query*)
                               (nth 2 nil)
                               js/decodeURIComponent
                               str/trim
                               not-empty)]
         {:view :roster
          :saga-name (or saga-name (default-saga-route-name))}))
     (when-let [[_ saga-name] (re-matches #"^#/([^/?#][^?#]*)/?$" raw)]
       {:view :saga
        :saga-name (or (some-> saga-name js/decodeURIComponent str/trim not-empty)
                       (default-saga-route-name))})
     (when (or (str/blank? raw)
               (re-matches #"^#/?$" raw))
       {:view :saga
        :saga-name (default-saga-route-name)})
     {:view :saga
      :saga-name (default-saga-route-name)})))

(defn saga-hash
  ([] (saga-hash (default-saga-route-name)))
  ([saga-name]
   (str "#/" (js/encodeURIComponent (or saga-name (default-saga-route-name))))))

(defn roster-hash
  ([] (roster-hash (default-saga-route-name)))
  ([saga-name]
   (str "#/roster?saga=" (js/encodeURIComponent (or saga-name (default-saga-route-name))))))

(defn frame-hash
  ([chapter frame-id]
   (frame-hash chapter frame-id false nil))
  ([chapter frame-id fullscreen?]
   (frame-hash chapter frame-id fullscreen? nil))
  ([chapter frame-id fullscreen? from-page]
   (let [query-parts (cond-> []
                       fullscreen? (conj "fullscreen=1")
                       (#{:saga :roster} from-page) (conj (str "from=" (name from-page))))
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
        normalized (-> frame
                       (dissoc :imageDataUrl)
                       (assoc :imageUrl image-url))]
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
  (let [saga (derived-saga state)
        roster (derived-roster state)
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [enriched (enrich-frame f)]
                                      (assoc (if (:ownerType enriched)
                                               enriched
                                               (assoc enriched :ownerType "saga"))
                                             :frameDescription (frame-description enriched)))))
                             vec)]
    {:saga saga
     :roster roster
     :frames enriched-frames}))

(defn status-line [state saga roster frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Saga: " (count saga)
         " | Roster: " (count roster)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
