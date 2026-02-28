(ns webapp.shared.events.sync)

(defn next-command-id []
  (str (.now js/Date) "-" (rand-int 1000000)))

(defn enqueue-command [db command]
  (update db :sync-outbox (fnil conj []) command))

(defn dequeue-command [db]
  (update db :sync-outbox (fn [commands]
                            (vec (rest (or commands []))))))

(defn queue-command [db status-text command]
  {:db (-> db
           (assoc :status status-text)
           (enqueue-command command))
   :dispatch [:sync-outbox/process]})

(defn callback-events [command-id]
  {:on-success [:sync-outbox/succeeded command-id]
   :on-failure [:sync-outbox/failed command-id]})
