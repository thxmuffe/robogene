(ns robogene.frontend.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :frame-inputs (fn [db _] (:frame-inputs db)))
(rf/reg-sub :route (fn [db _] (:route db)))
(rf/reg-sub :latest-state (fn [db _] (:latest-state db)))
