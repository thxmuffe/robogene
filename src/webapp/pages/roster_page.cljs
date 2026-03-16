(ns webapp.pages.roster-page
  (:require [re-frame.core :as rf]
            [webapp.pages.gallery-page :as gallery-page]))

(def roster-config
  {:view-id :roster
   :page-class "roster-page"
   :entity-label "character"
   :entity-singular "character"
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
   :name-input-placeholder "Name this character..."
   :description-input-placeholder "Describe aliases, style, and references..."
   :add-title "Add New Character"
   :teaser-title "Add New Character"
   :teaser-sub "Create a character profile with image frames"
   :search-placeholder "Search characters..."
   :empty-label "No characters match this search."
   :saga-back-label nil})

(defn roster-page [saga-name]
  (let [selected-roster @(rf/subscribe [:selected-roster])
        roster-characters @(rf/subscribe [:characters-for-selected-roster])
        active-frame-id @(rf/subscribe [:active-frame-id])
        new-character-name @(rf/subscribe [:new-character-name])
        new-character-description @(rf/subscribe [:new-character-description])
        new-character-panel-open? @(rf/subscribe [:new-character-panel-open?])
        safe-saga-name (or saga-name "Saga")
        title (or (:name selected-roster) "Roster")]
    [gallery-page/collection-page (assoc roster-config
                                         :page-title title
                                         :saga-back-label (str "Back to " safe-saga-name))
     roster-characters
     active-frame-id
     {:name new-character-name
      :description new-character-description}
     new-character-panel-open?
     false]))
