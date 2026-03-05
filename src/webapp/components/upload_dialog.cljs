(ns webapp.components.upload-dialog
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [webapp.components.popup-dialog :as popup-dialog]
            [webapp.shared.ui.interaction :as interaction]))

(defn file->data-url [file on-result]
  (let [reader (js/FileReader.)]
    (set! (.-onload reader) (fn [_] (on-result (.-result reader))))
    (.readAsDataURL reader file)))

(defn first-image-file [file-list]
  (some (fn [file]
          (when (str/starts-with? (or (.-type file) "") "image/")
            file))
        (array-seq file-list)))

(defn upload-dialog [{:keys [open on-close on-submit]}]
  (r/with-let [drag-over?* (r/atom false)
               image-data-url* (r/atom nil)
               error* (r/atom nil)
               input-id (str "upload-input-" (random-uuid))]
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
      [popup-dialog/popup-dialog {:open open
                                  :on-close do-cancel!}
       [:div.upload-dialog
        [:h3.upload-dialog-title "Replace With Own Photo"]
        [:p.upload-dialog-help "Paste from clipboard, drag and drop, or choose a file from your computer."]
        [:input.upload-file-input
         {:id input-id
          :type "file"
          :accept "image/*"
          :onChange on-file-change}]
        [:div.upload-dropzone
         {:className (str "upload-dropzone" (when @drag-over?* " is-drag-over"))
          :role "button"
          :tabIndex 0
          :onDragOver on-drag-over
          :onDragLeave on-drag-leave
          :onDrop on-drop
          :onPaste on-paste
          :onClick #(do
                      (interaction/stop! %)
                      (some-> (.getElementById js/document input-id) .click))}
         [:span.upload-dropzone-text "Drop image here, click to choose file, or paste (Cmd/Ctrl+V)."]]
        (when (seq (or @image-data-url* ""))
          [:div.upload-preview
           [:img {:src @image-data-url*
                  :alt "Upload preview"}]])
        (when (seq (or @error* ""))
          [:p.error-line @error*])
        [:div.upload-actions
         [:button.btn {:type "button"
                       :onClick do-cancel!}
          "Cancel"]
         [:button.btn.primary
          {:type "button"
           :onClick do-submit!
           :disabled (str/blank? (or @image-data-url* ""))}
          "Replace image"]]]])))
