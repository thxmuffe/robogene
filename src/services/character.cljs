(ns services.character
  (:require [services.chapter :as chapter]))

(defn add-character! [description]
  (chapter/add-character! description))

(defn add-frame! [character-id]
  (chapter/add-frame! character-id "character"))

(defn update-description! [character-id description]
  (chapter/update-character-description! character-id description))

(defn delete-character! [character-id]
  (chapter/delete-character! character-id))
