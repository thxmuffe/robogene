(ns robogene.frontend.db)

(def default-db
  {:status "Loading episodes..."
   :frame-inputs {}
   :last-rendered-revision nil
   :episodes []
   :gallery-items []
   :new-episode-description ""
   :latest-state nil
   :route {:view :index}})
