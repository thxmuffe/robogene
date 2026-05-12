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
         :chapterAgents {}
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

(defn entity-children [entities parent-id]
  (->> (get-in entities [(str parent-id) :children])
       (map #(get entities (str %)))
       (remove nil?)
       vec))

(defn- normalize-search [s]
  (-> (or s "")
      str
      str/trim
      str/lower-case))

(defn- parse-positive-int [value fallback]
  (let [n (js/Number value)]
    (if (and (js/Number.isFinite n) (>= n 0))
      (js/Math.floor n)
      fallback)))

(defn- search-role-rank [role]
  (case (normalize-search role)
    "saga" 0
    "roster" 1
    "chapter" 2
    "character" 3
    4))

(defn- searchable-role? [entity]
  (contains? #{"saga" "roster" "chapter"} (normalize-search (:vanityRole entity))))

(defn- sort-search-entities [entities]
  (sort-by (fn [{:keys [vanityRole title]}]
             [(search-role-rank vanityRole) (normalize-search title)])
           entities))

(defn search-entities [{:keys [query cursor limit]}]
  (let [q (normalize-search query)
        offset (parse-positive-int cursor 0)
        page-size (min 100 (max 1 (parse-positive-int limit 20)))
        entities (->> (vals (:entities @state))
                      (filter searchable-role?)
                      sort-search-entities)
        matching (if (str/blank? q)
                   entities
                   (let [exact-title (->> entities
                                          (filter #(= q (normalize-search (:title %)))))
                         exact-ids (set (map :id exact-title))
                         partial-title (->> entities
                                            (remove #(contains? exact-ids (:id %)))
                                            (filter #(str/includes? (normalize-search (:title %)) q)))]
                     (concat exact-title partial-title)))
        rows (vec matching)
        total (count rows)
        items (->> rows
                   (drop offset)
                   (take page-size)
                   vec)
        next-offset (+ offset (count items))
        next-cursor (when (< next-offset total)
                      (str next-offset))]
    {:query (or query "")
     :items items
     :nextCursor next-cursor
     :total total}))

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

(defn chapter-roster-ids [chapter]
  (->> (or (seq (get-in chapter [:payload :rosterIds]))
           (when-let [roster-id (get-in chapter [:payload :rosterId])]
             [roster-id]))
       (remove str/blank?)
       (mapv str)))

(defn character-entry [entities roster character]
  {:id (:id character)
   :name (:title character)
   :description (:description character)
   :rosterId (:id roster)
   :referenceFrames (->> (entity-children entities (:id character))
                         (filter #(= "frame" (:vanityRole %)))
                         (mapv (fn [frame]
                                 {:id (:id frame)
                                  :description (:description frame)
                                  :imageUrl (get-in frame [:payload :imageUrl])})))})

(defn build-roster-map [entities roster-ids]
  (->> roster-ids
       (keep #(get entities (str %)))
       (mapcat (fn [roster]
                 (->> (entity-children entities (:id roster))
                      (filter #(= "character" (:vanityRole %)))
                      (map #(character-entry entities roster %)))))
       (reduce (fn [acc character]
                 (assoc acc (:name character) character))
               {})))

(defn build-chapter-agent [chapter-id]
  (let [entities (:entities @state)
        chapter (get entities (str chapter-id))]
    (when-not chapter
      (throw (js/Error. "Chapter not found.")))
    (when-not (= "chapter" (:vanityRole chapter))
      (throw (js/Error. "Only chapter entities can have chapter agents.")))
    (let [roster-ids (chapter-roster-ids chapter)]
      {:chapterId (str chapter-id)
       :agentId (str "chapter-agent-" chapter-id)
       :chapter {:id (:id chapter)
                 :title (:title chapter)
                 :description (:description chapter)}
       :rosterIds roster-ids
       :rosterMap (build-roster-map entities roster-ids)
       :createdAt (.toISOString (js/Date.))})))

(defn ensure-agent-exists! [chapter-id]
  (let [chapter-id* (str chapter-id)]
    (or (get-in @state [:chapterAgents chapter-id*])
        (let [agent (build-chapter-agent chapter-id*)]
          (swap! state assoc-in [:chapterAgents chapter-id*] agent)
          agent))))

(defn trash-agent! [chapter-id]
  (swap! state update :chapterAgents dissoc (str chapter-id))
  true)

(defn chapter-id-for-frame [frame]
  (some-> (or (get-in frame [:payload :parentId])
              (get-in frame [:payload :chapterId]))
          str
          not-empty))

(defn generate-image! [entity]
  (let [without-roster? (true? (get-in entity [:payload :withoutRoster]))
        chapter-id (chapter-id-for-frame entity)
        agent (when (and chapter-id (not without-roster?))
                (ensure-agent-exists! chapter-id))]
    (image-generator/generate-image! (cond-> {:generator (get-in entity [:payload :generator])
                                              :prompt (or (:description entity) "")
                                              :refs []}
                                       agent
                                       (assoc :agentId (:agentId agent)
                                              :chapterId chapter-id)))))

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
    (let [chapter-id (chapter-id-for-frame frame)
          agent-id (when (and chapter-id (false? withoutRoster))
                     (str "chapter-agent-" chapter-id))
          next-frame (cond-> (-> frame
                                 (assoc :description (or (some-> direction str) (:description frame) ""))
                                 (assoc-in [:payload :generator] generator-id)
                                 (assoc-in [:payload :imageStatus] "queued")
                                 (assoc-in [:payload :error] nil))
                       agent-id
                       (assoc-in [:payload :agentId] agent-id)

                       agent-id
                       (assoc-in [:payload :agentChapterId] chapter-id)

                       (true? withoutRoster)
                       (-> (assoc-in [:payload :withoutRoster] true)
                           (update :payload dissoc :agentId :agentChapterId))

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
