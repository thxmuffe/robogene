(ns webapp.shared.events.handlers.shared)

(def new-chapter-frame-id "__new_chapter__")

(defn frame-by-id [frames frame-id]
  (some (fn [frame] (when (= (:frameId frame) frame-id) frame)) frames))
