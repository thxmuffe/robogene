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

(defn parse-hash-route [hash]
  (let [raw (or hash "")]
    (or
     (when-let [[_ chapter frame query] (re-matches #"^#/chapter/([^/]+)/frame/([^?]+)(?:\?(.*))?$" raw)]
       (let [fullscreen? (boolean (re-find #"(^|&)fullscreen=1(&|$)" (or query "")))]
         {:view :frame
          :chapter chapter
          :frame-id frame
          :fullscreen? fullscreen?}))
     (when (re-matches #"^#/characters/?$" raw)
       {:view :characters})
     (when (or (str/blank? raw)
               (re-matches #"^#/?$" raw)
               (re-matches #"^#/saga/?$" raw))
       {:view :saga})
     {:view :saga})))

(defn frame-hash
  ([chapter frame-id]
   (frame-hash chapter frame-id false))
  ([chapter frame-id fullscreen?]
   (str "#/chapter/" chapter "/frame/" frame-id
        (when fullscreen? "?fullscreen=1"))))

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

(defn derived-saga [state]
  (->> (or (:saga state) [])
       (sort-by (fn [chapter]
                  [(or (:chapterNumber chapter) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt chapter) "")
                   (or (:chapterId chapter) "")]))
       vec))

(defn derived-characters [state]
  (->> (or (:characters state) [])
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
        characters (derived-characters state)
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [enriched (enrich-frame f)]
                                      (assoc (if (:ownerType enriched)
                                               enriched
                                               (assoc enriched :ownerType "saga"))
                                             :frameDescription (frame-description enriched)))))
                             vec)]
    {:saga saga
     :characters characters
     :frames enriched-frames}))

(defn status-line [state saga characters frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Saga: " (count saga)
         " | Characters: " (count characters)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
