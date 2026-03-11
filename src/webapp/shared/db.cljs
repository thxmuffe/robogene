(ns webapp.shared.db)

(def default-db
  {:status "Loading saga..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :cancel-ui-token 0
   :frame-drafts {}
   :open-frame-actions {}
   :view-state {:gallery {:collapsed-chapter-ids #{}}
                :saga {:name-inputs {}
                       :description-inputs {}
                       :editing-id nil
                       :new-name ""
                       :new-description ""
                       :new-panel-open? false
                       :show-celebration? false}
                :roster {:name-inputs {}
                         :description-inputs {}
                         :editing-id nil
                         :new-name ""
                         :new-description ""
                         :new-panel-open? false}}
   :image-ui-by-frame-id {}
   :last-rendered-revision nil
   :active-frame-id nil
   :saga-meta {:name "Robot Emperor"
               :description ""}
   :saga []
   :roster []
   :gallery-items []
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :saga}})
