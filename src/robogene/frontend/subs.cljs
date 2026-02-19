(ns robogene.frontend.subs
  (:require [re-frame.core :as rf]))

(rf/reg-sub :status (fn [db _] (:status db)))
(rf/reg-sub :direction-input (fn [db _] (:direction-input db)))
(rf/reg-sub :gallery-items (fn [db _] (:gallery-items db)))
(rf/reg-sub :submitting? (fn [db _] (:submitting? db)))
