(ns webapp.shared.model
  (:require [clojure.string :as str]))

(defn entity-id [entity]
  (some-> (:id entity) str))

(defn normalize-entity-id [value]
  (some-> value str js/decodeURIComponent str/trim not-empty))

(defn entity-role [entity]
  (some-> (:vanityRole entity) str str/lower-case))

(defn entity-children-ids [entity]
  (vec (or (:children entity) [])))

(defn entity-sequence? [entity]
  (let [kind (:kind entity)
        kind* (cond
                (keyword? kind) (name kind)
                (string? kind) kind
                :else nil)]
    (or (= "sequence" (some-> kind* str/lower-case))
        (seq (entity-children-ids entity)))))

(defn entity-item? [entity]
  (not (entity-sequence? entity)))

(defn entity-by-id [entities target]
  (let [target-id (normalize-entity-id target)]
    (or (get entities target-id)
        (some (fn [[_ entity]]
                (when (= target-id (entity-id entity))
                  entity))
              entities))))

(defn entity-children [entities entity-or-id]
  (let [entity (if (map? entity-or-id) entity-or-id (entity-by-id entities entity-or-id))]
    (->> (entity-children-ids entity)
         (map #(entity-by-id entities %))
         (remove nil?)
         vec)))

(defn image-url [entity]
  (let [payload (:payload entity)]
    (some-> (or (:imageUrl payload)
                (:imageDataUrl payload)
                (:imageUrl entity)
                (:imageDataUrl entity))
            str
            str/trim
            not-empty)))

(defn entity-parent-id [entity]
  (some-> (or (get-in entity [:payload :parentId])
              (get-in entity [:payload :chapterId])
              (get-in entity [:payload :characterId])
              (get-in entity [:payload :sagaId])
              (get-in entity [:payload :rosterId]))
          str
          not-empty))

(defn frame-owner-id [entity]
  (some-> (or (get-in entity [:payload :parentId])
              (get-in entity [:payload :chapterId])
              (get-in entity [:payload :characterId]))
          str
          not-empty))

(defn frame-owner-type [entity fallback]
  (or (some-> (get-in entity [:payload :ownerType]) str not-empty)
      fallback
      "saga"))

(defn primary-label [entity]
  (or (some-> (:title entity) str/trim not-empty)
      (some-> (:description entity) str/trim not-empty)
      "Untitled"))

(defn secondary-label [entity]
  (let [title (some-> (:title entity) str/trim not-empty)
        description (some-> (:description entity) str/trim not-empty)]
    (when (and title description (not= title description))
      description)))

(defn preview-image-url
  ([entities target-id]
   (preview-image-url entities target-id #{}))
  ([entities target-id seen]
   (let [entity (entity-by-id entities target-id)
         id (entity-id entity)]
     (cond
       (nil? entity) nil
       (contains? seen id) nil
       :else
       (or (image-url entity)
           (some #(preview-image-url entities % (conj seen id))
                 (entity-children-ids entity)))))))

(defn frame-id-of [frame]
  (:frameId frame))

(defn ordered-frames [frames]
  (->> (or frames [])
       (sort-by (fn [frame]
                  [(or (:frameNumber frame) js/Number.MAX_SAFE_INTEGER)
                   (or (:createdAt frame) "")
                   (or (:frameId frame) "")]))
       vec))

(defn relative-frame-by-id [frames frame-id delta]
  (let [{:keys [prev next active-frame]}
        (loop [remaining (seq frames)
               prev nil]
          (if-let [active-frame (first remaining)]
            (if (= (frame-id-of active-frame) frame-id)
              {:prev prev
               :next (second remaining)
               :active-frame active-frame}
              (recur (rest remaining) active-frame))
            {:prev nil :next nil :active-frame nil}))
        first-frame (first frames)
        last-frame (last frames)]
    (cond
      (or (empty? frames) (zero? delta)) nil
      (pos? delta) (or next first-frame)
      (neg? delta) (or prev last-frame)
      (some? active-frame) active-frame
      :else first-frame)))

(defn parse-query-param [query key]
  (some-> (re-find (re-pattern (str "(^|&)" key "=([^&]+)")) query)
          (nth 2 nil)
          js/decodeURIComponent
          str/trim
          not-empty))

(defn parse-hash-route [hash]
  (let [raw (or hash "")]
    (or
     (when-let [[_ frame-id query] (re-matches #"^#/frame/([^/?#]+)(?:\?(.*))?$" raw)]
       (let [query* (or query "")
             fullscreen? (boolean (re-find #"(^|&)fullscreen=1(&|$)" query*))
             from-page (cond
                         (re-find #"(^|&)from=roster(&|$)" query*) :roster
                         (re-find #"(^|&)from=saga(&|$)" query*) :saga
                         :else nil)
             frame-id* (some-> frame-id js/decodeURIComponent str/trim not-empty)]
         {:view :frame
          :frame-id frame-id*
          :entity-id frame-id*
          :fullscreen? fullscreen?
          :from-page from-page
          :roster-id (parse-query-param query* "rosterId")
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ chapter frame query] (re-matches #"^#/chapter/([^/]+)/frame/([^?]+)(?:\?(.*))?$" raw)]
       (let [query* (or query "")
             fullscreen? (boolean (re-find #"(^|&)fullscreen=1(&|$)" query*))
             from-page (cond
                         (re-find #"(^|&)from=roster(&|$)" query*) :roster
                         (re-find #"(^|&)from=saga(&|$)" query*) :saga
                         :else nil)]
         {:view :frame
          :chapter chapter
          :frame-id frame
          :entity-id frame
          :fullscreen? fullscreen?
          :from-page from-page
          :roster-id (parse-query-param query* "rosterId")
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ character-id] (re-matches #"^#/character/([^/?#]+)(?:\?.*)?$" raw)]
       (let [entity-id (some-> character-id js/decodeURIComponent str/trim not-empty)]
         {:view :character
          :entity-id entity-id
          :character-id entity-id}))
     (when-let [[_ entity-id] (re-matches #"^#/entity/([^/?#]+)(?:\?.*)?$" raw)]
       (let [entity-id* (some-> entity-id js/decodeURIComponent str/trim not-empty)]
         {:view :entity
          :entity-id entity-id*}))
     (when-let [[_ chapter query] (re-matches #"^#/chapter/([^/?#]+)(?:\?(.*))?$" raw)]
       (let [chapter-id (normalize-entity-id chapter)]
         {:view :chapter
          :chapter chapter-id
          :entity-id chapter-id
          :saga-id (parse-query-param (or query "") "sagaId")}))
     (when-let [[_ query] (re-matches #"^#/roster/?(?:\?(.*))?$" raw)]
       (let [query* (or query "")]
         {:view :roster
          :roster-id nil
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ roster-id query] (re-matches #"^#/roster/([^/?#]+)(?:\?(.*))?$" raw)]
       (let [query* (or query "")
             entity-id (some-> roster-id js/decodeURIComponent str/trim not-empty)]
         {:view :roster
          :roster-id entity-id
          :entity-id entity-id
          :saga-id (parse-query-param query* "sagaId")}))
     (when-let [[_ saga-id] (re-matches #"^#/saga/([^/?#]+)(?:\?.*)?$" raw)]
       (let [entity-id (some-> saga-id js/decodeURIComponent str/trim not-empty)]
         {:view :saga
          :saga-id entity-id
          :entity-id entity-id}))
     (when (or (str/blank? raw)
               (re-matches #"^#/?$" raw))
       {:view :index})
     {:view :index})))

(defn index-hash []
  "#/")

(defn entity-hash [entity-id]
  (if (str/blank? (or entity-id ""))
    (index-hash)
    (str "#/entity/" (js/encodeURIComponent entity-id))))

(defn character-hash [character-id]
  (if (str/blank? (or character-id ""))
    (index-hash)
    (str "#/character/" (js/encodeURIComponent character-id))))

(defn saga-hash [saga-id]
  (if (str/blank? (or saga-id ""))
    (index-hash)
    (str "#/saga/" (js/encodeURIComponent saga-id))))

(defn roster-hash
  ([roster-id]
   (roster-hash roster-id nil))
  ([roster-id saga-id]
   (str (if (str/blank? (or roster-id ""))
          "#/roster"
          (str "#/roster/" (js/encodeURIComponent roster-id)))
        (when-not (str/blank? (or saga-id ""))
          (str "?sagaId=" (js/encodeURIComponent saga-id))))))

(defn chapter-hash
  ([chapter-id]
   (chapter-hash chapter-id nil))
  ([chapter-id saga-id]
   (str "#/chapter/" (js/encodeURIComponent chapter-id)
        (when-not (str/blank? (or saga-id ""))
          (str "?sagaId=" (js/encodeURIComponent saga-id))))))

(defn frame-hash
  ([frame-id]
   (frame-hash frame-id false nil nil nil))
  ([frame-id fullscreen?]
   (frame-hash frame-id fullscreen? nil nil nil))
  ([frame-id fullscreen? from-page]
   (frame-hash frame-id fullscreen? from-page nil nil))
  ([frame-id fullscreen? from-page saga-id]
   (frame-hash frame-id fullscreen? from-page saga-id nil))
  ([frame-id fullscreen? from-page saga-id roster-id]
   (let [query-parts (cond-> []
                       fullscreen? (conj "fullscreen=1")
                       (#{:saga :roster} from-page) (conj (str "from=" (name from-page)))
                       (not (str/blank? (or saga-id ""))) (conj (str "sagaId=" (js/encodeURIComponent saga-id)))
                       (and (= :roster from-page)
                            (not (str/blank? (or roster-id ""))))
                       (conj (str "rosterId=" (js/encodeURIComponent roster-id))))
         query (when (seq query-parts)
                 (str "?" (str/join "&" query-parts)))]
     (str "#/frame/" (js/encodeURIComponent frame-id) (or query "")))))

(defn route-hash-for-entity [entity]
  (let [id (:id entity)
        role (entity-role entity)]
    (case role
      "saga" (saga-hash id)
      "roster" (roster-hash id)
      "chapter" (chapter-hash id)
      "character" (character-hash id)
      "frame" (frame-hash id)
      (entity-hash id))))

(defn parse-json-safe [text]
  (js->clj (.parse js/JSON text) :keywordize-keys true))

(defn entity-role-rank [role]
  (case (some-> role str str/lower-case)
    "saga" 0
    "chapter" 1
    "roster" 2
    "character" 3
    "frame" 4
    5))

(defn sort-entities [entities]
  (sort-by (fn [entity]
             [(entity-role-rank (entity-role entity))
              (primary-label entity)
              (entity-id entity)])
           entities))

(defn entities-by-role [entities role]
  (->> (vals entities)
       (filter #(= (entity-role %) (name role)))
       sort-entities
       vec))

(defn children-by-role [entities parent-id role]
  (->> (entity-children entities parent-id)
       (filter #(= (entity-role %) (name role)))
       sort-entities
       vec))

(defn root-entities [entities]
  (let [child-ids (->> (vals entities)
                       (mapcat entity-children-ids)
                       set)]
    (->> (vals entities)
         (remove #(contains? child-ids (entity-id %)))
         sort-entities
         vec)))

(defn frame-entity? [entity]
  (let [role (entity-role entity)
        payload (:payload entity)]
    (and (entity-item? entity)
         (or (= role "frame")
             (contains? payload :imageUrl)
             (contains? payload :imageStatus)
             (contains? payload :frameNumber)))))

(defn frame-row [entity]
  {:frameId (:id entity)
   :title (:title entity)
   :description (:description entity)
   :frameDescription (:description entity)
   :imageUrl (get-in entity [:payload :imageUrl])
   :imageStatus (get-in entity [:payload :imageStatus])
   :frameNumber (or (get-in entity [:payload :frameNumber]) js/Number.MAX_SAFE_INTEGER)
   :chapterId (frame-owner-id entity)
   :ownerType (frame-owner-type entity
                                (when (= "character" (entity-role entity))
                                  "character"))
   :createdAt (get-in entity [:payload :createdAt])
   :error (get-in entity [:payload :error])})

(defn gallery-frames [entities]
  (->> (vals entities)
       (filter frame-entity?)
       (map frame-row)
       ordered-frames))

(defn frames-for-owner [entities _owner-type owner-id]
  (->> (entity-children entities owner-id)
       (filter frame-entity?)
       (map frame-row)
       ordered-frames))

(defn frames-for-chapter [entities chapter-id]
  (frames-for-owner entities "saga" chapter-id))

(defn chapter-parent-id [entities chapter-id]
  (or (get-in (entity-by-id entities chapter-id) [:payload :parentId])
      (some (fn [entity]
              (when (some #(= chapter-id %) (entity-children-ids entity))
                (:id entity)))
            (vals entities))))

(defn owner-name [entities entity-id]
  (some-> (entity-by-id entities entity-id) primary-label))

(defn status-line [state entities]
  (let [all (vals (or entities {}))
        sequences (count (filter entity-sequence? all))
        items (count (remove entity-sequence? all))
        pending (or (:pendingCount state) 0)]
    (str "Sequences: " sequences
         " | Items: " items
         (if (pos? pending)
           (str " | Queue: " pending)
           " | Queue idle"))))
