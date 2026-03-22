(ns webapp.shared.events.handlers.visual-effects
  (:require [re-frame.core :as rf]))

(rf/reg-event-db
 :chapter-celebration-ended
 (fn [db _]
   (assoc-in db [:view-state :saga :show-celebration?] false)))
