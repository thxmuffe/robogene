(ns webapp.components.editable-subtitle-display
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.edit-desc-with-actions :as edit-desc-with-actions]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            ["react-icons/fa6" :refer [FaCamera FaDownload FaEraser FaTrashCan FaWandMagic FaWandMagicSparkles]]))

(defn- focus-subtitle! [frame-id]
  (.requestAnimationFrame js/window
                          (fn []
                            (frame-nav/focus-subtitle! frame-id))))

(defn- scroll-subtitle-into-view! [frame-id]
  (.requestAnimationFrame
   js/window
   (fn []
     (some-> (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"] .subtitle-display"))
             (.scrollIntoView #js {:block "nearest"
                                   :inline "nearest"})))))

(defn- close-editing! [frame-id]
  (rf/dispatch [:set-frame-actions-open frame-id false])
  (focus-subtitle! frame-id))

(defn- keep-editing-on-blur? [frame-id]
  (let [active-el (.-activeElement js/document)
        frame-actions-selector (str ".frame-action-buttons[data-frame-id=\"" frame-id "\"]")]
    (or (interaction/closest? active-el frame-actions-selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(def max-subtitle-chars 500)

(defn- clamp-subtitle [text]
  (let [value (subs (or text "") 0 (min (count (or text "")) max-subtitle-chars))]
    value))

(defn- image-extension [image-url]
  (let [data-match (some->> image-url
                            (re-find #"^data:image/([^;]+);base64,")
                            second
                            str/lower-case)
        path-match (some->> image-url
                            (re-find #"\.([a-zA-Z0-9]+)(?:\?|$)")
                            second
                            str/lower-case)]
    (cond
      (= "jpeg" data-match) "jpg"
      (seq data-match) data-match
      (seq path-match) path-match
      :else "png")))

(defn- download-image! [frame-id frame-number image-url]
  (when (seq (or image-url ""))
    (let [link (.createElement js/document "a")]
      (set! (.-href link) image-url)
      (set! (.-download link) (str "frame-" (or frame-number frame-id) "." (image-extension image-url)))
      (set! (.-rel link) "noopener")
      (.appendChild (.-body js/document) link)
      (.click link)
      (.remove link))))

(defn- blur-subtitle-input! [frame-id]
  (some-> (.querySelector js/document (str ".subtitle-display-input[data-frame-id=\"" frame-id "\"]"))
          (.blur)))

(defn- unsaved-subtitle-changes? [current-input saved-description]
  (not= current-input saved-description))

(defn editable-subtitle-display [{:keys [frameId frameNumber description imageUrl]} editing?]
  (let [upload-submit-blur?* (r/atom false)
        confirm* (r/atom nil)
        upload-open?* (r/atom false)
        seen-cancel-token* (r/atom nil)]
    (fn [{:keys [frameId frameNumber description imageUrl]} editing?]
      (let [saved-description (clamp-subtitle description)
            current-input (clamp-subtitle @(rf/subscribe [:frame-draft frameId]))
            cancel-ui-token @(rf/subscribe [:cancel-ui-token])
            has-image? (not (str/blank? (or imageUrl "")))
            remove-image-item {:id :remove-image
                               :label "Remove image"
                               :confirm {:title "Remove image from this frame?"
                                         :text "The frame and its description will stay."
                                         :confirm-label "Remove image"
                                         :confirm-color "primary"}
                               :dispatch-event [:clear-frame-image frameId]}
            delete-frame-item {:id :delete-frame
                               :label "Delete frame"
                               :confirm {:title "Delete this frame?"
                                         :text "This cannot be undone."
                                         :confirm-label "Delete"
                                         :confirm-color "error"}
                               :dispatch-event [:delete-frame frameId]}
            selected-item @confirm*
            actions [{:id :generate
                      :label "Generate image"
                      :icon FaWandMagicSparkles
                      :color "indigo"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (blur-subtitle-input! frameId)
                                   (js/setTimeout
                                    (fn []
                                      (rf/dispatch [:generate-frame frameId current-input]))
                                    0))}
                     {:id :upload-image
                      :label "Upload or take picture"
                      :icon FaCamera
                      :color "blue"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! upload-open?* true))}
                     {:id :download-image
                      :label "Download image"
                      :icon FaDownload
                      :color "teal"
                      :disabled? (not has-image?)
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (download-image! frameId frameNumber imageUrl))}
                     {:id :generate-without-roster
                      :label "Generate without roster"
                      :icon FaWandMagic
                      :color "violet"
                      :disabled? (not has-image?)
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (blur-subtitle-input! frameId)
                                   (js/setTimeout
                                    (fn []
                                      (rf/dispatch [:generate-frame-without-roster frameId current-input]))
                                    0))}
                     {:id :delete-frame
                      :label "Delete frame"
                      :icon FaTrashCan
                      :color "red"
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! confirm* delete-frame-item))}
                     {:id :remove-image
                      :label "Remove image"
                      :icon FaEraser
                      :color "orange"
                      :disabled? (not has-image?)
                      :on-select (fn [e]
                                   (interaction/halt! e)
                                   (reset! confirm* remove-image-item))}]]
        (when (not= cancel-ui-token @seen-cancel-token*)
          (reset! seen-cancel-token* cancel-ui-token)
          (reset! confirm* nil)
          (reset! upload-open?* false))
        [edit-desc-with-actions/edit-desc-with-actions
         {:id frameId
          :editing? editing?
          :title-visible? false
          :title nil
          :desc saved-description
          :actions actions
          :class-name "subtitle-display-shell"
          :display-class-name "subtitle-display"
          :editing-class-name "subtitle-display-editing"
          :desc-class-name "subtitle-display-text"
          :desc-input-class-name "subtitle-display-input"
          :desc-input-placeholder "Describe this frame..."
          :desc-placeholder "Click subtitle to add description"
          :desc-max-chars max-subtitle-chars
          :show-actions-while-editing-only? true
          :actions-class-name "frame-action-buttons frame-action-buttons-row"
          :menu-title "Frame actions"
          :menu-aria-label "Frame actions"
          :on-open-edit #(do
                           (rf/dispatch [:set-frame-actions-open frameId true])
                           (scroll-subtitle-into-view! frameId))
          :on-close-edit #(close-editing! frameId)
          :on-focus #(do
                       (rf/dispatch [:set-active-frame frameId])
                       (scroll-subtitle-into-view! frameId))
          :on-desc-change #(rf/dispatch [:frame-direction-changed frameId %])
          :keep-editing-on-blur? (fn [_]
                                   (or @upload-submit-blur?*
                                       (keep-editing-on-blur? frameId)))
          :on-save (fn [{:keys [desc]}]
                     (when (unsaved-subtitle-changes? desc saved-description)
                       (rf/dispatch [:save-frame-description frameId desc])))
          :extra-content
          [:<>
           [confirm-dialog/confirm-dialog
            {:item selected-item
             :on-cancel #(reset! confirm* nil)
             :on-confirm (fn []
                           (when-let [event (:dispatch-event selected-item)]
                             (rf/dispatch event))
                           (reset! confirm* nil))}]
           [upload-dialog/upload-dialog
            {:open @upload-open?*
             :active-frame-id frameId
             :on-close (fn []
                         (reset! upload-open?* false)
                         (when @upload-submit-blur?*
                           (reset! upload-submit-blur?* false)
                           (.requestAnimationFrame js/window
                                                   (fn []
                                                     (blur-subtitle-input! frameId)))))
             :on-submit (fn [image-data-url]
                          (reset! upload-submit-blur?* true)
                          (rf/dispatch [:replace-frame-image frameId image-data-url]))}]]}]))))
