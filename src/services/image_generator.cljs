(ns services.image-generator
  (:require [clojure.string :as str]
            [host.settings :as settings]
            [goog.object :as gobj]))

(defn configured-generators []
  (settings/image-generator-ids))

(defn generator-config [provider-id]
  (or (settings/image-generator-config provider-id)
      (throw (js/Error. (str "Unsupported or unconfigured image generator: " provider-id)))))

(defn require-startup-env! []
  (let [providers (configured-generators)]
    (when-not (seq providers)
      (throw (js/Error. "No configured image generators are available.")))
    (doseq [provider-id providers]
      (let [cfg (generator-config provider-id)
            api-key (some-> (or (get cfg "apiKey")
                                (get cfg :apiKey))
                            str
                            str/trim)]
        (case provider-id
          "openai" (when (str/blank? api-key)
                     (throw (js/Error. "IMAGE_GENERATORS entry for openai requires a resolved apiKey.")))
          "mock" nil
          (throw (js/Error. (str "Unsupported image generator in IMAGE_GENERATORS: " provider-id))))))))

(defn fetch-json [url options]
  (-> (js/fetch url options)
      (.then (fn [response]
               (-> (.json response)
                   (.then (fn [body]
                            {:ok (.-ok response)
                             :status (.-status response)
                             :body body})))))))

(defn openai-image-response->data-url [body]
  (let [data (gobj/get body "data")
        first-item (when (and data (> (.-length data) 0)) (aget data 0))
        b64 (when first-item (gobj/get first-item "b64_json"))]
    (if (not b64)
      (throw (js/Error. (str "Unexpected OpenAI response: " (.stringify js/JSON body))))
      (str "data:image/png;base64," b64))))

(defn append-reference-image! [form {:keys [bytes name]}]
  (let [blob (js/Blob. (clj->js [bytes]) #js {:type "image/png"})]
    (.append form "image[]" blob name)))

(defn setting-key->field-name [k]
  (if (keyword? k) (name k) (str k)))

(defn setting-value->field-value [v]
  (if (string? v) v (str v)))

(defn sanitize-openai-options [options]
  (let [opts (or options {})]
    (-> opts
        (dissoc :prompt "prompt")
        (dissoc :image "image"))))

(defn reference->meta [{:keys [bytes name]}]
  {:name (or name "<unnamed>")
   :bytes (or (some-> bytes .-length) 0)
   :present? (some? bytes)})

(defn request-debug-context [{:keys [prompt refs options generator]}]
  (str " generator=" (pr-str generator)
       " prompt=" (pr-str (or prompt ""))
       " options=" (pr-str (sanitize-openai-options options))
       " refs=" (pr-str (mapv reference->meta (or refs [])))))

(defn openai-image-request! [{:keys [key options prompt refs]}]
  (let [options (sanitize-openai-options options)]
    (if (seq refs)
      (let [form (js/FormData.)]
        (.append form "prompt" prompt)
        (doseq [[k v] options]
          (when (some? v)
            (.append form (setting-key->field-name k) (setting-value->field-value v))))
        (doseq [ref refs]
          (append-reference-image! form ref))
        (fetch-json "https://api.openai.com/v1/images/edits"
                    #js {:method "POST"
                         :headers #js {:Authorization (str "Bearer " key)}
                         :body form}))
      (do
        (fetch-json "https://api.openai.com/v1/images/generations"
                    #js {:method "POST"
                         :headers #js {:Authorization (str "Bearer " key)
                                       "Content-Type" "application/json"}
                         :body (.stringify js/JSON
                                           (clj->js (assoc options :prompt prompt)))})))))

(defn openai-response->result [{:keys [ok status body]} request]
  (if-not ok
    (throw (js/Error. (str "OpenAI error " status ": "
                           (.stringify js/JSON body)
                           " | request:"
                           (request-debug-context request))))
    (openai-image-response->data-url body)))

(defn openai-generate-image! [{:keys [generator prompt refs options]}]
  (let [cfg (generator-config generator)
        key (some-> (or (get cfg "apiKey") (get cfg :apiKey)) str str/trim)
        merged-options (merge (select-keys cfg [:model :quality :size]) (or options {}))]
    (if-not (seq key)
      (js/Promise.reject (js/Error. (str "Missing apiKey for image generator: " generator)))
      (-> (openai-image-request! {:key key
                                  :options merged-options
                                  :prompt prompt
                                  :refs refs})
          (.then (fn [response]
                   (openai-response->result response {:generator generator
                                                     :prompt prompt
                                                     :options merged-options
                                                     :refs refs})))))))

(defn mock-generator []
  (fn [_request]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn []
          (resolve (settings/mock-data-url)))
        (settings/mock-delay-ms))))))

(defn select-generator! [provider-id]
  (case (some-> provider-id str str/lower-case)
    "openai" openai-generate-image!
    "mock" (mock-generator)
    (throw (js/Error. (str "Unsupported image generator: " provider-id)))))

(defn generate-image! [{:keys [generator] :as request}]
  (when-not (seq (or generator ""))
    (throw (js/Error. "Image generator must be selected explicitly.")))
  ((select-generator! generator) request))
