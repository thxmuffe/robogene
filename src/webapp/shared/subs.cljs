(ns webapp.shared.subs
  (:require [re-frame.core :as rf]
            [webapp.shared.model :as model]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :chapters (fn [db _] (:chapters db)))
(rf/reg-sub :frame-inputs (fn [db _] (:frame-inputs db)))
(rf/reg-sub :open-frame-actions (fn [db _] (:open-frame-actions db)))
(rf/reg-sub :image-ui-by-frame-id (fn [db _] (:image-ui-by-frame-id db)))
(rf/reg-sub :frame-image-ui
            (fn [db [_ frame-id]]
              (get-in db [:image-ui-by-frame-id frame-id] :idle)))
(rf/reg-sub :active-frame-id (fn [db _] (:active-frame-id db)))
(rf/reg-sub :new-chapter-description (fn [db _] (:new-chapter-description db)))
(rf/reg-sub :new-chapter-panel-open? (fn [db _] (:new-chapter-panel-open? db)))
(rf/reg-sub :show-chapter-celebration? (fn [db _] (:show-chapter-celebration? db)))
(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
(rf/reg-sub :frames-for-chapter
            (fn [db [_ chapter-id]]
              (model/frames-for-chapter (:gallery-items db) chapter-id)))
(rf/reg-sub :wait-lights-visible? (fn [db _] (:wait-lights-visible? db)))
(rf/reg-sub :pending-api-requests (fn [db _] (:pending-api-requests db)))
(rf/reg-sub :wait-lights-events (fn [db _] (:wait-lights-events db)))
