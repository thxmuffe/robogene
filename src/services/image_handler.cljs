(ns services.image-handler
  (:require [clojure.string :as str]))

(defn- find-frame-index [frames frame-id]
  (first (keep-indexed (fn [idx frame]
                         (when (= (:frameId frame) frame-id) idx))
                       frames)))

(defn- frame-not-found! []
  (throw (js/Error. "Frame not found.")))

(defn- ensure-frame-editable! [frame error-msg]
  (when (or (= "queued" (:status frame))
            (= "processing" (:status frame)))
    (throw (js/Error. error-msg))))

(defn clear-frame-image! [state* frame-id]
  (let [snapshot @state*
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))]
    (when (nil? idx)
      (frame-not-found!))
    (ensure-frame-editable! frame "Cannot clear image while queued or processing.")
    (swap! state*
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageUrl] nil)
                 (assoc-in [:frames idx :status] "draft")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] nil)
                 (update :revision inc))))
    (get (:frames @state*) idx)))

(defn replace-frame-image! [state* frame-id image-data-url]
  (let [snapshot @state*
        frames (:frames snapshot)
        idx (find-frame-index frames frame-id)
        frame (when (number? idx) (get frames idx))
        normalized-image (some-> image-data-url str str/trim)]
    (when (nil? idx)
      (frame-not-found!))
    (ensure-frame-editable! frame "Cannot replace image while queued or processing.")
    (when-not (str/starts-with? (or normalized-image "") "data:image/")
      (throw (js/Error. "Invalid imageDataUrl.")))
    (swap! state*
           (fn [s]
             (-> s
                 (assoc-in [:frames idx :imageUrl] normalized-image)
                 (assoc-in [:frames idx :status] "ready")
                 (assoc-in [:frames idx :error] nil)
                 (assoc-in [:frames idx :completedAt] (.toISOString (js/Date.)))
                 (update :revision inc))))
    (get (:frames @state*) idx)))
