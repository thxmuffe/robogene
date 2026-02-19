(ns robogene.frontend.db)

(def default-db
  {:status "Loading existing frames..."
   :direction-input ""
   :direction-dirty? false
   :submitting? false
   :pending-count 0
   :last-rendered-revision nil
   :gallery-items []
   :latest-state nil})
