(ns webapp.shared.db)

(def default-db
  {:status "Loading stories..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :cancel-ui-token 0
   :frame-drafts {}
   :open-frame-actions {}
   :view-state {:gallery {:collapsed-chapter-ids nil}
                :index {:search ""
                        :page 1
                        :per-page 12
                        :name-inputs {}
                        :description-inputs {}
                        :editing-id nil
                        :new-name ""
                        :new-description ""
                        :new-panel-open? false}
                :saga {:name-inputs {}
                       :description-inputs {}
                       :editing-id nil
                       :new-name ""
                       :new-description ""
                       :search ""
                       :page 1
                       :per-page 12
                       :new-panel-open? false
                       :show-celebration? false}
                :roster {:name-inputs {}
                         :description-inputs {}
                         :editing-id nil
                         :new-name ""
                         :new-description ""
                         :new-panel-open? false}}
   :image-ui-by-frame-id {}
   :hidden-frame-images {}
   :last-rendered-revision nil
   :active-frame-id nil
   :sagas []
   :saga []
   :roster []
   :gallery-items []
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :index}})
