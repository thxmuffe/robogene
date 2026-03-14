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
(rf/reg-sub :saga-description-inputs (fn [db _] (get-in db [:view-state :index :description-inputs])))
(rf/reg-sub :chapter-description-inputs (fn [db _] (get-in db [:view-state :saga :description-inputs])))
(rf/reg-sub :character-description-inputs (fn [db _] (get-in db [:view-state :roster :description-inputs])))
(rf/reg-sub :editing-saga-id (fn [db _] (get-in db [:view-state :index :editing-id])))
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
(rf/reg-sub
 :selected-roster-id
 (fn [db _]
   (or (get-in db [:route :roster-id])
       (some-> (:rosters db) first :rosterId))))
(rf/reg-sub
 :selected-roster
 (fn [db _]
   (let [roster-id (or (get-in db [:route :roster-id])
                       (some-> (:rosters db) first :rosterId))]
     (some (fn [roster]
             (when (= (:rosterId roster) roster-id)
               roster))
           (:rosters db)))))
(rf/reg-sub
 :characters-for-selected-roster
 (fn [db _]
   (let [roster-id (or (get-in db [:route :roster-id])
                       (some-> (:rosters db) first :rosterId))]
     (->> (or (:roster db) [])
          (filter (fn [character]
                    (= (:rosterId character) roster-id)))
          vec))))
(rf/reg-sub
 :characters-for-roster
 (fn [db [_ roster-id]]
   (->> (or (:roster db) [])
        (filter (fn [character]
                  (= (:rosterId character) roster-id)))
        vec)))
(rf/reg-sub
 :roster-link-state
 (fn [db _]
   (get-in db [:view-state :roster-link])))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
(rf/reg-sub
 :selected-saga-id
 (fn [db _]
   (get-in db [:route :saga-id])))
(rf/reg-sub
 :selected-saga
 (fn [db _]
   (let [saga-id (get-in db [:route :saga-id])]
     (some (fn [saga]
             (when (= (:sagaId saga) saga-id)
               saga))
           (:sagas db)))))
(rf/reg-sub
 :chapters-for-selected-saga
 (fn [db _]
   (let [saga-id (get-in db [:route :saga-id])]
     (->> (or (:saga db) [])
          (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
          vec))))
(rf/reg-sub
 :chapters-by-saga-id
 (fn [db [_ saga-id]]
   (->> (or (:saga db) [])
        (filter (fn [chapter] (= (:sagaId chapter) saga-id)))
        vec)))
(rf/reg-sub
 :chapter-by-id
 (fn [db [_ chapter-id]]
   (some (fn [chapter]
           (when (= (:chapterId chapter) chapter-id)
             chapter))
         (:saga db))))
(rf/reg-sub :frames-for-chapter
            (fn [db [_ chapter-id]]
              (model/frames-for-chapter (:gallery-items db) chapter-id)))
(rf/reg-sub :frames-for-owner
            (fn [db [_ owner-type owner-id]]
              (model/frames-for-owner (:gallery-items db) owner-type owner-id)))
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
