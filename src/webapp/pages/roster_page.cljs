(ns webapp.pages.roster-page
  (:require [webapp.pages.gallery-page :as gallery-page]))

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
   :description-changed-event :new-character-description-changed
   :set-open-event :set-new-character-panel-open
   :add-event :add-character
   :input-id "new-character-description"
   :input-label "Character Description"
   :input-placeholder "Describe this character and style..."
   :add-title "Add New Character"
   :add-submit-label "Add New Character"
   :teaser-title "Add New Character"
   :teaser-sub "Create a character profile with image frames"
   :saga-back-label nil})

(defn roster-page [saga-name roster frame-inputs open-frame-actions active-frame-id new-character-description new-character-panel-open?]
  (let [safe-saga-name (or saga-name "Saga")
        title (str safe-saga-name " roster")]
    [gallery-page/collection-page (assoc roster-config
                                         :page-title title
                                         :saga-back-label (str "Back to " safe-saga-name))
   roster
   frame-inputs
   open-frame-actions
   active-frame-id
   new-character-description
   new-character-panel-open?
   false]))
