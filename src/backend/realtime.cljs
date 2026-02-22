(ns backend.realtime
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            ["crypto" :as crypto]))

(def hub-name (or (.. js/process -env -ROBOGENE_SIGNALR_HUB) "robogene"))
(def connection-setting-name
  (or (.. js/process -env -ROBOGENE_SIGNALR_CONNECTION_SETTING)
      "AzureSignalRConnectionString"))

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

(defn create-jwt [audience access-key]
  (let [header (base64-url (.stringify js/JSON (clj->js {:alg "HS256" :typ "JWT"})))
        now (js/Math.floor (/ (.now js/Date) 1000))
        exp (+ now 60)
        payload (base64-url (.stringify js/JSON
                                        (clj->js {:aud audience
                                                  :iat now
                                                  :nbf now
                                                  :exp exp})))
        content (str header "." payload)
        signature (base64-url (-> (.createHmac crypto "sha256" (.from js/Buffer access-key "utf8"))
                                   (.update content)
                                   .digest))]
    (str content "." signature)))

(defn connection-config []
  (let [raw (gobj/get (.-env js/process) connection-setting-name)]
    (parse-connection-string raw)))

(defn create-client-connection-info []
  (when-let [{:keys [endpoint access-key]} (connection-config)]
    (let [url (str endpoint "/client/?hub=" (.encodeURIComponent js/globalThis hub-name))
          access-token (create-jwt url access-key)]
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
                     true
                     (-> (.text response)
                         (.then (fn [text]
                                  (throw (js/Error. (str "SignalR publish failed: HTTP "
                                                         (.-status response)
                                                         " "
                                                         text)))))))))))
    (js/Promise.resolve false)))
