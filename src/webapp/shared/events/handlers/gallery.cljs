(ns webapp.shared.events.handlers.gallery
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 :toggle-gallery-chapter-collapsed
 (fn [db [_ chapter-id]]
   (update-in db
              [:view-state :gallery :collapsed-chapter-ids]
              (fn [ids]
                (let [ids (or ids #{})]
                  (if (contains? ids chapter-id)
                    (disj ids chapter-id)
                    (conj ids chapter-id)))))))
