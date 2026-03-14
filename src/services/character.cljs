(ns services.character
  (:require [services.chapter :as chapter]))

(defn add-character! [roster-id name]
  (chapter/add-character! roster-id name))

(defn add-character-with-details! [roster-id name description]
  (chapter/add-character-with-details! roster-id name description))

(defn add-frame! [character-id]
  (chapter/add-frame! character-id "character"))

(defn add-frame-with-id! [character-id frame-id]
  (chapter/add-frame! character-id "character" frame-id))

(defn update-details! [character-id name description]
  (chapter/update-character-details! character-id name description))

(defn delete-character! [character-id]
  (chapter/delete-character! character-id))
