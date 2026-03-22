(ns webapp.dialog.link-entities-dialog
  (:require [clojure.string :as str]
            [webapp.components.entity-card :as entity-card]
            [webapp.dialog.popup-dialog :as popup-dialog]
            [webapp.shared.model :as model]
            ["@mantine/core" :refer [Button NativeSelect Stack TextInput]]))

(def role-options
  [{:value "saga" :label "Saga"}
   {:value "roster" :label "Roster"}
   {:value "chapter" :label "Chapter"}
   {:value "character" :label "Character"}])

(def sort-options
  [{:value "title-asc" :label "Title A-Z"}
   {:value "title-desc" :label "Title Z-A"}
   {:value "created-desc" :label "Newest first"}
   {:value "created-asc" :label "Oldest first"}])

(defn- role-label [role]
  (or (some->> role-options
               (some (fn [{:keys [value label]}]
                       (when (= value role) label))))
      "Sequence"))

(defn- searchable-text [entity]
  (str/lower-case
   (str/join "\n"
             (remove str/blank?
                     [(some-> (:title entity) str)
                      (some-> (:description entity) str)
                      (some-> entity model/entity-role role-label)]))))

(defn- sort-entities [entities sort-key]
  (let [created-at #(or (some-> % :payload :createdAt str) "")
        title-key #(str/lower-case (model/primary-label %))]
    (vec
     (case sort-key
       "title-desc" (sort-by (juxt title-key :id) #(compare %2 %1) entities)
       "created-desc" (sort-by (juxt created-at :id) #(compare %2 %1) entities)
       "created-asc" (sort-by (juxt created-at :id) entities)
       (sort-by (juxt title-key :id) entities)))))

(defn link-entities-dialog [{:keys [open
                                    title
                                    search
                                    sort
                                    role-filters
                                    entities
                                    entities-map
                                    on-search
                                    on-sort
                                    on-role-filters
                                    on-close
                                    on-select
                                    empty-label]}]
  (let [search* (some-> search str str/lower-case str/trim)
        active-role-filters (set (map #(some-> % str str/lower-case) (or role-filters [])))
        filtered-entities (->> (or entities [])
                               (filter (fn [entity]
                                         (contains? active-role-filters (model/entity-role entity))))
                               (filter (fn [entity]
                                         (or (str/blank? (or search* ""))
                                             (str/includes? (searchable-text entity) search*))))
                               (#(sort-entities % sort)))
        items (mapv (fn [entity]
                      (let [entity-id (:id entity)
                            role (model/entity-role entity)
                            description (some-> (:description entity) str/trim not-empty)
                            item-description (str/join "  "
                                                       (remove str/blank?
                                                               [(role-label role)
                                                                description]))
                            card-entity (assoc entity :description item-description)]
                        ^{:key entity-id}
                        [:div.link-entities-card-wrap
                         [entity-card/entity-card
                          {:entity card-entity
                           :class-name "link-entities-card"
                           :on-click #(when on-select
                                        (on-select entity-id))}]]))
                    filtered-entities)]
    [popup-dialog/popup-dialog {:open open
                                :on-close on-close
                                :size "72rem"
                                :padding "lg"}
     [:div.link-entities-dialog
      [:h3.link-entities-title (or title "Link entities")]
      [:> Stack {:gap "sm"}
       [:> TextInput
       {:value (or search "")
         :placeholder "Search sequences..."
         :className "link-entities-search"
         :onChange #(when on-search
                      (on-search (.. % -target -value)))}]
       [:div.link-entities-toolbar
        [:div.link-entities-role-filters
         (for [{:keys [value label]} role-options]
           ^{:key value}
           [:> Button
            {:variant (if (contains? active-role-filters value) "filled" "default")
             :size "sm"
             :className "link-entities-filter-box"
             :onClick #(when on-role-filters
                         (let [selected? (contains? active-role-filters value)
                               next-values (if selected?
                                             (vec (remove (fn [selected]
                                                            (= value selected))
                                                          (or role-filters [])))
                                             (vec (distinct (conj (vec (or role-filters [])) value))))]
                           (on-role-filters next-values)))}
            label])]
        [:> NativeSelect
         {:label "Sort"
          :value (or sort "title-asc")
          :data (clj->js sort-options)
          :className "link-entities-sort"
          :onChange #(when on-sort
                       (on-sort (.. % -target -value)))}]]
       (if (seq items)
         (into [:div.link-entities-grid] items)
         [:p.link-entities-empty (or empty-label "No sequence entities match this search.")])]]]))
