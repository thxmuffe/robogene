(ns webapp.shared.events.handlers.entity-forms
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.shared.store :as store]))

(def allowed-vanity-roles #{"saga" "roster" "chapter" "character"})

(defn- role-parent-id [entity next-role]
  (let [payload (or (:payload entity) {})
        current-parent-id (model/entity-parent-id entity)]
    (case next-role
      "chapter" (or (some-> (:sagaId payload) str not-empty)
                    current-parent-id)
      "character" (or (some-> (:rosterId payload) str not-empty)
                      current-parent-id)
      "saga" nil
      "roster" nil
      current-parent-id)))

(defn- role-payload-patch [entity next-role]
  (let [payload (or (:payload entity) {})
        next-parent-id (role-parent-id entity next-role)]
    (if (seq (or next-parent-id ""))
      (assoc payload :parentId next-parent-id)
      (dissoc payload :parentId))))

(defn- frame-role-payload-patch [entity-id next-role frame-entity]
  (let [payload (or (:payload frame-entity) {})]
    (case next-role
      "chapter"
      (-> payload
          (assoc :parentId entity-id
                 :ownerType "saga"
                 :chapterId entity-id)
          (dissoc :characterId))

      "character"
      (-> payload
          (assoc :parentId entity-id
                 :ownerType "character"
                 :characterId entity-id)
          (dissoc :chapterId))

      payload)))

(defn entity-label->keys [entity-label]
  (case (str entity-label)
    "saga"
    {:editing-key [:view-state :index :editing-id]
     :name-inputs-key [:view-state :index :name-inputs]
     :description-inputs-key [:view-state :index :description-inputs]
     :name-key [:view-state :index :new-name]
     :description-key [:view-state :index :new-description]
     :panel-open-key [:view-state :index :new-panel-open?]}

    "character"
    {:editing-key [:view-state :roster :editing-id]
     :name-inputs-key [:view-state :roster :name-inputs]
     :description-inputs-key [:view-state :roster :description-inputs]
     :name-key [:view-state :roster :new-name]
     :description-key [:view-state :roster :new-description]
     :panel-open-key [:view-state :roster :new-panel-open?]}

    {:editing-key [:view-state :saga :editing-id]
     :name-inputs-key [:view-state :saga :name-inputs]
     :description-inputs-key [:view-state :saga :description-inputs]
     :name-key [:view-state :saga :new-name]
     :description-key [:view-state :saga :new-description]
     :panel-open-key [:view-state :saga :new-panel-open?]}))

(rf/reg-event-db
 :new-saga-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :index :new-name] value)))

(rf/reg-event-db
 :new-chapter-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :saga :new-name] value)))

(rf/reg-event-db
 :new-character-name-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :roster :new-name] value)))

(rf/reg-event-db
 :new-saga-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :index :new-description] value)))

(rf/reg-event-db
 :new-chapter-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :saga :new-description] value)))

(rf/reg-event-db
 :new-character-description-changed
 (fn [db [_ value]]
   (assoc-in db [:view-state :roster :new-description] value)))

(rf/reg-event-db
 :start-entity-edit
 (fn [db [_ entity-label entity-id current-name current-description]]
   (let [{:keys [editing-key name-inputs-key description-inputs-key]} (entity-label->keys entity-label)]
     (-> db
         (assoc-in editing-key entity-id)
         (assoc-in (conj name-inputs-key entity-id) (or current-name ""))
         (assoc-in (conj description-inputs-key entity-id) (or current-description ""))))))

(rf/reg-event-db
 :entity-name-input-changed
 (fn [db [_ entity-label entity-id value]]
   (let [{:keys [name-inputs-key]} (entity-label->keys entity-label)]
     (assoc-in db (conj name-inputs-key entity-id) value))))

(rf/reg-event-db
 :entity-description-input-changed
 (fn [db [_ entity-label entity-id value]]
   (let [{:keys [description-inputs-key]} (entity-label->keys entity-label)]
     (assoc-in db (conj description-inputs-key entity-id) value))))

(rf/reg-event-db
 :cancel-entity-name-edit
 (fn [db [_ entity-label]]
   (let [{:keys [editing-key]} (entity-label->keys entity-label)]
     (assoc-in db editing-key nil))))

(rf/reg-event-fx
 :save-entity
 (fn [{:keys [db]} [_ entity-id patch]]
   (let [entity (model/entity-by-id (:entities db) entity-id)
         role (model/entity-role entity)
         {:keys [editing-key]} (entity-label->keys role)
         title (cond
                 (contains? patch :title) (some-> (:title patch) str str/trim)
                 :else (:title entity))
         vanity-role (when (contains? patch :vanityRole)
                       (some-> (:vanityRole patch) str str/trim str/lower-case))
         next-patch (cond-> {}
                      (contains? patch :title) (assoc :title title)
                      (contains? patch :description) (assoc :description (some-> (:description patch) str))
                      (contains? patch :payload) (assoc :payload (:payload patch))
                      (contains? patch :vanityRole) (assoc :vanityRole vanity-role))]
     (if (or (nil? entity)
             (and (contains? patch :title)
                  (str/blank? (or title "")))
             (and (contains? patch :vanityRole)
                  (not (contains? allowed-vanity-roles vanity-role))))
       {:db db}
       {:db (assoc-in db editing-key nil)
        :dispatch [:update-entity entity-id next-patch]}))))

(rf/reg-event-fx
 :change-entity-role
 (fn [{:keys [db]} [_ entity-id next-role]]
   (let [entity (model/entity-by-id (:entities db) entity-id)
         next-role (some-> next-role str str/trim str/lower-case)
         current-role (model/entity-role entity)
         old-parent-id (model/entity-parent-id entity)
         next-parent-id (role-parent-id entity next-role)
         child-frame-ids (vec (or (:children entity) []))
         child-frame-patches (keep (fn [child-id]
                                     (let [child-entity (model/entity-by-id (:entities db) child-id)
                                           child-role (model/entity-role child-entity)]
                                       (when (= child-role "frame")
                                         [:update-entity child-id
                                          {:payload (frame-role-payload-patch (str entity-id) next-role child-entity)}])))
                                   child-frame-ids)
         dispatches (cond-> []
                      (and (seq (or old-parent-id ""))
                           (not= (str old-parent-id) (str next-parent-id)))
                      (conj [:entity-remove-child old-parent-id entity-id])

                      (and (seq (or next-parent-id ""))
                           (not= (str old-parent-id) (str next-parent-id)))
                      (conj [:entity-add-child next-parent-id entity-id])

                      true
                      (into child-frame-patches)

                      true
                      (conj [:save-entity entity-id
                             {:vanityRole next-role
                              :payload (role-payload-patch entity next-role)}]))]
     (if (or (nil? entity)
             (not (contains? allowed-vanity-roles next-role))
             (= current-role next-role))
       {:db db}
       {:db db
        :dispatch-n dispatches}))))

(rf/reg-event-db
 :set-new-saga-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :index :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :set-new-chapter-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :saga :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :set-new-character-panel-open
 (fn [db [_ open?]]
   (assoc-in db [:view-state :roster :new-panel-open?] (true? open?))))

(rf/reg-event-db
 :collection-search-changed
 (fn [db [_ view-id value]]
   (-> db
       (assoc-in [:view-state view-id :search] (or value ""))
       (assoc-in [:view-state view-id :page] 1))))

(rf/reg-event-fx
 :search/request
 (fn [{:keys [db]} [_ {:keys [append? cursor]}]]
   (let [query (get-in db [:view-state :search-page :search] "")
         limit (get-in db [:view-state :search-page :per-page] 20)]
     {:db (assoc-in db [:view-state :search-page :loading?] true)
      :search-entities {:query query
                        :cursor cursor
                        :limit limit
                        :append? append?}})))

(rf/reg-event-fx
 :search/load-more
 (fn [{:keys [db]} _]
   (if-let [cursor (get-in db [:view-state :search-page :next-cursor])]
     {:dispatch [:search/request {:append? true
                                  :cursor cursor}]}
     {:db db})))

(rf/reg-event-fx
 :search/succeeded
 (fn [{:keys [db]} [_ {:keys [query append?]} payload]]
   (let [items (vec (or (:items payload) []))
         ids (->> items (keep :id) vec)
         current-query (get-in db [:view-state :search-page :search] "")]
     (if (not= (or query "") current-query)
       {:db db}
       (let [normalized-items (keep store/normalize-entity items)
             entities-map (into {}
                                (map (fn [entity] [(:id entity) entity]))
                                normalized-items)
             next-entities (merge (or (:entities db) {}) entities-map)]
         {:db (-> db
                  (assoc :entities next-entities)
                  (assoc :derived-state (store/compute-derived-state next-entities))
                  (assoc-in [:view-state :search-page :loading?] false)
                  (assoc-in [:view-state :search-page :loaded-query] query)
                  (assoc-in [:view-state :search-page :next-cursor] (:nextCursor payload))
                  (assoc-in [:view-state :search-page :result-ids]
                            (if append?
                              (vec (concat (get-in db [:view-state :search-page :result-ids] []) ids))
                              ids)))})))))

(rf/reg-event-db
 :search/failed
 (fn [db [_ _ctx _msg]]
   (assoc-in db [:view-state :search-page :loading?] false)))

(rf/reg-event-db
 :collection-page-selected
 (fn [db [_ view-id page]]
   (assoc-in db [:view-state view-id :page] (max 1 (or page 1)))))
