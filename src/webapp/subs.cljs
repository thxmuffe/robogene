(ns webapp.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :episodes (fn [db _] (:episodes db)))
(rf/reg-sub :frame-inputs (fn [db _] (:frame-inputs db)))
(rf/reg-sub :open-frame-actions (fn [db _] (:open-frame-actions db)))
(rf/reg-sub :active-frame-id (fn [db _] (:active-frame-id db)))
(rf/reg-sub :new-episode-description (fn [db _] (:new-episode-description db)))
(rf/reg-sub :new-episode-panel-open? (fn [db _] (:new-episode-panel-open? db)))
(rf/reg-sub :show-episode-celebration? (fn [db _] (:show-episode-celebration? db)))
(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
