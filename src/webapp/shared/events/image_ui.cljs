(ns webapp.shared.events.image-ui
  (:require [clojure.string :as str]))

(defn frame-image-url [frame]
  (or (:imageUrl frame) ""))

(defn image-ui-state-for-url [url]
  (if (str/blank? (or url ""))
    :idle
    :loading))

(defn sync-image-ui-by-frame-id [prev-ui old-frames new-frames]
  (let [old-url-by-id (into {}
                            (map (fn [frame]
                                   [(:frameId frame) (frame-image-url frame)]))
                            (or old-frames []))]
    (into {}
          (map (fn [frame]
                 (let [frame-id (:frameId frame)
                       new-url (frame-image-url frame)
                       old-url (get old-url-by-id frame-id)
                       existing (get prev-ui frame-id)]
                   [frame-id
                    (cond
                      (str/blank? new-url) :idle
                      (= new-url old-url) (or existing (image-ui-state-for-url new-url))
                      :else (image-ui-state-for-url new-url))])))
          (or new-frames []))))

(defn mark-image-loaded [ui-map frame-id]
  (assoc ui-map frame-id :loaded))

(defn mark-image-error [ui-map frame-id]
  (assoc ui-map frame-id :error))

(defn mark-image-idle [ui-map frame-id]
  (assoc ui-map frame-id :idle))

(defn remove-frame [ui-map frame-id]
  (dissoc ui-map frame-id))
