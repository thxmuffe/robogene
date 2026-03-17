(ns services.entity
  (:require [clojure.string :as str]
            [services.image-generator :as image-generator]
            [services.realtime :as realtime]
            [host.settings :as settings]
            [services.azure-store :as store]
            ["crypto" :as crypto]
            [goog.object :as gobj]))

(defn new-uuid []
  (.randomUUID crypto))

(image-generator/require-startup-env!)

(defonce state
  (atom {:workspaceId nil
         :entities {} ;; Map of id -> entity
         :processing false
         :revision 0
         :openaiOptions (settings/image-settings)}))

(defn entity-by-id [id]
  (get-in @state [:entities (str id)]))

(defn entities-by-role [role]
  (->> (vals (:entities @state))
       (filter #(= (:vanityRole %) (name role)))
       vec))

(defn apply-persisted-state! [entities]
  (swap! state
         (fn [s]
           (assoc s :entities (into {} (map (fn [e] [(str (:id e)) e]) entities)))))
  @state)

(defn sync-state! []
  (let [workspace-id (or (:workspaceId @state) "default")]
    (-> (store/load-entities workspace-id)
        (.then (fn [entities]
                 (apply-persisted-state! entities)
                 @state))
        (.catch (fn [err]
                  (js/console.error "[robogene] storage sync failed" err)
                  (throw err))))))

(defn persist-entity! [entity]
  (let [workspace-id (or (:workspaceId @state) "default")]
    (store/save-entity! workspace-id entity)))

(defn active-queue-count []
  (count (filter (fn [e]
                   (or (= "queued" (get-in e [:payload :imageStatus]))
                       (= "processing" (get-in e [:payload :imageStatus]))))
                 (vals (:entities @state)))))

(defn emit-state-changed! [reason extra]
  (let [snapshot @state
        payload (clj->js (merge {:reason reason
                                  :chapterId (:workspaceId snapshot) ;; Legacy back-compat
                                  :revision (:revision snapshot)
                                  :processing (:processing snapshot)
                                  :pendingCount (active-queue-count)
                                  :emittedAt (.toISOString (js/Date.))}
                                 (or extra {})))]
    (realtime/publish-state-update! payload)))

(defn save-entity! [entity]
  (let [id (str (:id entity))]
    (swap! state (fn [s]
                   (-> s
                       (assoc-in [:entities id] entity)
                       (update :revision inc))))
    (-> (persist-entity! entity)
        (.then (fn [_]
                 (emit-state-changed! "entity-updated" {:entity entity})
                 entity)))))

(defn delete-entity! [id]
  (let [workspace-id (or (:workspaceId @state) "default")
        id (str id)]
    (swap! state (fn [s]
                   (-> s
                       (update :entities dissoc id)
                       (update :revision inc))))
    (-> (store/delete-entity! workspace-id id)
        (.then (fn [_]
                 (emit-state-changed! "entity-deleted" {:id id})
                 true)))))

;; Image Generation
(defn build-prompt-for-entity [entity]
  (let [title (:title entity)
        desc (:description entity)
        parent-id (get-in entity [:payload :parentId])
        parent (when parent-id (entity-by-id parent-id))]
    (str/join "\n\n"
              (filter seq
                      ["Create ONE comic image."
                       (when parent (str "Context: " (:title parent) ". " (:description parent)))
                       (str "Subject: " title)
                       (str "Details: " desc)
                       "Avoid text overlays."]))))

(defn generate-image! [entity]
  (image-generator/generate-image! {:prompt (build-prompt-for-entity entity)
                                    :refs []
                                    :options (:openaiOptions @state)}))

(defn process-step! []
  (let [snapshot @state
        queued (first (filter #(= "queued" (get-in % [:payload :imageStatus])) (vals (:entities snapshot))))]
    (if-not queued
      (do
        (swap! state assoc :processing false)
        (emit-state-changed! "queue-idle" nil))
      (let [id (:id queued)]
        (-> (save-entity! (assoc-in queued [:payload :imageStatus] "processing"))
            (.then (fn [_]
                     (emit-state-changed! "processing" {:id id})
                     (generate-image! queued)))
            (.then (fn [image-data-url]
                     (save-entity! (-> queued
                                       (assoc-in [:payload :imageStatus] "ready")
                                       (assoc-in [:payload :imageUrl] image-data-url)))))
            (.then (fn [_]
                     (emit-state-changed! "ready" {:id id})
                     (process-step!)))
            (.catch (fn [err]
                      (js/console.error "[robogene] generation failed" err)
                      (save-entity! (-> queued
                                        (assoc-in [:payload :imageStatus] "failed")
                                        (assoc-in [:payload :error] (str err))))
                      (emit-state-changed! "failed" {:id id :error (str err)})
                      (process-step!))))))))

(defn process-queue! []
  (when-not (:processing @state)
    (swap! state assoc :processing true)
    (process-step!)))

;; Initialize
(defn init! []
  (when-not (:workspaceId @state)
    (swap! state assoc :workspaceId (new-uuid)))
  (sync-state!))

(init!)
