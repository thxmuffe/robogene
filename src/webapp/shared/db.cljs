(ns webapp.shared.db)

(def default-db
  {:status "Loading chapters..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :frame-inputs {}
   :open-frame-actions {}
   :image-ui-by-frame-id {}
   :last-rendered-revision nil
   :active-frame-id nil
   :chapters []
   :gallery-items []
   :new-chapter-description ""
   :new-chapter-panel-open? false
   :show-chapter-celebration? false
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :index}})
