(ns webapp.components.upload-dialog
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.components.popup-dialog :as popup-dialog]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon]]
            ["react-icons/fa6" :refer [FaCamera FaFolderOpen FaImage FaPaste FaVideo FaXmark]]))

(defn stop-media-stream! [stream]
  (when-let [tracks (some-> stream .getTracks array-seq)]
    (doseq [track tracks]
      (.stop track))))

(defn file->data-url [file on-result]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader) (fn [_] (on-result (.-result reader))))
    (.readAsDataURL reader file)))

(defn file->data-url-promise [file]
  (js/Promise.
   (fn [resolve reject]
     (let [reader (js/FileReader.)]
       (set! (.-onload reader) (fn [_] (resolve (.-result reader))))
       (set! (.-onerror reader) (fn [_] (reject (js/Error. "Image read failed."))))
       (.readAsDataURL reader file)))))

(defn image-files [file-list]
  (->> (array-seq file-list)
       (filter (fn [file]
                 (str/starts-with? (or (.-type file) "") "image/")))
       vec))

(defn clipboard-read-supported? []
  (fn? (some-> js/navigator .-clipboard .-read)))

(defn webcam-supported? []
  (fn? (some-> js/navigator .-mediaDevices .-getUserMedia)))

(defn upload-dialog [{:keys [open on-close on-submit on-submit-many active-frame-id multiple? title]}]
  (r/with-let [drag-over?* (r/atom false)
               error* (r/atom nil)
               file-input-id (str "upload-input-" (random-uuid))
               camera-input-id (str "camera-input-" (random-uuid))
               surface-el* (r/atom nil)
               video-el* (r/atom nil)
               stream* (r/atom nil)
               webcam-open?* (r/atom false)
               was-open?* (r/atom false)
               dialog-pos* (r/atom {:x 0 :y 0})
               drag-state* (r/atom nil)
               spotlight-style* (r/atom nil)]
    (let [stop-webcam! (fn []
                         (when-let [stream @stream*]
                           (stop-media-stream! stream)
                           (reset! stream* nil))
                         (when-let [video @video-el*]
                           (set! (.-srcObject video) nil))
                         (reset! webcam-open?* false))
          sync-spotlight! (fn []
                            (if-not (seq (or active-frame-id ""))
                              (reset! spotlight-style* nil)
                              (if-let [frame-el (.querySelector js/document (str ".frame[data-frame-id=\"" active-frame-id "\"]"))]
                                (let [rect (.getBoundingClientRect frame-el)
                                      x (+ (.-left rect) (/ (.-width rect) 2))
                                      y (+ (.-top rect) (/ (.-height rect) 2))
                                      radius (max 44 (min 88 (* 0.18 (max (.-width rect) (.-height rect)))))]
                                  (reset! spotlight-style*
                                          {:--spotlight-x (str x "px")
                                           :--spotlight-y (str y "px")
                                           :--spotlight-radius (str radius "px")}))
                                (reset! spotlight-style* nil))))
          commit-image! (fn [image-data-url]
                          (stop-webcam!)
                          (reset! error* nil)
                          (on-submit image-data-url)
                          (on-close))
          commit-images! (fn [image-data-urls]
                           (stop-webcam!)
                           (reset! error* nil)
                           (if multiple?
                             (when on-submit-many
                               (on-submit-many image-data-urls))
                             (when-let [first-image (first image-data-urls)]
                               (on-submit first-image)))
                           (on-close))
          set-files! (fn [file-list]
                       (let [files (image-files file-list)]
                         (if (seq files)
                           (do
                             (stop-webcam!)
                             (reset! error* nil)
                             (if multiple?
                               (-> (js/Promise.all (clj->js (map file->data-url-promise files)))
                                   (.then (fn [results]
                                            (commit-images! (vec (array-seq results)))))
                                   (.catch (fn [err]
                                             (reset! error* (or (some-> err .-message)
                                                                "Image read failed.")))))
                               (file->data-url (first files)
                                               (fn [result]
                                                 (commit-images! [result])))))
                           (reset! error* (if multiple?
                                           "Please provide image files."
                                           "Please provide an image file.")))))
          start-webcam! (fn [e]
                          (interaction/halt! e)
                          (if-not (webcam-supported?)
                            (reset! error* "Camera preview is not supported in this browser.")
                            (-> (.getUserMedia (.-mediaDevices js/navigator)
                                               #js {:video #js {:facingMode "user"}
                                                    :audio false})
                                (.then (fn [stream]
                                         (stop-webcam!)
                                         (reset! error* nil)
                                         (reset! stream* stream)
                                         (reset! webcam-open?* true)
                                         (js/requestAnimationFrame
                                          (fn []
                                            (when-let [video @video-el*]
                                              (set! (.-srcObject video) stream)
                                              (some-> (.play video)
                                                      (.catch (fn [_] nil))))))))
                                (.catch (fn [_]
                                          (reset! error* "Unable to access camera."))))))
          capture-webcam! (fn [e]
                            (interaction/halt! e)
                            (if-let [video @video-el*]
                              (let [width (max 1 (or (.-videoWidth video) 0))
                                    height (max 1 (or (.-videoHeight video) 0))]
                                (if (or (zero? width) (zero? height))
                                  (reset! error* "Camera is not ready yet.")
                                  (let [canvas (.createElement js/document "canvas")
                                        ctx (.getContext canvas "2d")]
                                    (set! (.-width canvas) width)
                                    (set! (.-height canvas) height)
                                    (.drawImage ctx video 0 0 width height)
                                    (commit-images! [(.toDataURL canvas "image/png")]))))
                              (reset! error* "Camera is not ready yet.")))
          on-file-change (fn [e]
                           (interaction/stop! e)
                           (set-files! (.. e -target -files)))
          on-drop (fn [e]
                    (interaction/halt! e)
                    (reset! drag-over?* false)
                    (set-files! (.. e -dataTransfer -files)))
          on-drag-over (fn [e]
                         (interaction/halt! e)
                         (reset! drag-over?* true))
          on-drag-leave (fn [e]
                          (interaction/halt! e)
                          (reset! drag-over?* false))
          on-paste (fn [e]
                     (let [items (array-seq (.. e -clipboardData -items))
                           image-item (some (fn [item]
                                              (when (str/starts-with? (or (.-type item) "") "image/")
                                                item))
                                            items)]
                       (when image-item
                         (interaction/halt! e)
                         (set-files! #js [(.getAsFile image-item)]))))
          read-clipboard-image! (fn [e]
                                  (interaction/halt! e)
                                  (if-not (clipboard-read-supported?)
                                    (some-> @surface-el* .focus)
                                    (-> (.read (.-clipboard js/navigator))
                                        (.then (fn [items]
                                                 (let [clipboard-items (array-seq items)
                                                       image-entry (some (fn [item]
                                                                           (let [types (array-seq (.-types item))]
                                                                             (some (fn [type]
                                                                                     (when (str/starts-with? (or type "") "image/")
                                                                                       #js {:item item :type type}))
                                                                                   types)))
                                                                         clipboard-items)]
                                                   (if image-entry
                                                     (-> (.getType (.-item image-entry) (.-type image-entry))
                                                         (.then (fn [blob]
                                                                  (set-files! #js [blob]))))
                                                     (do
                                                       (reset! error* "Clipboard does not contain an image.")
                                                       (some-> @surface-el* .focus))))))
                                        (.catch (fn [_]
                                                  (some-> @surface-el* .focus))))))
          open-file-picker! (fn [e input-id]
                              (interaction/halt! e)
                              (some-> (.getElementById js/document input-id) .click))
          start-drag! (fn [e]
                        (when-not (interaction/interactive-target? (.-target e))
                          (let [pos @dialog-pos*]
                            (reset! drag-state* {:pointer-id (.-pointerId e)
                                                 :origin-x (.-clientX e)
                                                 :origin-y (.-clientY e)
                                                 :start-x (:x pos)
                                                 :start-y (:y pos)})
                            (some-> (.-currentTarget e) (.setPointerCapture (.-pointerId e)))
                            (interaction/prevent! e))))
          move-drag! (fn [e]
                       (when-let [{:keys [pointer-id origin-x origin-y start-x start-y]} @drag-state*]
                         (when (= pointer-id (.-pointerId e))
                           (reset! dialog-pos* {:x (+ start-x (- (.-clientX e) origin-x))
                                                :y (+ start-y (- (.-clientY e) origin-y))}))))
          end-drag! (fn [e]
                      (when-let [{:keys [pointer-id]} @drag-state*]
                        (when (= pointer-id (.-pointerId e))
                          (reset! drag-state* nil)
                          (some-> (.-currentTarget e) (.releasePointerCapture (.-pointerId e))))))
          do-cancel! (fn [e]
                       (interaction/halt! e)
                       (stop-webcam!)
                       (reset! error* nil)
                       (on-close))
          toolbar-actions (cond-> [{:id :paste-image
                                   :label "Paste image"
                                   :icon FaPaste
                                   :color "grape"
                                   :on-select read-clipboard-image!}
                                  {:id :choose-file
                                   :label (if multiple? "Choose files" "Choose file")
                                   :icon FaFolderOpen
                                   :color "blue"
                                   :on-select #(open-file-picker! % file-input-id)}
                                  {:id :take-photo
                                   :label "Take photo"
                                   :icon FaCamera
                                   :color "cyan"
                                   :on-select #(open-file-picker! % camera-input-id)}]
                           (and (not multiple?)
                                (webcam-supported?))
                           (conj {:id :webcam
                                  :label (if @webcam-open?* "Capture photo" "Use webcam")
                                  :icon FaVideo
                                  :color "teal"
                                  :on-select (if @webcam-open?*
                                               capture-webcam!
                                               start-webcam!)}))]
      (when (and open (not @was-open?*))
        (reset! dialog-pos* {:x 0 :y 0})
        (sync-spotlight!)
        (js/requestAnimationFrame
         (fn []
           (sync-spotlight!)
           (some-> @surface-el* .focus)
           (js/setTimeout
            (fn []
              (sync-spotlight!)
              (some-> @surface-el* .focus))
            60))))
      (reset! was-open?* open)
      [popup-dialog/popup-dialog
       {:open open
        :on-close do-cancel!
        :overlay-props {:className (when @spotlight-style* "popup-dialog-overlay-spotlight")
                        :style @spotlight-style*}
        :size "auto"
        :padding 0
       :styles #js {:content #js {:background "transparent"
                                  :boxShadow "none"}
                     :body #js {:padding 0}}}
       [:div.upload-dialog
        {:className "upload-dialog"
         :style {:transform (str "translate(" (:x @dialog-pos*) "px, " (:y @dialog-pos*) "px)")}
         :onPointerDown start-drag!
         :onPointerMove move-drag!
         :onPointerUp end-drag!
         :onPointerCancel end-drag!
         :onPaste on-paste}
        [:div.upload-dialog-title-bar
         [:h3.upload-dialog-title (or title (if multiple? "Upload images" "Upload photo"))]
         [:> ActionIcon
          {:className "upload-dialog-close"
           :aria-label "Close upload dialog"
           :title "Close upload dialog"
           :variant "subtle"
           :radius "xl"
           :onClick do-cancel!}
          [:> FaXmark]]]
        [:input.upload-file-input
         {:id file-input-id
          :type "file"
          :accept "image/*"
          :multiple (true? multiple?)
          :onChange on-file-change}]
        [:input.upload-file-input
         {:id camera-input-id
          :type "file"
          :accept "image/*"
          :capture "environment"
          :onChange on-file-change}]
        [:div.upload-image-surface
         {:className (str "upload-image-surface"
                          " is-empty"
                          (when @drag-over?* " is-drag-over")
                          (when @webcam-open?* " is-webcam"))
          :role "button"
          :tabIndex 0
          :data-autofocus true
          :ref #(reset! surface-el* %)
          :onDragOver on-drag-over
          :onDragLeave on-drag-leave
          :onDrop on-drop
          :onPaste on-paste
          :onClick #(open-file-picker! % file-input-id)
          :onKeyDown (fn [e]
                       (when (or (= "Enter" (.-key e))
                                 (= " " (.-key e)))
                         (open-file-picker! e file-input-id)))}
         (cond
           @webcam-open?*
           [:div.upload-image-placeholder
            [:> FaVideo]
            [:span "Press webcam again to capture"]]

           :else
           [:div.upload-image-placeholder
            [:> FaImage]
            [:span (if multiple?
                     "Drop, paste, or choose images"
                     "Drop, paste, or choose image")]])
         [:video.upload-webcam-buffer
          {:ref #(reset! video-el* %)
           :autoPlay true
           :playsInline true
           :muted true}]]
        (when (seq (or @error* ""))
          [:p.error-line @error*])
        [waterfall-row/waterfall-row
         {:class-name "upload-toolbar"
          :actions toolbar-actions
          :menu-title "Upload actions"
          :menu-aria-label "Upload actions"}]]])
    (finally
      (stop-media-stream! @stream*))))
