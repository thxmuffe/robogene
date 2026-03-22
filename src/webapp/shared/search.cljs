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
    "roster" 1
    "chapter" 2
    "character" 3
    4))

(defn- sort-entities [entities]
  (sort-by (fn [{:keys [vanityRole title]}]
             [(role-rank vanityRole) (normalize title)])
           entities))

(defn- searchable-role? [entity]
  (contains? #{"saga" "roster" "chapter"} (normalize (:vanityRole entity))))

(defn search-entities
  "Return saga/roster/chapter entities matching query by title only:
   1) title exact
   2) title contains query
   Result order is saga -> roster -> chapter -> title."
  [entities-by-id query]
  (let [q (normalize query)
        entities (->> (vals entities-by-id)
                      (filter searchable-role?))]
    (if (str/blank? q)
      (vec (sort-entities entities))
      (let [exact-title (->> entities (filter #(= q (normalize (:title %)))) sort-entities)
            exact-title-ids (set (map :id exact-title))
            partial-title (->> entities
                               (remove #(contains? exact-title-ids (:id %)))
                               (filter #(str/includes? (normalize (:title %)) q))
                               sort-entities)]
        (vec (concat exact-title partial-title))))))
