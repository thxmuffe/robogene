(ns webapp.shared.search
  "Shared entity search helpers."
  (:require [clojure.string :as str]))

(defn- normalize [s]
  (-> (or s "")
      str
      str/trim
      str/lower-case))

(defn- role-rank [role]
  (case (normalize role)
    "saga" 0
    "chapter" 1
    "character" 2
    3))

(defn- sort-entities [entities]
  (sort-by (fn [{:keys [vanityRole title]}]
             [(role-rank vanityRole) (normalize title)])
           entities))

(defn search-entities
  "Return entities matching query with priority:
   1) title exact (saga->chapter->character->others)
   2) description exact (same order)
   3) title contains query (same order)
   4) description contains query (same order)
   Duplicates removed, stable priority preserved."
  [entities-by-id query]
  (let [q (normalize query)]
    (if (str/blank? q)
      []
      (let [entities (vals entities-by-id)
            exact-title (->> entities (filter #(= q (normalize (:title %)))) sort-entities)
            exact-title-ids (set (map :id exact-title))
            exact-desc (->> entities
                            (remove #(contains? exact-title-ids (:id %)))
                            (filter #(= q (normalize (:description %))))
                            sort-entities)
            used-ids (into exact-title-ids (map :id exact-desc))
            partial-title (->> entities
                               (remove #(contains? used-ids (:id %)))
                               (filter #(str/includes? (normalize (:title %)) q))
                               sort-entities)
            used-ids-2 (into used-ids (map :id partial-title))
            partial-desc (->> entities
                              (remove #(contains? used-ids-2 (:id %)))
                              (filter #(str/includes? (normalize (:description %)) q))
                              sort-entities)]
        (vec (concat exact-title exact-desc partial-title partial-desc))))))
