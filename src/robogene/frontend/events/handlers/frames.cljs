(ns robogene.frontend.events.handlers.frames
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-inputs frame-id] value)))

(rf/reg-event-fx
 :generate-frame
 (fn [{:keys [db]} [_ frame-id]]
   {:db (assoc db :status "Queueing frame...")
    :post-generate-frame {:frame-id frame-id
                          :direction (get-in db [:frame-inputs frame-id] "")}}))

(rf/reg-event-fx
 :generate-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db db
    :set-fallback-polling :active
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :generate-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Generation failed: " msg))))

(rf/reg-event-fx
 :add-frame
 (fn [{:keys [db]} [_ episode-id]]
   {:db (assoc db :status "Adding frame...")
    :post-add-frame {:episode-id episode-id}}))

(rf/reg-event-fx
 :add-frame-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db :status "Frame added.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :add-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Add frame failed: " msg))))

(rf/reg-event-fx
 :delete-frame
 (fn [{:keys [db]} [_ frame-id]]
   {:db (assoc db :status "Deleting frame...")
    :post-delete-frame {:frame-id frame-id}}))

(rf/reg-event-fx
 :delete-frame-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db :status "Frame deleted.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :delete-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Delete frame failed: " msg))))

(rf/reg-event-fx
 :clear-frame-image
 (fn [{:keys [db]} [_ frame-id]]
   {:db (assoc db :status "Removing frame image...")
    :post-clear-frame-image {:frame-id frame-id}}))

(rf/reg-event-fx
 :clear-frame-image-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db :status "Frame image removed.")
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :clear-frame-image-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Remove image failed: " msg))))
