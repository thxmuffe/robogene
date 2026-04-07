(ns webapp.shared.events.handlers.create
  (:require [re-frame.core :as rf]
            [webapp.shared.events.handlers.frames :as frames]
            [webapp.shared.events.sync :as sync]
            [webapp.shared.model :as model]))

(rf/reg-event-fx
 :create/new-sequence
 (fn [{:keys [db]} _]
   (let [command-id (sync/next-command-id)
         local-id (str "create-sequence-" command-id)
         local-entity {:id local-id
                       :vanityRole "chapter"
                       :title ""
                       :description ""
                       :children []
                       :payload {:isolated true
                                 :createdAt (.toISOString (js/Date.))}}
         command {:id command-id
                  :kind :add-create-sequence
                  :payload {:local-entity local-entity}
                  :success-status "Chapter created."}]
     (frames/queue-command! db "Creating chapter..." command))))
