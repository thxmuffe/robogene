(ns robogene.frontend.db)

(def default-db
  {:status "Loading episodes..."
   :frame-inputs {}
   :open-frame-actions {}
   :last-rendered-revision nil
   :active-frame-id nil
   :episodes []
   :gallery-items []
   :new-episode-description ""
   :latest-state nil
   :route {:view :index}})
