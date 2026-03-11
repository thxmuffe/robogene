(ns webapp.shared.subs
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :saga-meta (fn [db _] (:saga-meta db)))
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
(rf/reg-sub :chapter-name-inputs (fn [db _] (get-in db [:view-state :saga :name-inputs])))
(rf/reg-sub :character-name-inputs (fn [db _] (get-in db [:view-state :roster :name-inputs])))
(rf/reg-sub :chapter-description-inputs (fn [db _] (get-in db [:view-state :saga :description-inputs])))
(rf/reg-sub :character-description-inputs (fn [db _] (get-in db [:view-state :roster :description-inputs])))
(rf/reg-sub :editing-chapter-id (fn [db _] (get-in db [:view-state :saga :editing-id])))
(rf/reg-sub :editing-character-id (fn [db _] (get-in db [:view-state :roster :editing-id])))
(rf/reg-sub :gallery-chapter-collapsed?
            (fn [db [_ chapter-id]]
              (contains? (get-in db [:view-state :gallery :collapsed-chapter-ids] #{})
                         chapter-id)))
(rf/reg-sub :image-ui-by-frame-id (fn [db _] (:image-ui-by-frame-id db)))
(rf/reg-sub :frame-image-ui
            (fn [db [_ frame-id]]
              (get-in db [:image-ui-by-frame-id frame-id] :idle)))
(rf/reg-sub :active-frame-id (fn [db _] (:active-frame-id db)))
(rf/reg-sub :new-chapter-name (fn [db _] (get-in db [:view-state :saga :new-name])))
(rf/reg-sub :new-chapter-description (fn [db _] (get-in db [:view-state :saga :new-description])))
(rf/reg-sub :new-chapter-panel-open? (fn [db _] (get-in db [:view-state :saga :new-panel-open?])))
(rf/reg-sub :new-character-name (fn [db _] (get-in db [:view-state :roster :new-name])))
(rf/reg-sub :new-character-description (fn [db _] (get-in db [:view-state :roster :new-description])))
(rf/reg-sub :new-character-panel-open? (fn [db _] (get-in db [:view-state :roster :new-panel-open?])))
(rf/reg-sub :show-chapter-celebration? (fn [db _] (get-in db [:view-state :saga :show-celebration?])))
(rf/reg-sub :saga-meta-editing? (fn [db _] (true? (get-in db [:view-state :saga :meta-editing?]))))
(rf/reg-sub :saga-meta-name (fn [db _] (get-in db [:view-state :saga :meta-name])))
(rf/reg-sub :saga-meta-description (fn [db _] (get-in db [:view-state :saga :meta-description])))
(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
(rf/reg-sub :frames-for-chapter
            (fn [db [_ chapter-id]]
              (model/frames-for-chapter (:gallery-items db) chapter-id)))
(rf/reg-sub :frames-for-owner
            (fn [db [_ owner-type owner-id]]
              (model/frames-for-owner (:gallery-items db) owner-type owner-id)))
(rf/reg-sub :wait-lights-visible? (fn [db _] (:wait-lights-visible? db)))
(rf/reg-sub :pending-api-requests (fn [db _] (:pending-api-requests db)))
(rf/reg-sub :wait-lights-events (fn [db _] (:wait-lights-events db)))
(rf/reg-sub :cancel-ui-token (fn [db _] (:cancel-ui-token db)))
