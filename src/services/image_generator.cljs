(ns services.image-generator
  (:require [clojure.string :as str]
            [goog.object :as gobj]))

(defn image-generator []
  (some-> (.. js/process -env -ROBOGENE_IMAGE_GENERATOR) str str/trim str/lower-case not-empty))

(defn image-generator-key []
  (some-> (.. js/process -env -ROBOGENE_IMAGE_GENERATOR_KEY) str str/trim))

(def default-mock-data-url
  "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO7+Jc8AAAAASUVORK5CYII=")

(defn mock-data-url []
  (or (some-> (.. js/process -env -ROBOGENE_IMAGE_GENERATOR_MOCK_DATA_URL) str str/trim not-empty)
      default-mock-data-url))

(defn mock-delay-ms []
  (let [raw (some-> (.. js/process -env -ROBOGENE_IMAGE_GENERATOR_MOCK_DELAY_MS) str str/trim)
        n (js/Number raw)]
    (if (and (seq (or raw "")) (js/Number.isFinite n) (>= n 0))
      n
      0)))

(defn require-startup-env! []
  (let [generator (or (image-generator) "openai")
        key (image-generator-key)]
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

(defn openai-image-request! [{:keys [key model quality size prompt refs]}]
  (if (seq refs)
    (let [form (js/FormData.)]
      (.append form "model" model)
      (.append form "prompt" prompt)
      (.append form "quality" quality)
      (.append form "size" size)
      (doseq [ref refs]
        (append-reference-image! form ref))
      (fetch-json "https://api.openai.com/v1/images/edits"
                  #js {:method "POST"
                       :headers #js {:Authorization (str "Bearer " key)}
                       :body form}))
    (fetch-json "https://api.openai.com/v1/images/generations"
                #js {:method "POST"
                     :headers #js {:Authorization (str "Bearer " key)
                                   "Content-Type" "application/json"}
                     :body (.stringify js/JSON
                                       (clj->js {:model model
                                                 :prompt prompt
                                                 :quality quality
                                                 :size size}))})))

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

(defn openai-generate-image! [{:keys [key prompt refs model quality size]}]
  (if-not (seq key)
    (js/Promise.reject (js/Error. "Missing ROBOGENE_IMAGE_GENERATOR_KEY in Function App settings."))
    (letfn [(request-with-refs! [attempt-refs]
              (-> (openai-image-request! {:key key
                                          :model model
                                          :quality quality
                                          :size size
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
      (request-with-refs! refs))))

(defn openai-generator []
  (fn [request]
    (openai-generate-image! (assoc request :key (image-generator-key)))))

(defn mock-generator []
  (fn [_request]
    (js/Promise.
     (fn [resolve _reject]
       (js/setTimeout
        (fn []
          (resolve (mock-data-url)))
        (mock-delay-ms))))))

(defonce generator!* (atom nil))

(defn select-image-generator! []
  (let [selected (or (image-generator) "openai")]
    (reset! generator!*
            (case selected
              "openai" (openai-generator)
              "mock" (mock-generator)
              (throw (js/Error. (str "Unsupported ROBOGENE_IMAGE_GENERATOR: " selected)))))))

(defn ensure-image-generator! []
  (when-not (fn? @generator!*)
    (select-image-generator!)))

(defn set-image-generator! [f]
  (reset! generator!* f))

(defn generate-image! [request]
  (ensure-image-generator!)
  ((or @generator!* (openai-generator)) request))
