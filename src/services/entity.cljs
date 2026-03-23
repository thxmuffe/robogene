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
         :defaultImageGenerator (settings/default-image-generator)
         :availableImageGenerators (image-generator/configured-generators)}))

(defn entity-by-id [id]
  (get-in @state [:entities (str id)]))

(defn normalize-entity [entity]
  (let [entity-id (some-> (:id entity) str str/trim not-empty)
        payload (or (:payload entity) {})
        parent-id (some-> (:parentId payload)
                          str
                          str/trim
                          not-empty)]
    (when entity-id
      (-> entity
          (assoc :id entity-id)
          (update :children #(mapv str (or % [])))
          (assoc :payload (cond-> payload
                            parent-id (assoc :parentId parent-id)))))))

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
                                 :workspaceId (:workspaceId snapshot)
                                 :revision (:revision snapshot)
                                 :processing (:processing snapshot)
                                 :pendingCount (active-queue-count)
                                 :requiresFetch false
                                 :emittedAt (.toISOString (js/Date.))}
                                 (or extra {})))]
    (realtime/publish-state-update! payload)))

(defn- persist-entities! [entities]
  (reduce (fn [p entity]
            (.then p
                   (fn [persisted]
                     (-> (persist-entity! entity)
                         (.then (fn [saved]
                                  (conj persisted saved)))))))
          (js/Promise.resolve [])
          entities))

(defn- remove-child-id [children child-id]
  (->> (or children [])
       (map str)
       (remove #(= % (str child-id)))
       vec))

(defn- ensure-child-id [children child-id]
  (let [child-id (str child-id)
        children (vec (map str (or children [])))]
    (if (some #(= % child-id) children)
      children
      (conj children child-id))))

(defn- self-child-link? [parent-id child-id]
  (= (str parent-id) (str child-id)))

(defn- validate-entity-graph! [entity]
  (let [entity-id (some-> (:id entity) str not-empty)
        children (mapv str (or (:children entity) []))
        parent-id (some-> (get-in entity [:payload :parentId]) str not-empty)]
    (when (some #(= % entity-id) children)
      (throw (js/Error. "Entity cannot include itself in children.")))
    (when (self-child-link? parent-id entity-id)
      (throw (js/Error. "Entity cannot be its own parent.")))))

(defn- apply-entity-graph-save [entities entity]
  (let [entity* (normalize-entity entity)
        entity-id (:id entity*)
        previous (get entities entity-id)
        previous-parent-id (some-> previous :payload :parentId str not-empty)
        next-parent-id (some-> entity* :payload :parentId str not-empty)]
    (cond-> (assoc entities entity-id entity*)
      (and previous-parent-id (not= previous-parent-id next-parent-id))
      (update previous-parent-id
              (fn [parent]
                (when parent
                  (update parent :children remove-child-id entity-id))))

      next-parent-id
      (update next-parent-id
              (fn [parent]
                (when parent
                  (update parent :children ensure-child-id entity-id)))))))

(defn- entity-descendant-ids [entities entity-id]
  (let [entity (get entities (str entity-id))]
    (reduce (fn [acc child-id]
              (let [child-id (str child-id)]
                (into (conj acc child-id)
                      (entity-descendant-ids entities child-id))))
            []
            (or (:children entity) []))))

(defn save-entity! [entity]
  (let [entity* (or (normalize-entity entity)
                    (throw (js/Error. "Entity save requires :id.")))
        id (:id entity*)]
    (validate-entity-graph! entity*)
    (swap! state
           (fn [s]
             (-> s
                 (update :entities apply-entity-graph-save entity*)
                 (update :revision inc))))
    (let [snapshot @state
          persisted-entities (let [saved (get-in snapshot [:entities id])
                                   parent-id (some-> saved :payload :parentId str not-empty)
                                   parent (when parent-id
                                            (get-in snapshot [:entities parent-id]))]
                               (cond-> [saved]
                                 parent (conj parent)))]
      (-> (persist-entities! persisted-entities)
        (.then (fn [persisted]
                 (swap! state
                        (fn [s]
                          (reduce (fn [acc saved]
                                    (assoc-in acc [:entities (:id saved)] saved))
                                  s
                                  persisted)))
                   (let [saved (get-in @state [:entities id])
                         persisted* (vec persisted)]
                     (emit-state-changed! "entity-updated"
                                          {:entity saved
                                           :entities persisted*})
                     saved)))))))

(defn delete-entity! [id]
  (let [workspace-id (or (:workspaceId @state) "default")
        id (str id)
        snapshot @state
        entities (:entities snapshot)
        entity (get entities id)
        parent-id (some-> entity :payload :parentId str not-empty)
        removed-ids (->> (cons id (entity-descendant-ids entities id))
                         distinct
                         vec)
        next-parent (when-let [parent (get entities parent-id)]
                      (update parent :children remove-child-id id))]
    (swap! state
           (fn [s]
             (-> s
                 (update :entities
                         (fn [current]
                           (cond-> (apply dissoc current removed-ids)
                             next-parent
                             (assoc parent-id next-parent))))
                 (update :revision inc))))
    (-> (reduce (fn [p entity-id]
                  (.then p (fn [_] (store/delete-entity! workspace-id entity-id))))
                (js/Promise.resolve true)
                removed-ids)
        (.then (fn [_]
                 (if next-parent
                   (persist-entity! next-parent)
                   (js/Promise.resolve true))))
        (.then (fn [_]
                 (emit-state-changed! "entity-deleted"
                                      {:id id
                                       :deletedIds removed-ids
                                       :entities (cond-> []
                                                   next-parent (conj next-parent))})
                 true)))))

;; Image Generation
(declare process-queue!)

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
  (image-generator/generate-image! {:generator (get-in entity [:payload :generator])
                                    :prompt (build-prompt-for-entity entity)
                                    :refs []}))

(defn queue-frame-generation! [{:keys [frameId direction generator withoutRoster]}]
  (let [frame-id (str frameId)
        generator-id (some-> generator str str/lower-case str/trim)
        frame (entity-by-id frame-id)]
    (when-not frame
      (throw (js/Error. "Frame not found.")))
    (when-not (= "frame" (:vanityRole frame))
      (throw (js/Error. "Only frame entities can be generated.")))
    (when-not (some #(= % generator-id) (:availableImageGenerators @state))
      (throw (js/Error. (str "Unsupported image generator: " generator-id))))
    (let [next-frame (cond-> (-> frame
                                 (assoc :description (or (some-> direction str) (:description frame) ""))
                                 (assoc-in [:payload :generator] generator-id)
                                 (assoc-in [:payload :imageStatus] "queued")
                                 (assoc-in [:payload :error] nil))
                       (true? withoutRoster)
                       (assoc-in [:payload :withoutRoster] true)

                       (false? withoutRoster)
                       (update :payload dissoc :withoutRoster))]
      (-> (save-entity! next-frame)
          (.then (fn [saved]
                   (process-queue!)
                   saved))))))

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
                     (-> (generate-image! queued)
                         (.then (fn [image-data-url]
                                  (-> (save-entity! (-> queued
                                                        (assoc-in [:payload :imageStatus] "ready")
                                                        (assoc-in [:payload :imageUrl] image-data-url)))
                                      (.then (fn [_]
                                               (process-step!)))
                                      (.catch (fn [err]
                                                (js/console.error "[robogene] frame persistence failed" err)
                                                (emit-state-changed! "failed" {:id id :error (str err)})
                                                (process-step!))))))
                         (.catch (fn [err]
                                   (js/console.error "[robogene] generation failed" err)
                                   (-> (save-entity! (-> queued
                                                         (assoc-in [:payload :imageStatus] "failed")
                                                         (assoc-in [:payload :error] (str err))))
                                       (.catch (fn [persist-err]
                                                 (js/console.error "[robogene] failed saving generation error state" persist-err)
                                                 nil))
                                       (.finally (fn []
                                                   (emit-state-changed! "failed" {:id id :error (str err)})
                                                   (process-step!)))))))))
            (.catch (fn [err]
                      (js/console.error "[robogene] frame pipeline failed" err)
                      (emit-state-changed! "failed" {:id id :error (str err)})
                      (process-step!))))))))

(defn process-queue! []
  (when-not (:processing @state)
    (swap! state assoc :processing true)
    (process-step!)))

;; Initialize
(defn init! []
  (when-not (:workspaceId @state)
    ;; Use stable workspace if provided, else generate once and keep in memory.
    (swap! state assoc :workspaceId (or (settings/workspace-id) (new-uuid))))
  (swap! state assoc
         :availableImageGenerators (image-generator/configured-generators)
         :defaultImageGenerator (settings/default-image-generator))
  (sync-state!))

(init!)
