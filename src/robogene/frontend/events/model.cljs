(ns robogene.frontend.events.model
  (:require [clojure.string :as str]))

(defn episode-number-of [episode]
  (:episodeNumber episode))

(defn frame-number-of [frame]
  (:frameNumber frame))

(defn ordered-episodes [episodes]
  (->> episodes
       (sort-by episode-number-of)
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

(defn episode-description [episode]
  (let [desc (str/trim (or (:description episode) ""))]
    (if (seq desc)
      desc
      (str "Episode " (:episodeNumber episode)))))

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

(defn normalize-episodes [state]
  (let [raw-episodes (ordered-episodes (or (:episodes state) []))
        raw-frames (or (:frames state) [])
        fallback-episode-id (or (:episodeId (first raw-episodes))
                                (:episodeId (first raw-frames))
                                "episode-1")
        episodes (if (seq raw-episodes)
                   (mapv (fn [episode]
                           (-> episode
                               (assoc :episodeNumber (or (:episodeNumber episode) 1))
                               (assoc :description (episode-description episode))))
                         raw-episodes)
                   [{:episodeId fallback-episode-id
                     :episodeNumber 1
                     :description "Episode 1"}])]
    (ordered-episodes episodes)))

(defn normalize-state [state]
  (let [episodes (normalize-episodes state)
        episode-number-by-id (into {}
                                   (map (fn [episode] [(:episodeId episode) (:episodeNumber episode)])
                                        episodes))
        default-episode-id (:episodeId (first episodes))
        enriched-frames (->> (or (:frames state) [])
                             (map (fn [f]
                                    (let [episode-id (or (:episodeId f) default-episode-id)
                                          enriched (-> (enrich-frame state f)
                                                       (assoc :episodeId episode-id))]
                                      (assoc enriched :frameDescription (frame-description enriched)))))
                             (sort-by (fn [frame]
                                        [(get episode-number-by-id (:episodeId frame) 9999)
                                         (frame-number-of frame)]))
                             vec)
        frames-by-episode (group-by :episodeId enriched-frames)
        episodes-with-frames (mapv (fn [episode]
                                     (assoc episode :frames (ordered-frames (get frames-by-episode (:episodeId episode) []))))
                                   episodes)]
    {:episodes episodes-with-frames
     :frames enriched-frames}))

(defn status-line [state episodes frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Episodes: " (count episodes)
         " | Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
