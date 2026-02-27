(ns services.image-generator
  (:require [clojure.string :as str]
            [host.settings :as settings]
            [goog.object :as gobj]))

(defn require-startup-env! []
  (let [generator (or (settings/image-generator) "openai")
        key (settings/image-generator-key)]
    (case generator
      "openai" (when (str/blank? key)
                 (throw (js/Error. "Missing ROBOGENE_IMAGE_GENERATOR_KEY in Function App settings.")))
      "mock" nil
      (throw (js/Error. (str "Unsupported ROBOGENE_IMAGE_GENERATOR: " generator))))))

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

(defn log-openai-request! [mode options refs]
  (let [opts (sanitize-openai-options options)
        model (or (get opts "model") (get opts :model) "-")
        size (or (get opts "size") (get opts :size) "-")
        quality (or (get opts "quality") (get opts :quality) "-")
        refs-count (count (or refs []))]
    (js/console.info
     (str "[robogene] openai image request"
          " mode=" mode
          " refs=" refs-count
          " model=" model
          " size=" size
          " quality=" quality))))

(defn openai-image-request! [{:keys [key options prompt refs]}]
  (let [options (sanitize-openai-options options)]
  (if (seq refs)
    (let [form (js/FormData.)]
      (log-openai-request! "edits" options refs)
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
      (log-openai-request! "generations" options refs)
      (fetch-json "https://api.openai.com/v1/images/generations"
                #js {:method "POST"
                     :headers #js {:Authorization (str "Bearer " key)
                                   "Content-Type" "application/json"}
                     :body (.stringify js/JSON
                                       (clj->js (assoc options :prompt prompt)))})))))

(defn invalid-second-reference? [status body]
  (let [err (gobj/get body "error")
        code (some-> err (gobj/get "code"))
        message (some-> err (gobj/get "message") str)]
    (and (= status 400)
         (= code "invalid_image_file")
         (str/includes? (or message "") "image 2"))))

(defn invalid-reference-image? [status body]
  (let [err (gobj/get body "error")
        code (some-> err (gobj/get "code"))]
    (and (= status 400)
         (= code "invalid_image_file"))))

(defn openai-response->result [{:keys [ok status body]}]
  (if-not ok
    (throw (js/Error. (str "OpenAI error " status ": " (.stringify js/JSON body))))
    (openai-image-response->data-url body)))

(defn openai-generate-image! [{:keys [prompt refs options]}]
  (let [key (settings/image-generator-key)]
    (if-not (seq key)
      (js/Promise.reject (js/Error. "Missing ROBOGENE_IMAGE_GENERATOR_KEY in Function App settings."))
      (letfn [(request-with-refs! [attempt-refs]
                (-> (openai-image-request! {:key key
                                            :options options
                                            :prompt prompt
                                            :refs attempt-refs})
                    (.then (fn [{:keys [ok status body]}]
                             (cond
                               (and (not ok)
                                    (invalid-second-reference? status body)
                                    (> (count attempt-refs) 1))
                               (do
                                 (js/console.warn
                                  "[robogene] OpenAI rejected secondary reference image; retrying with single reference image.")
                                 (request-with-refs! (vec (take 1 attempt-refs))))

                               (and (not ok)
                                    (invalid-reference-image? status body)
                                    (seq attempt-refs))
                               (do
                                 (js/console.warn
                                  "[robogene] OpenAI rejected reference image; retrying without reference images.")
                                 (request-with-refs! []))

                               :else
                               (openai-response->result {:ok ok :status status :body body}))))))]
        (request-with-refs! refs)))))

(defn mock-generator []
  (fn [_request]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn []
          (resolve (settings/mock-data-url)))
        (settings/mock-delay-ms))))))

(defonce generator!* (atom nil))

(defn select-image-generator! []
  (let [selected (or (settings/image-generator) "openai")]
    (reset! generator!*
            (case selected
              "openai" openai-generate-image!
              "mock" (mock-generator)
              (throw (js/Error. (str "Unsupported ROBOGENE_IMAGE_GENERATOR: " selected)))))))

(defn ensure-image-generator! []
  (when-not (fn? @generator!*)
    (select-image-generator!)))

(defn set-image-generator! [f]
  (reset! generator!* f))

(defn generate-image! [request]
  (ensure-image-generator!)
  ((or @generator!* openai-generate-image!) request))
