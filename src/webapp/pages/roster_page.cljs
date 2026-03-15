(ns webapp.pages.roster-page
  (:require [webapp.pages.gallery-page :as gallery-page]))

(def roster-config
  {:view-id :roster
   :page-class "collection-page"
   :entity-label "character"
   :entity-singular "sequence"
   :entity-id-key :characterId
   :owner-type "character"
   :page-title "Roster"
   :editing-id-sub [:editing-character-id]
   :name-inputs-sub [:character-name-inputs]
   :description-inputs-sub [:character-description-inputs]
   :name-changed-event :new-character-name-changed
   :description-changed-event :new-character-description-changed
   :set-open-event :set-new-character-panel-open
   :add-event :add-character
   :name-input-placeholder "Name this sequence..."
   :description-input-placeholder ""
   :add-title "Add new"
   :teaser-title "Add new"
   :teaser-sub ""
   :search-placeholder "Search sequences..."
   :empty-label "No sequences match this search."
   :saga-back-label nil})

(defn roster-page [selected-roster saga-name roster-characters active-frame-id]
  (let [safe-saga-name (or saga-name "Saga")
        title (or (:name selected-roster) "Roster")
        config (assoc roster-config
                      :page-title title
                      :header-entity selected-roster
                      :saga-back-label (str "Back to " safe-saga-name))]
    [gallery-page/sequence-gallery config
     (or roster-characters [])
     active-frame-id
     false]))
