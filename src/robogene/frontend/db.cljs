(ns robogene.frontend.db)

(def default-db
  {:status "Loading episodes..."
   :pending-api-requests 0
   :wait-dialog-visible? false
   :frame-inputs {}
   :open-frame-actions {}
   :last-rendered-revision nil
   :active-frame-id nil
   :episodes []
   :gallery-items []
   :new-episode-description ""
   :new-episode-panel-open? false
   :show-episode-celebration? false
   :latest-state nil
   :route {:view :index}})
