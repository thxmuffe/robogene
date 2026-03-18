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
  "Return entities whose title or description exactly matches the query.
   Priority order: title matches (saga -> chapter -> character -> others),
   then description matches in the same order. Duplicates are removed."
  [entities-by-id query]
  (let [q (normalize query)]
    (if (str/blank? q)
      []
      (let [entities (vals entities-by-id)
            title-matches (->> entities
                               (filter #(= q (normalize (:title %))))
                               sort-entities)
            title-ids (set (map :id title-matches))
            desc-matches (->> entities
                              (remove #(contains? title-ids (:id %)))
                              (filter #(= q (normalize (:description %))))
                              sort-entities)]
        (vec (concat title-matches desc-matches))))))
