(ns webapp.shared.db)

(def default-db
  {:status "Loading stories..."
   :pending-api-requests 0
   :wait-lights-visible? true
   :wait-lights-events []
   :cancel-ui-token 0
   
   ;; Generic entity storage (flat pool)
   :entities {}  ; {entity-id -> {id title description children vanityRole payload}}
   :ui-state {}  ; {entity-id -> {name-input description-input editing?}}
   :derived-state {}  ; Cached computations: {parent-id -> [child-ids]} etc
   
   ;; Legacy state (kept for backward compatibility during migration)
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
                :roster-link {:open? false
                              :search ""
                              :target nil}
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
   :rosters []
   :saga []
   :roster []
   :gallery-items []
   :sync-outbox []
   :sync-inflight nil
   :latest-state nil
   :route {:view :index}})
