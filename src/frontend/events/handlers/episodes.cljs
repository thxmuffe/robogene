(ns frontend.events.handlers.episodes
  (:require [re-frame.core :as rf]
            [clojure.string :as str]))

(rf/reg-event-db
 :new-episode-description-changed
 (fn [db [_ value]]
   (assoc db :new-episode-description value)))

(rf/reg-event-db
 :set-new-episode-panel-open
 (fn [db [_ open?]]
   (assoc db :new-episode-panel-open? (true? open?))))

(rf/reg-event-db
 :episode-celebration-ended
 (fn [db _]
   (assoc db :show-episode-celebration? false)))

(rf/reg-event-fx
 :add-episode
 (fn [{:keys [db]} _]
   (if (str/blank? (or (:new-episode-description db) ""))
     {:db (assoc db
                 :new-episode-panel-open? true
                 :status "Add an episode theme first.")}
     {:db (assoc db :status "Creating episode...")
      :post-add-episode {:description (:new-episode-description db)}})))

(rf/reg-event-fx
 :add-episode-accepted
 (fn [{:keys [db]} [_ _data]]
   {:db (assoc db
               :new-episode-description ""
               :new-episode-panel-open? false
               :show-episode-celebration? true
               :status "Episode created.")
    :start-episode-celebration true}))

(rf/reg-event-db
 :add-episode-failed
 (fn [db [_ msg]]
   (assoc db :status (str "Create episode failed: " msg))))
