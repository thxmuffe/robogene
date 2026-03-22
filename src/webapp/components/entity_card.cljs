(ns webapp.components.entity-card
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.model :as model]
            ["@mantine/core" :refer [Box Card Text Stack]]))

(def ^:const thumb-max-width 320)
(def ^:const thumb-max-height 200)
(defonce thumbnail-cache* (atom {}))
(defonce thumbnail-pending* (atom {}))

(defn- scaled-size [width height]
  (let [safe-width (max 1 (or width 1))
        safe-height (max 1 (or height 1))
        ratio (min (/ thumb-max-width safe-width)
                   (/ thumb-max-height safe-height)
                   1)]
    {:width (max 1 (js/Math.round (* safe-width ratio)))
     :height (max 1 (js/Math.round (* safe-height ratio)))}))

(defn- canvas-thumbnail [img]
  (let [{:keys [width height]} (scaled-size (.-naturalWidth img) (.-naturalHeight img))
        canvas (.createElement js/document "canvas")
        ctx (.getContext canvas "2d")]
    (set! (.-width canvas) width)
    (set! (.-height canvas) height)
    (.drawImage ctx img 0 0 width height)
    (.toDataURL canvas "image/jpeg" 0.82)))

(defn- same-origin-or-inline-image? [src]
  (or (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*://" (or src "")))
      (.startsWith (or src "") "data:")
      (.startsWith (or src "") "blob:")
      (= (.-origin js/location)
         (.-origin (js/URL. src js/location.href)))))

(defn- start-thumbnail! [src on-ready]
  (if-not (same-origin-or-inline-image? src)
    (on-ready src)
    (if-let [cached (get @thumbnail-cache* src)]
    (on-ready cached)
    (if-let [listeners (get @thumbnail-pending* src)]
      (swap! thumbnail-pending* update src conj on-ready)
      (do
        (swap! thumbnail-pending* assoc src [on-ready])
        (let [img (js/Image.)]
          (set! (.-decoding img) "async")
          (set! (.-loading img) "eager")
          (set! (.-onload img)
                (fn []
                  (let [thumb (try
                                (canvas-thumbnail img)
                                (catch :default _
                                  src))
                        listeners (get @thumbnail-pending* src [])]
                    (swap! thumbnail-cache* assoc src thumb)
                    (swap! thumbnail-pending* dissoc src)
                    (doseq [listener listeners]
                      (listener thumb)))))
          (set! (.-onerror img)
                (fn []
                  (let [listeners (get @thumbnail-pending* src [])]
                    (swap! thumbnail-cache* assoc src src)
                    (swap! thumbnail-pending* dissoc src)
                    (doseq [listener listeners]
                      (listener src)))))
          (set! (.-src img) src)))))))

(defn entity-card [{:keys [entity clickable? on-click class-name]}]
  (let [entities @(rf/subscribe [:entities])
        entity-id (:id entity)
        preview-url (model/preview-image-url entities entity-id)
        title (model/primary-label entity)
        subtitle (model/secondary-label entity)
        clickable? (not (false? clickable?))]
    (r/with-let [thumb-url* (r/atom preview-url)
                 source-url* (r/atom nil)]
      (when (not= preview-url @source-url*)
        (reset! source-url* preview-url)
        (reset! thumb-url* preview-url)
        (when preview-url
          (start-thumbnail! preview-url #(reset! thumb-url* %))))
      [:> Card
       {:className (str "frame entity-card"
                        (when clickable? " frame-clickable")
                        (when (seq class-name) (str " " class-name)))
        :onClick on-click}
       [:> Stack {:gap "xs"}
        [:> Box {:className "media-shell entity-card-media"}
         (if @thumb-url*
           [:img {:className "entity-card-image"
                  :src @thumb-url*
                  :alt title
                  :loading "lazy"
                  :decoding "async"}]
           [:> Box {:className "placeholder-img entity-card-placeholder"}
            [:div.placeholder-text "No image"]])]
        [:> Box {:className "entity-card-copy"}
         [:> Text {:fw 700 :size "sm" :className "entity-card-title"} title]
         (when subtitle
           [:> Text {:size "sm" :c "dimmed" :className "entity-card-subtitle"} subtitle])]]])))
