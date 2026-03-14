(ns services.character
  (:require [services.chapter :as chapter]))

(defn add-character! [name]
  (chapter/add-character! name))

(defn add-character-with-details! [name description]
  (chapter/add-character-with-details! name description))

(defn add-frame! [character-id]
  (chapter/add-frame! character-id "character"))

(defn add-frame-with-id! [character-id frame-id]
  (chapter/add-frame! character-id "character" frame-id))

(defn update-details! [character-id name description]
  (chapter/update-character-details! character-id name description))

(defn delete-character! [character-id]
  (chapter/delete-character! character-id))
