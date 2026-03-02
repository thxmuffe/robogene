(ns webapp.shared.db)

(def default-db
  {:status "Loading saga..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :frame-inputs {}
   :open-frame-actions {}
   :chapter-name-inputs {}
   :editing-chapter-id nil
   :image-ui-by-frame-id {}
   :last-rendered-revision nil
   :active-frame-id nil
   :saga []
   :gallery-items []
   :new-chapter-description ""
   :new-chapter-panel-open? false
   :show-chapter-celebration? false
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :index}})
