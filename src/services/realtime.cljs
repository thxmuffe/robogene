(ns services.realtime
  (:require [clojure.string :as str]
            [host.settings :as settings]
            [goog.object :as gobj]
            ["crypto" :as crypto]))

(def hub-name (settings/signalr-hub-name))
(def connection-setting-name (settings/signalr-connection-setting-name))

(defn parse-int [value fallback]
  (let [n (js/Number value)]
    (if (and (js/Number.isFinite n) (> n 0))
      (js/Math.floor n)
      fallback)))

(defn client-token-ttl-seconds []
  (settings/signalr-client-token-ttl-seconds))

(defn parse-connection-string [raw]
  (when (string? raw)
    (let [entries (->> (str/split raw #";")
                       (map str/trim)
                       (filter seq))
          kv (reduce (fn [acc entry]
                       (let [eq (.indexOf entry "=")]
                         (if (<= eq 0)
                           acc
                           (let [k (str/trim (.slice entry 0 eq))
                                 v (str/trim (.slice entry (inc eq)))]
                             (assoc acc k v)))))
                     {}
                     entries)
          endpoint (get kv "Endpoint")
          access-key (get kv "AccessKey")]
      (when (and (seq endpoint) (seq access-key))
        {:endpoint (str/replace endpoint #"/+$" "")
         :access-key access-key}))))

(defn base64-url [input]
  (-> (if (.isBuffer js/Buffer input)
        input
        (.from js/Buffer (str input) "utf8"))
      (.toString "base64")
      (str/replace #"\+" "-")
      (str/replace #"/" "_")
      (str/replace #"=+$" "")))

(defn create-jwt
  ([audience access-key]
   (create-jwt audience access-key 60))
  ([audience access-key ttl-seconds]
  (let [header (base64-url (.stringify js/JSON (clj->js {:alg "HS256" :typ "JWT"})))
        now (js/Math.floor (/ (.now js/Date) 1000))
        exp (+ now (parse-int ttl-seconds 60))
        payload (base64-url (.stringify js/JSON
                                        (clj->js {:aud audience
                                                  :iat now
                                                  :nbf now
                                                  :exp exp})))
        content (str header "." payload)
        signature (base64-url (-> (.createHmac crypto "sha256" (.from js/Buffer access-key "utf8"))
                                   (.update content)
                                   .digest))]
    (str content "." signature))))

(defn connection-config []
  (parse-connection-string (settings/signalr-connection-string)))

(defn create-client-connection-info []
  (when-let [{:keys [endpoint access-key]} (connection-config)]
    (let [url (str endpoint "/client/?hub=" (.encodeURIComponent js/globalThis hub-name))
          access-token (create-jwt url access-key (client-token-ttl-seconds))]
      {:url url
       :accessToken access-token})))

(defn publish-state-update! [data]
  (if-let [{:keys [endpoint access-key]} (connection-config)]
    (let [audience (str endpoint "/api/v1/hubs/" hub-name)
          token (create-jwt audience access-key)
          body (.stringify js/JSON (clj->js {:target "stateChanged"
                                             :arguments [data]}))]
      (-> (js/fetch audience
                    #js {:method "POST"
                         :headers #js {"Authorization" (str "Bearer " token)
                                       "Content-Type" "application/json"}
                         :body body})
          (.then (fn [response]
                   (if (.-ok response)
                     (do
                       (js/console.info
                        (str "[robogene] SignalR publish ok"
                             " status=" (.-status response)
                             " reason=" (or (gobj/get data "reason") "")))
                       true)
                     (-> (.text response)
                         (.then (fn [text]
                                  (throw (js/Error. (str "SignalR publish failed: HTTP "
                                                         (.-status response)
                                                         " "
                                                         text)))))))))
          (.catch (fn [err]
                    (js/console.warn
                     (str "[robogene] SignalR publish exception: "
                          (or (.-message err) err)))
                    (throw err)))))
    (js/Promise.resolve false)))
