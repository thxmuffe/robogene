(ns host.config
  (:require [clojure.string :as str]
            ["fs" :as fs]
            ["path" :as path]))

(defn unique-paths [paths]
  (reduce (fn [acc p]
            (if (some #(= % p) acc) acc (conj acc p)))
          []
          paths))

(defn ancestor-paths [start max-depth]
  (loop [current (.resolve path (or start "."))
         depth 0
         acc []]
    (let [next-acc (conj acc current)
          parent (.dirname path current)]
      (if (or (>= depth max-depth) (= parent current))
        next-acc
        (recur parent (inc depth) next-acc)))))

(defn host-config-paths []
  (let [cwd (or (some-> js/process .-cwd (.call js/process)) ".")
        env (some-> js/process .-env)
        script-root (or (some-> env (aget "AzureWebJobsScriptRoot")) "")
        pwd (or (some-> env (aget "PWD")) "")
        roots (filter seq [cwd js/__dirname script-root pwd])
        search-roots (mapcat #(ancestor-paths % 8) roots)
        candidates (mapcat (fn [root]
                             [(.resolve path root "host.json")
                              (.resolve path root "src" "host" "host.json")])
                           search-roots)]
    (unique-paths candidates)))

(defn read-json-file [p]
  (try
    (when (.existsSync fs p)
      (-> (.readFileSync fs p "utf8")
          (.parse js/JSON)
          (js->clj :keywordize-keys true)))
    (catch :default _
      nil)))

(defn read-host-config []
  (or (some read-json-file (host-config-paths)) {}))

(defn read-env-config []
  (let [env (.-env js/process)
        keys (js->clj (.keys js/Object env))]
    (reduce (fn [acc k]
              (let [v (aget env k)]
                (if (some? v)
                  (assoc acc k (str v))
                  acc)))
            {}
            keys)))

(defn key->segment [k]
  (cond
    (keyword? k) (name k)
    (string? k) k
    :else (str k)))

(defn flatten-host
  ([m] (flatten-host nil m))
  ([prefix m]
   (reduce-kv
    (fn [acc k v]
      (let [segment (key->segment k)
            path-key (if prefix (str prefix "." segment) segment)]
        (if (map? v)
          (merge acc
                 {path-key v}
                 (flatten-host path-key v))
          (assoc acc path-key v))))
    {}
    (or m {}))))

(defn required-keys-present! [settings keys]
  (let [missing (->> keys
                     (filter (fn [k] (str/blank? (or (get settings k) ""))))
                     vec)]
    (when (seq missing)
      (throw (js/Error.
              (str "Missing required settings: " (str/join ", " missing)))))))

(defn build-settings []
  (let [host (read-host-config)
        host-flat (flatten-host host)
        env (read-env-config)
        settings (merge host-flat env)]
    (required-keys-present! settings [])
    settings))

(defonce settings* (atom (build-settings)))

(defn settings []
  @settings*)

(defn setting
  ([k]
   (get @settings* k))
  ([k fallback]
   (or (setting k) fallback)))

(defn parse-int [raw fallback]
  (let [n (js/Number raw)]
    (if (and (js/Number.isFinite n) (> n 0))
      (js/Math.floor n)
      fallback)))

(defn parse-csv [raw]
  (->> (str/split (or raw "") #",")
       (map str/trim)
       (filter seq)
       vec))
