(ns webapp.shared.fullscreen-layout)

(def default-image-ratio (/ 3 2))
(def min-display-ratio 0.5)

(defn- px [n]
  (str (js/Math.round (double n)) "px"))

(defn- image-ratio [frame-id]
  (let [img (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"] .frame-image"))
        width (or (some-> img .-naturalWidth) (some-> img .-clientWidth) 0)
        height (or (some-> img .-naturalHeight) (some-> img .-clientHeight) 0)]
    (if (and (pos? width) (pos? height))
      (/ width height)
      default-image-ratio)))

(defn compute-style [frame-id]
  (let [ratio (max min-display-ratio (image-ratio frame-id))
        max-width (.-innerWidth js/window)
        max-height (.-innerHeight js/window)
        width-from-height (* max-height ratio)
        [width height] (if (<= width-from-height max-width)
                         [width-from-height max-height]
                         [max-width (/ max-width ratio)])]
    {:--card-width (px width)
     :--frame-media-max-height (px height)}))
