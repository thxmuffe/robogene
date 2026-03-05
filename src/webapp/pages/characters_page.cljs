(ns webapp.pages.characters-page
  (:require [webapp.pages.gallery-page :as gallery-page]))

(def characters-config
  {:view-id :characters
   :page-class "characters-page"
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

(defn characters-page [saga-name characters frame-inputs open-frame-actions active-frame-id new-character-description new-character-panel-open?]
  (let [safe-saga-name (or saga-name "Saga")
        title (str safe-saga-name " roster")]
    [gallery-page/collection-page (assoc characters-config
                                         :page-title title
                                         :saga-back-label (str "Back to " safe-saga-name))
   characters
   frame-inputs
   open-frame-actions
   active-frame-id
   new-character-description
   new-character-panel-open?
   false]))
