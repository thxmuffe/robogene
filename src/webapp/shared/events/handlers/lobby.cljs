(ns webapp.shared.events.handlers.lobby
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.model :as model]
            [webapp.shared.store :as store]
            [webapp.shared.events.handlers.frames :as frames]
            [webapp.shared.events.sync :as sync]))

(def default-source-name "chatgpt")

(defn- normalized-source-name [value]
  (or (some-> value str str/trim not-empty)
      default-source-name))

(defn- next-lobby-item-id []
  (str "lobby-item-" (.now js/Date) "-" (rand-int 1000000)))

(defn- normalize-lobby-item [{:keys [id image-url image-status label]}]
  {:id (or (some-> id str not-empty) (next-lobby-item-id))
   :image-url (some-> image-url str not-empty)
   :image-status (or (some-> image-status str not-empty)
                     (when (some-> image-url str not-empty) "uploading")
                     "draft")
   :label (some-> label str not-empty)})

(defn- lobby-items [db]
  (vec (or (get-in db [:view-state :lobby :items]) [])))

(defn- update-lobby-items [db items]
  (assoc-in db [:view-state :lobby :items] (vec items)))

(defn- move-item [items item-id delta]
  (let [items* (vec items)
        idx (first (keep-indexed (fn [index item]
                                   (when (= (str (:id item)) (str item-id))
                                     index))
                                 items*))
        swap-idx (+ (or idx -1) delta)]
    (if (or (nil? idx)
            (neg? swap-idx)
            (>= swap-idx (count items*)))
      items*
      (assoc items*
             idx (nth items* swap-idx)
             swap-idx (nth items* idx)))))

(defn- target-owner-type [target-entity]
  (case (model/entity-role target-entity)
    "chapter" "saga"
    "character" "character"
    nil))

(defn- import-command [target-id owner-type frame-number {:keys [id image-url image-status]}]
  (let [command-id (sync/next-command-id)
        frame-id (str "lobby-frame-" command-id "-" id)
        local-frame (cond-> (store/build-local-frame frame-id target-id owner-type)
                      true (assoc :frameNumber frame-number)
                      (some? image-url) (assoc :imageUrl image-url)
                      (some? image-url) (assoc :imageStatus (or image-status "uploading")))
        local-entity (frames/frame-entity frame-id
                                          target-id
                                          owner-type
                                          frame-number
                                          image-url
                                          (or (:imageStatus local-frame)
                                              image-status
                                              "draft"))]
    [:queue-command-direct
     {:id command-id
      :kind :add-frame
      :payload {:owner-id target-id
                :owner-type owner-type
                :frame-id frame-id
                :local-frame local-frame
                :local-entity local-entity}
      :success-status "Imported lobby item."}
     "Importing lobby items..."]))

(rf/reg-event-db
 :lobby/set-target
 (fn [db [_ target-id]]
   (assoc-in db [:view-state :lobby :target-id] (some-> target-id str not-empty))))

(rf/reg-event-db
 :lobby/set-source
 (fn [db [_ source-name]]
   (assoc-in db [:view-state :lobby :source-name] (normalized-source-name source-name))))

(rf/reg-event-db
 :lobby/add-staged-item
 (fn [db [_ item]]
   (update-lobby-items db (conj (lobby-items db) (normalize-lobby-item item)))))

(rf/reg-event-db
 :lobby/add-empty-slot
 (fn [db _]
   (update-lobby-items db (conj (lobby-items db)
                                (normalize-lobby-item {:label "Empty slot"})))))

(rf/reg-event-db
 :lobby/remove-staged-item
 (fn [db [_ item-id]]
   (update-lobby-items db
                       (remove #(= (str (:id %)) (str item-id))
                               (lobby-items db)))))

(rf/reg-event-db
 :lobby/move-staged-item
 (fn [db [_ item-id delta]]
   (update-lobby-items db (move-item (lobby-items db) item-id delta))))

(rf/reg-event-db
 :lobby/clear
 (fn [db _]
   (-> db
       (assoc-in [:view-state :lobby :items] [])
       (assoc-in [:view-state :lobby :source-name] default-source-name))))

(rf/reg-event-fx
 :lobby/import
 (fn [{:keys [db]} _]
   (let [target-id (get-in db [:view-state :lobby :target-id])
         target-entity (model/entity-by-id (:entities db) target-id)
         owner-type (target-owner-type target-entity)
         items (lobby-items db)]
     (cond
       (str/blank? (or target-id ""))
       {:db (assoc db :status "Choose a target sequence first.")}

       (nil? owner-type)
       {:db (assoc db :status "Lobby import currently supports chapters and characters only.")}

       (empty? items)
       {:db (assoc db :status "Add at least one image or empty slot first.")}

       :else
       (let [base-frame-number (store/next-frame-number db target-id owner-type)]
         {:db (-> db
                  (assoc-in [:view-state :lobby :items] [])
                  (assoc :status (str "Importing " (count items) " lobby item"
                                      (when (not= 1 (count items)) "s")
                                      "...")))
          :dispatch-n (vec (concat
                            (map-indexed (fn [idx item]
                                           (import-command target-id
                                                           owner-type
                                                           (+ base-frame-number idx)
                                                           item))
                                         items)
                            [[:navigate-entity-page (:id target-entity)]]))})))))
