(ns webapp.shared.db)

(def default-db
  {:status "Loading saga..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :frame-inputs {}
   :open-frame-actions {}
   :view-state {:saga {:name-inputs {}
                       :editing-id nil
                       :new-description ""
                       :new-panel-open? false
                       :show-celebration? false}
                :roster {:name-inputs {}
                             :editing-id nil
                             :new-description ""
                             :new-panel-open? false}}
   :image-ui-by-frame-id {}
   :last-rendered-revision nil
   :active-frame-id nil
   :saga []
   :roster []
   :gallery-items []
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :saga}})
