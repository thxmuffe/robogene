(ns services.image-handler
  (:require [clojure.string :as str]))

(defn- find-frame-index [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))

(defn- frame-not-found! []
  (throw (js/Error. "Frame not found.")))

(defn clear-frame-image! [state* frame-id]
  (let [snapshot @state*
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        _frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (frame-not-found!))
    (swap! state*
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageUrl] nil)
                 (assoc-in [:frames idx :imageStatus] "draft")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] nil)
                 (assoc-in [:frames idx :queuedAt] nil)
                 (assoc-in [:frames idx :startedAt] nil)
                 (update-in [:frames idx] dissoc :withoutRoster)
                 (update :revision inc))))
    (get (:frames @state*) idx)))

(defn replace-frame-image! [state* frame-id image-data-url]
  (let [snapshot @state*
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        _frame (when (number? idx) (get frames idx))
        normalized-image (some-> image-data-url str str/trim)]
    (when (nil? idx)
      (frame-not-found!))
    (when-not (str/starts-with? (or normalized-image "") "data:image/")
      (throw (js/Error. "Invalid imageDataUrl.")))
    (swap! state*
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageUrl] normalized-image)
                 (assoc-in [:frames idx :imageStatus] "ready")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
                 (assoc-in [:frames idx :queuedAt] nil)
                 (assoc-in [:frames idx :startedAt] nil)
                 (update-in [:frames idx] dissoc :withoutRoster)
                 (update :revision inc))))
    (get (:frames @state*) idx)))
