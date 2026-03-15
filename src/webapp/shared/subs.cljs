(ns webapp.shared.subs
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :sagas (fn [db _] (:sagas db)))
(rf/reg-sub :rosters (fn [db _] (:rosters db)))
(rf/reg-sub :saga (fn [db _] (:saga db)))
(rf/reg-sub :roster (fn [db _] (:roster db)))
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
(rf/reg-sub :roster-name-inputs (fn [db _] (get-in db [:view-state :roster-meta :name-inputs])))
(rf/reg-sub :saga-description-inputs (fn [db _] (get-in db [:view-state :index :description-inputs])))
(rf/reg-sub :chapter-description-inputs (fn [db _] (get-in db [:view-state :saga :description-inputs])))
(rf/reg-sub :character-description-inputs (fn [db _] (get-in db [:view-state :roster :description-inputs])))
(rf/reg-sub :roster-description-inputs (fn [db _] (get-in db [:view-state :roster-meta :description-inputs])))
(rf/reg-sub :editing-saga-id (fn [db _] (get-in db [:view-state :index :editing-id])))
(rf/reg-sub :editing-chapter-id (fn [db _] (get-in db [:view-state :saga :editing-id])))
(rf/reg-sub :editing-character-id (fn [db _] (get-in db [:view-state :roster :editing-id])))
(rf/reg-sub :editing-roster-id (fn [db _] (get-in db [:view-state :roster-meta :editing-id])))
(rf/reg-sub :gallery-chapter-collapsed?
            (fn [db [_ chapter-id]]
              (contains? (get-in db [:view-state :gallery :collapsed-chapter-ids] #{})
                         chapter-id)))
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
(rf/reg-sub :view :<- [:route] (fn [route _] (:view route)))
(rf/reg-sub :selected-saga-id :<- [:route] (fn [route _] (:saga-id route)))

(rf/reg-sub :selected-saga
            :<- [:selected-saga-id]
            :<- [:sagas]
            (fn [[saga-id sagas] _]
              (some (fn [saga] (when (= (:sagaId saga) saga-id) saga))
                    sagas)))

(rf/reg-sub :chapters-by-saga-id
            :<- [:saga]
            (fn [chapters [_ saga-id]]
              (->> (or chapters [])
                   (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
                   vec)))

(rf/reg-sub :chapters-for-selected-saga
            :<- [:selected-saga-id]
            :<- [:saga]
            (fn [[saga-id chapters] _]
              (->> (or chapters [])
                   (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
                   vec)))

(rf/reg-sub :selected-chapter-id :<- [:route] (fn [route _] (:chapter-id route)))

(rf/reg-sub :selected-chapter
            :<- [:selected-chapter-id]
            :<- [:saga]
            (fn [[chapter-id chapters] _]
              (some (fn [chapter] (when (= (:chapterId chapter) chapter-id) chapter))
                    chapters)))

(rf/reg-sub :frames-for-chapter
            :<- [:gallery-items]
            (fn [frames [_ chapter-id]]
              (model/frames-for-chapter frames chapter-id)))

(rf/reg-sub :frames-for-owner
            :<- [:gallery-items]
            (fn [frames [_ owner-type owner-id]]
              (model/frames-for-owner frames owner-type owner-id)))

(rf/reg-sub :selected-chapter-frames
            :<- [:selected-chapter-id]
            :<- [:gallery-items]
            (fn [[chapter-id frames] _]
              (model/frames-for-chapter frames chapter-id)))

(rf/reg-sub :selected-frame-id :<- [:route] (fn [route _] (:frame-id route)))

(rf/reg-sub :selected-frame
            :<- [:selected-frame-id]
            :<- [:gallery-items]
            (fn [[frame-id frames] _]
              (some (fn [frame] (when (= (:frameId frame) frame-id) frame))
                    frames)))

(rf/reg-sub :fullscreen? :<- [:route] (fn [route _] (:fullscreen route)))
(rf/reg-sub :roster-link-state (fn [db _] (get-in db [:view-state :roster-link])))

(rf/reg-sub :selected-roster-id
            :<- [:route]
            :<- [:rosters]
            (fn [[route rosters] _]
              (or (:roster-id route)
                  (some-> rosters first :rosterId))))

(rf/reg-sub :selected-roster
            :<- [:selected-roster-id]
            :<- [:rosters]
            (fn [[roster-id rosters] _]
              (some (fn [roster] (when (= (:rosterId roster) roster-id) roster))
                    rosters)))

(rf/reg-sub :characters-for-selected-roster
            :<- [:selected-roster-id]
            :<- [:roster]
            (fn [[roster-id characters] _]
              (->> (or characters [])
                   (filter (fn [character] (= (:rosterId character) roster-id)))
                   vec)))

(rf/reg-sub :wait-lights-visible? (fn [db _] (:wait-lights-visible? db)))
(rf/reg-sub :pending-api-requests (fn [db _] (:pending-api-requests db)))
(rf/reg-sub :wait-lights-events (fn [db _] (:wait-lights-events db)))
(rf/reg-sub :cancel-ui-token (fn [db _] (:cancel-ui-token db)))

(rf/reg-sub :collection-search (fn [db [_ view-id]] (get-in db [:view-state view-id :search])))
(rf/reg-sub :collection-page (fn [db [_ view-id]] (get-in db [:view-state view-id :page] 1)))
(rf/reg-sub :collection-per-page (fn [db [_ view-id]] (get-in db [:view-state view-id :per-page] 12)))
