(ns robogene.frontend.events.model
  (:require [clojure.string :as str]))

(defn frame-number-of [frame]
  (:frameNumber frame))

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

(defn first-useful-line [text]
  (->> (str/split-lines (or text ""))
       (map str/trim)
       (filter (fn [line]
                 (and (seq line)
                      (not (str/starts-with? (str/lower-case line) "keep continuity"))
                      (not (re-matches #"(?i)^frame\s+\d+$" line)))))
       first))

(defn clamp-text [text limit]
  (let [v (str/trim (or text ""))]
    (if (> (count v) limit)
      (str (subs v 0 limit) "...")
      v)))

(defn frame-description [frame]
  (let [frame-number (frame-number-of frame)
        beat (str/trim (or (:beatText frame) ""))
        preferred (if (and (seq beat) (not (generic-frame-text? beat frame-number)))
                    beat
                    (or (first-useful-line (:directionText frame))
                        (first-useful-line (:suggestedDirection frame))
                        "No description yet."))]
    (let [final-text (clamp-text preferred 180)]
      (if (str/blank? final-text) "No description yet." final-text))))

(defn default-suggested-direction [beat-text page-prompt]
  (str/join "\n" (filter seq [beat-text page-prompt "Keep continuity with previous frames."])))

(defn enrich-frame [state frame]
  (let [frame-number (frame-number-of frame)
        beat-fallback (some->> (:beats state)
                               (some (fn [b] (when (= (:index b) frame-number) (:text b))))
                               str/trim)
        page-prompt (some-> (get-in state [:visual :pagePrompts frame-number]) str/trim)
        base (assoc frame :frameNumber frame-number)
        beat (str/trim (or (:beatText base) ""))
        with-beat (if (or (str/blank? beat) (generic-frame-text? beat frame-number))
                    (assoc base :beatText (or beat-fallback beat))
                    base)
        suggested (str/trim (or (:suggestedDirection with-beat) ""))]
    (cond-> with-beat
      (str/blank? suggested)
      (assoc :suggestedDirection (default-suggested-direction (:beatText with-beat) page-prompt)))))

(defn normalize-state [state]
  {:frames (->> (or (:frames state) [])
                (map (fn [f]
                       (let [enriched (enrich-frame state f)]
                         (assoc enriched :frameDescription (frame-description enriched)))))
                (sort-by frame-number-of >)
                vec)})

(defn status-line [state frames]
  (let [pending (or (:pendingCount state) 0)]
    (str "Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
