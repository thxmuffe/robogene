(ns webapp.components.upload-dialog
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [webapp.components.popup-dialog :as popup-dialog]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon]]
            ["react-icons/fa6" :refer [FaCamera FaCheck FaClipboard FaFolderOpen FaImage FaXmark]]))

(defn file->data-url [file on-result]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader) (fn [_] (on-result (.-result reader))))
    (.readAsDataURL reader file)))

(defn first-image-file [file-list]
  (some (fn [file]
          (when (str/starts-with? (or (.-type file) "") "image/")
            file))
        (array-seq file-list)))

(defn clipboard-read-supported? []
  (fn? (some-> js/navigator .-clipboard .-read)))

(defn upload-dialog [{:keys [open on-close on-submit]}]
  (r/with-let [drag-over?* (r/atom false)
               image-data-url* (r/atom nil)
               error* (r/atom nil)
               file-input-id (str "upload-input-" (random-uuid))
               camera-input-id (str "camera-input-" (random-uuid))
               surface-el* (r/atom nil)
               was-open?* (r/atom false)
               dialog-pos* (r/atom {:x 0 :y 0})
               drag-state* (r/atom nil)]
    (let [set-file! (fn [file]
                      (if file
                        (do
                          (reset! error* nil)
                          (file->data-url file #(reset! image-data-url* %)))
                        (reset! error* "Please provide an image file.")))
          on-file-change (fn [e]
                           (interaction/stop! e)
                           (set-file! (first-image-file (.. e -target -files))))
          on-drop (fn [e]
                    (interaction/halt! e)
                    (reset! drag-over?* false)
                    (set-file! (first-image-file (.. e -dataTransfer -files))))
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
                         (set-file! (.getAsFile image-item)))))
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
                                                                  (set-file! blob))))
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
          do-submit! (fn [e]
                       (interaction/halt! e)
                       (if (seq (or @image-data-url* ""))
                         (do
                           (on-submit @image-data-url*)
                           (reset! image-data-url* nil)
                           (reset! error* nil)
                           (on-close))
                         (reset! error* "No image selected yet.")))
          do-cancel! (fn [e]
                       (interaction/halt! e)
                       (reset! image-data-url* nil)
                       (reset! error* nil)
                       (on-close))]
      (when (and open (not @was-open?*))
        (reset! dialog-pos* {:x 0 :y 0})
        (js/requestAnimationFrame
         (fn []
           (some-> @surface-el* .focus)
           (js/setTimeout
            (fn []
              (some-> @surface-el* .focus))
            60))))
      (reset! was-open?* open)
      [popup-dialog/popup-dialog
       {:open open
        :on-close do-cancel!
        :size "auto"
        :padding 0
        :styles #js {:content #js {:background "transparent"
                                   :boxShadow "none"}
                     :body #js {:padding 0}}}
       [:div.upload-dialog
        {:className (str "upload-dialog" (when (seq (or @image-data-url* "")) " has-image"))
         :style {:transform (str "translate(" (:x @dialog-pos*) "px, " (:y @dialog-pos*) "px)")}
         :onPointerDown start-drag!
         :onPointerMove move-drag!
         :onPointerUp end-drag!
         :onPointerCancel end-drag!
         :onPaste on-paste}
        [:div.upload-dialog-title-bar
         [:h3.upload-dialog-title "Upload photo"]]
        [:input.upload-file-input
         {:id file-input-id
          :type "file"
          :accept "image/*"
          :onChange on-file-change}]
        [:input.upload-file-input
         {:id camera-input-id
          :type "file"
          :accept "image/*"
          :capture "environment"
          :onChange on-file-change}]
        [:div.upload-image-surface
         {:className (str "upload-image-surface" (when @drag-over?* " is-drag-over"))
          :role "button"
          :tabIndex 0
          :data-autofocus true
          :ref #(reset! surface-el* %)
          :style (when (seq (or @image-data-url* ""))
                   {:backgroundImage (str "url(" @image-data-url* ")")})
          :onDragOver on-drag-over
          :onDragLeave on-drag-leave
          :onDrop on-drop
          :onPaste on-paste
          :onClick #(open-file-picker! % file-input-id)
          :onKeyDown (fn [e]
                       (when (or (= "Enter" (.-key e))
                                 (= " " (.-key e)))
                         (open-file-picker! e file-input-id)))}
         (when-not (seq (or @image-data-url* ""))
           [:div.upload-image-placeholder
            [:> FaImage]
            [:span "Paste or drop image"]])]
        (when (seq (or @error* ""))
          [:p.error-line @error*])
        [:div.upload-toolbar
         [:button.upload-tool-btn
          {:type "button"
           :title "Paste image"
           :aria-label "Paste image"
           :onClick read-clipboard-image!}
          [:> FaClipboard]
          [:span "Paste"]]
         [:button.upload-tool-btn
          {:type "button"
           :title "Choose file"
           :aria-label "Choose file"
           :onClick #(open-file-picker! % file-input-id)}
          [:> FaFolderOpen]
          [:span "File"]]
         [:button.upload-tool-btn
          {:type "button"
           :title "Take photo"
           :aria-label "Take photo"
           :onClick #(open-file-picker! % camera-input-id)}
          [:> FaCamera]
          [:span "Camera"]]
         [:> ActionIcon
          {:className "upload-dialog-action"
           :aria-label "Submit"
           :title "Submit"
           :variant "filled"
           :radius "xl"
           :disabled (str/blank? (or @image-data-url* ""))
           :onClick do-submit!}
          [:> FaCheck]]
         [:> ActionIcon
         {:className "upload-dialog-action"
           :aria-label "Cancel"
           :title "Cancel"
           :variant "subtle"
           :radius "xl"
           :onClick do-cancel!}
          [:> FaXmark]]]]])))
