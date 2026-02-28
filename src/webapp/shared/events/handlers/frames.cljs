(ns webapp.shared.events.handlers.frames
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(defn deleted-frame-label [frame]
  (let [description (str/trim (or (:description frame) ""))]
    (cond
      (seq description) (str "\"" description "\"")
      :else (or (:frameId frame) "frame"))))

(defn request-frame-mutation [db status-text effect-key payload]
  (assoc {:db (assoc db :status status-text)}
         effect-key
         payload))

(defn accepted->refresh
  ([db]
   {:db db
    :dispatch [:fetch-state]})
  ([db status-text]
   {:db (assoc db :status status-text)
    :dispatch [:fetch-state]}))

(rf/reg-event-db
 :frame-direction-changed
 (fn [db [_ frame-id value]]
   (assoc-in db [:frame-inputs frame-id] value)))

(rf/reg-event-fx
 :generate-frame
 (fn [{:keys [db]} [_ frame-id]]
   (request-frame-mutation db
                           "Queueing frame..."
                           :post-generate-frame
                           {:frame-id frame-id
                            :direction (get-in db [:frame-inputs frame-id] "")})))

(rf/reg-event-fx
 :generate-accepted
 (fn [{:keys [db]} [_ _data]]
   (accepted->refresh db)))

(rf/reg-event-db
 :generate-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Request failed: " msg))))

(rf/reg-event-fx
 :add-frame
 (fn [{:keys [db]} [_ chapter-id]]
   (request-frame-mutation db
                           "Adding frame..."
                           :post-add-frame
                           {:chapter-id chapter-id})))

(rf/reg-event-fx
 :add-frame-accepted
 (fn [{:keys [db]} [_ _data]]
   (accepted->refresh db "Frame added.")))

(rf/reg-event-db
 :add-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Request failed: " msg))))

(rf/reg-event-fx
 :delete-frame
 (fn [{:keys [db]} [_ frame-id]]
   (request-frame-mutation db
                           "Deleting frame..."
                           :post-delete-frame
                           {:frame-id frame-id})))

(rf/reg-event-fx
 :delete-frame-accepted
 (fn [{:keys [db]} [_ data]]
   (let [frame (:frame data)]
     (accepted->refresh db (str "Deleted " (deleted-frame-label frame) ".")))))

(rf/reg-event-db
 :delete-frame-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Request failed: " msg))))

(rf/reg-event-fx
 :clear-frame-image
 (fn [{:keys [db]} [_ frame-id]]
   (request-frame-mutation db
                           "Removing frame image..."
                           :post-clear-frame-image
                           {:frame-id frame-id})))

(rf/reg-event-fx
 :clear-frame-image-accepted
 (fn [{:keys [db]} [_ _data]]
   (accepted->refresh db "Frame image removed.")))

(rf/reg-event-db
 :clear-frame-image-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Request failed: " msg))))
