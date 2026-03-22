(ns webapp.shared.subs
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :open-frame-actions (fn [db _] (:open-frame-actions db)))
(rf/reg-sub :frame-draft
            (fn [db [_ frame-id]]
              (get-in db [:frame-drafts frame-id])))
(rf/reg-sub :frame-edit-open?
            (fn [db [_ frame-id]]
              (true? (get-in db [:open-frame-actions frame-id]))))
(rf/reg-sub :any-frame-actions-open?
            (fn [db _]
              (boolean (some true? (vals (or (:open-frame-actions db) {}))))))
(rf/reg-sub :saga-name-inputs (fn [db _] (get-in db [:view-state :index :name-inputs])))
(rf/reg-sub :chapter-name-inputs (fn [db _] (get-in db [:view-state :saga :name-inputs])))
(rf/reg-sub :character-name-inputs (fn [db _] (get-in db [:view-state :roster :name-inputs])))
(rf/reg-sub :saga-description-inputs (fn [db _] (get-in db [:view-state :index :description-inputs])))
(rf/reg-sub :chapter-description-inputs (fn [db _] (get-in db [:view-state :saga :description-inputs])))
(rf/reg-sub :character-description-inputs (fn [db _] (get-in db [:view-state :roster :description-inputs])))
(rf/reg-sub :editing-saga-id (fn [db _] (get-in db [:view-state :index :editing-id])))
(rf/reg-sub :editing-chapter-id (fn [db _] (get-in db [:view-state :saga :editing-id])))
(rf/reg-sub :editing-character-id (fn [db _] (get-in db [:view-state :roster :editing-id])))
(rf/reg-sub :image-ui-by-frame-id (fn [db _] (:image-ui-by-frame-id db)))
(rf/reg-sub :frame-image-ui
            (fn [db [_ frame-id]]
              (get-in db [:image-ui-by-frame-id frame-id] :idle)))
(rf/reg-sub :frame-image-hidden?
            (fn [db [_ frame-id]]
              (true? (get-in db [:hidden-frame-images frame-id]))))
(rf/reg-sub :active-frame-id (fn [db _] (:active-frame-id db)))
(rf/reg-sub :new-saga-name (fn [db _] (get-in db [:view-state :index :new-name])))
(rf/reg-sub :new-saga-description (fn [db _] (get-in db [:view-state :index :new-description])))
(rf/reg-sub :new-saga-panel-open? (fn [db _] (get-in db [:view-state :index :new-panel-open?])))
(rf/reg-sub :new-chapter-name (fn [db _] (get-in db [:view-state :saga :new-name])))
(rf/reg-sub :new-chapter-description (fn [db _] (get-in db [:view-state :saga :new-description])))
(rf/reg-sub :new-chapter-panel-open? (fn [db _] (get-in db [:view-state :saga :new-panel-open?])))
(rf/reg-sub :new-character-name (fn [db _] (get-in db [:view-state :roster :new-name])))
(rf/reg-sub :new-character-description (fn [db _] (get-in db [:view-state :roster :new-description])))
(rf/reg-sub :new-character-panel-open? (fn [db _] (get-in db [:view-state :roster :new-panel-open?])))
(rf/reg-sub :show-chapter-celebration? (fn [db _] (get-in db [:view-state :saga :show-celebration?])))
(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :collection-search
            (fn [db [_ view-id]]
              (get-in db [:view-state view-id :search] "")))
(rf/reg-sub :collection-page
            (fn [db [_ view-id]]
              (get-in db [:view-state view-id :page] 1)))
(rf/reg-sub :collection-per-page
            (fn [db [_ view-id]]
              (get-in db [:view-state view-id :per-page] 12)))
(rf/reg-sub :wait-lights-visible? (fn [db _] (:wait-lights-visible? db)))
(rf/reg-sub :pending-api-requests (fn [db _] (:pending-api-requests db)))
(rf/reg-sub :wait-lights-events (fn [db _] (:wait-lights-events db)))
(rf/reg-sub :cancel-ui-token (fn [db _] (:cancel-ui-token db)))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
(rf/reg-sub :available-image-generators (fn [db _] (vec (or (:available-image-generators db) []))))
(rf/reg-sub :default-image-generator (fn [db _] (:default-image-generator db)))
(rf/reg-sub :selected-image-generator (fn [db _] (:selected-image-generator db)))

;; Entity pool

(rf/reg-sub :entities (fn [db _] (:entities db)))

(rf/reg-sub :entity
            (fn [db [_ entity-id]]
              (model/entity-by-id (:entities db) entity-id)))

(rf/reg-sub :entity-children
            (fn [db [_ entity-id]]
              (model/entity-children (:entities db) entity-id)))

(rf/reg-sub :entity-ui-state
            (fn [db [_ entity-id]]
              (get-in db [:ui-state entity-id] {})))

(rf/reg-sub :entity-editing?
            (fn [db [_ entity-id]]
              (true? (get-in db [:ui-state entity-id :editing?]))))

(rf/reg-sub :entity-name-draft
            (fn [db [_ entity-id]]
              (get-in db [:ui-state entity-id :name-draft] "")))

(rf/reg-sub :entity-description-draft
            (fn [db [_ entity-id]]
              (get-in db [:ui-state entity-id :description-draft] "")))

(rf/reg-sub :derived-state (fn [db _] (:derived-state db)))

(rf/reg-sub :children-by-parent-id
            (fn [db _]
              (get-in db [:derived-state :children-by-parent-id] {})))

;; Compatibility selectors derived from entities

(rf/reg-sub :rosters
            (fn [db _]
              (model/entities-by-role (:entities db) :roster)))

(rf/reg-sub
 :roster-link-state
 (fn [db _]
   (get-in db [:view-state :roster-link])))

(rf/reg-sub
 :link-entities-state
 (fn [db _]
   (get-in db [:view-state :link-entities])))

(rf/reg-sub :frames-for-chapter
            (fn [db [_ chapter-id]]
              (model/frames-for-chapter (:entities db) chapter-id)))

(rf/reg-sub :frames-for-owner
            (fn [db [_ owner-type owner-id]]
              (model/frames-for-owner (:entities db) owner-type owner-id)))
