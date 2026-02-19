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

(defn normalize-state [state]
  {:frames (->> (or (:frames state) [])
                (map (fn [f] (assoc f :frameNumber (frame-number-of f))))
                (sort-by frame-number-of >)
                vec)})

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
  (let [pending (or (:pendingCount state) 0)]
    (str "Frames: " (count frames)
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
