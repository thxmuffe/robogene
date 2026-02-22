(ns webapp.events.handlers.shared)

(def new-episode-frame-id "__new_episode__")

(defn frame-index-by-id [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))

(defn frame-by-id [frames frame-id]
  (some (fn [frame] (when (= (:frameId frame) frame-id) frame)) frames))
