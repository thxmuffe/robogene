(ns webapp.shared.db)

(def default-db
  {:status "Loading chapters..."
   :pending-api-requests 0
   :wait-lights-visible? false
   :wait-lights-events []
   :frame-inputs {}
   :open-frame-actions {}
   :last-rendered-revision nil
   :active-frame-id nil
   :chapters []
   :gallery-items []
   :new-chapter-description ""
   :new-chapter-panel-open? false
   :show-chapter-celebration? false
   :latest-state nil
   :route {:view :index}})
