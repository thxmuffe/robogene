(ns webapp.shared.events.handlers.create
  (:require [re-frame.core :as rf]
            [webapp.shared.events.handlers.frames :as frames]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.model :as model]))

(rf/reg-event-fx
 :create/ensure-sequence
 (fn [{:keys [db]} _]
   (let [entity-id (get-in db [:view-state :create :entity-id])
         existing (model/entity-by-id (:entities db) entity-id)]
     (if existing
       {:db db}
       (let [command-id (sync/next-command-id)
             local-id (str "draft-sequence-" command-id)
             local-entity {:id local-id
                           :vanityRole "chapter"
                           :title ""
                           :description ""
                           :children []
                           :payload {:draft true
                                     :isolated true
                                     :createdAt (.toISOString (js/Date.))}}
             command {:id command-id
                      :kind :add-draft-sequence
                      :payload {:local-entity local-entity}
                      :success-status "Draft sequence created."}]
         (frames/queue-command! db "Creating draft sequence..." command))))))
