(ns webapp.components.frame
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.db-text :as db-text]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.components.confirm-dialog :as confirm-dialog]
            [webapp.components.upload-dialog :as upload-dialog]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            ["react-icons/fa6" :refer [FaCamera FaDownload FaEraser FaTrashCan FaWandMagic FaWandMagicSparkles]]
            ["@mantine/core" :refer [Box Card Image]]))

(defn frame-owner-page [owner-type]
  (if (= "character" (str owner-type))
    :roster
    :saga))

(defn on-frame-click [chapter-id frame-id owner-type]
  (fn [e]
    (when-not (interaction/interactive-child-event? e)
      (controls/navigate-frame! chapter-id frame-id (frame-owner-page owner-type)))))

(defn on-media-nav-click [delta]
  (fn [e]
    (interaction/halt! e)
    (rf/dispatch [:navigate-relative-frame delta])))

(defn frame-image [{:keys [imageUrl frameId]} image-fit]
  [:> Image
   {:key (str frameId "|" (or imageUrl ""))
    :src (or imageUrl "")
    :alt (str "Frame " frameId)
    :fit image-fit
    :onLoad #(rf/dispatch [:frame-image-loaded frameId imageUrl])
    :onError #(rf/dispatch [:frame-image-error frameId imageUrl])}])

(defn frame-placeholder [{:keys [status]}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Edit subtitle and generate")]
    [:> Box {:className "placeholder-img"}
     (when (or (= status "queued") (= status "processing"))
      [:div.spinner])
     [:div.placeholder-text label]]))

(defn frame-status-note [{:keys [status image-loading? image-error?]}]
  (let [label (cond
                (= status "processing") "Generating..."
                (= status "queued") "Queued..."
                image-loading? "Loading image..."
                (or image-error? (= status "failed")) "Image failed to load"
                :else nil)]
    (when label
      [:div.frame-status-note
       (when (or (= status "processing") (= status "queued") image-loading?)
         [:div.spinner])
       [:div.placeholder-text label]])))

(def max-subtitle-chars 500)

(defn- clamp-subtitle [text]
  (let [value (or text "")]
    (subs value 0 (min (count value) max-subtitle-chars))))

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
  (some-> (.querySelector js/document (str "[data-db-text-id=\"" frame-id "-subtitle\"] .subtitle-display-input textarea"))
          (.blur)))

(defn- keep-frame-editing-open? [frame-id]
  (let [active-el (.-activeElement js/document)
        selector (str ".frame[data-frame-id=\"" frame-id "\"]")]
    (or (interaction/closest? active-el selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(defn frame
  ([frame]
   [frame frame {:clickable? true}])
  ([frame {:keys [clickable? active? media-nav? image-fit]
           :or {clickable? true active? false media-nav? false image-fit "contain"}}]
   (r/with-let [was-editable* (r/atom false)
                upload-submit-blur?* (r/atom false)
                confirm* (r/atom nil)
                upload-open?* (r/atom false)
                seen-cancel-token* (r/atom nil)]
     (let [has-image? (not (str/blank? (or (:imageUrl frame) "")))
           busy? (or (= "queued" (:status frame)) (= "processing" (:status frame)))
           image-ui @(rf/subscribe [:frame-image-ui (:frameId frame)])
           image-loading? (= :loading image-ui)
           image-error? (= :error image-ui)
           editable? @(rf/subscribe [:frame-edit-open? (:frameId frame)])
           current-input (clamp-subtitle @(rf/subscribe [:frame-draft (:frameId frame)]))
           cancel-ui-token @(rf/subscribe [:cancel-ui-token])
           frame* (assoc frame :actionsOpen editable?)
           remove-image-item {:id :remove-image
                              :label "Remove image"
                              :confirm {:title "Remove image from this frame?"
                                        :text "The frame and its description will stay."
                                        :confirm-label "Remove image"
                                        :confirm-color "primary"}
                              :dispatch-event [:clear-frame-image (:frameId frame)]}
           delete-frame-item {:id :delete-frame
                              :label "Delete frame"
                              :confirm {:title "Delete this frame?"
                                        :text "This cannot be undone."
                                        :confirm-label "Delete"
                                        :confirm-color "error"}
                              :dispatch-event [:delete-frame (:frameId frame)]}
           selected-item @confirm*
           actions [{:id :generate
                     :label "Generate image"
                     :icon FaWandMagicSparkles
                     :color "indigo"
                     :on-select (fn [e]
                                  (interaction/halt! e)
                                  (blur-subtitle-input! (:frameId frame))
                                  (js/setTimeout
                                   (fn []
                                     (rf/dispatch [:generate-frame (:frameId frame) current-input]))
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
                                  (download-image! (:frameId frame) (:frameNumber frame) (:imageUrl frame)))}
                    {:id :generate-without-roster
                     :label "Generate without roster"
                     :icon FaWandMagic
                     :color "violet"
                     :disabled? (not has-image?)
                     :on-select (fn [e]
                                  (interaction/halt! e)
                                  (blur-subtitle-input! (:frameId frame))
                                  (js/setTimeout
                                   (fn []
                                     (rf/dispatch [:generate-frame-without-roster (:frameId frame) current-input]))
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
                                  (reset! confirm* remove-image-item))}]
           attrs {:data-frame-id (:frameId frame)
                  :className (str "frame frame-panel"
                                  (when clickable? " frame-clickable")
                                  (when editable? " frame-editing")
                                  (when active? " frame-active"))}
           nav-attrs (cond-> {:className "frame-nav-surface"}
                       clickable? (assoc :onClick (on-frame-click (:chapterId frame) (:frameId frame) (:ownerType frame))))]
       (when (not= cancel-ui-token @seen-cancel-token*)
         (reset! seen-cancel-token* cancel-ui-token)
         (reset! confirm* nil)
         (reset! upload-open?* false))
       (when (and @was-editable* (not editable?))
         (.requestAnimationFrame js/window
                                 (fn []
                                   (frame-nav/focus-subtitle! (:frameId frame)))))
       (reset! was-editable* editable?)
       [:> Card
        (merge attrs
               {:component "article"
                :withBorder true
                :padding 0
                :radius "md"})
        [:> Box {:className "frame-main"}
         [:> Box {:className "media-shell"}
          [:> Box nav-attrs
           (if has-image?
             [:<>
              [frame-status-note {:status (:status frame)
                                  :image-loading? image-loading?
                                  :image-error? image-error?}]
              [frame-image frame* image-fit]]
             [frame-placeholder frame])]
          (when (and media-nav? (not editable?))
            [:div.media-nav-zones
             [:button
              {:type "button"
               :className "media-nav-zone media-nav-prev"
               :tabIndex -1
               :onFocus (fn [e] (.blur (.-currentTarget e)))
               :aria-label "Previous frame"
               :onClick (on-media-nav-click -1)}]
             [:button
              {:type "button"
               :className "media-nav-zone media-nav-next"
               :tabIndex -1
               :onFocus (fn [e] (.blur (.-currentTarget e)))
               :aria-label "Next frame"
               :onClick (on-media-nav-click 1)}]])]
         [:div.subtitle-display-shell
          [db-text/db-text
           {:id (str (:frameId frame) "-subtitle")
            :value (if editable?
                     current-input
                     (clamp-subtitle (:description frame)))
            :editing? editable?
            :multiline? true
            :class-name "subtitle-display-shell"
            :display-class-name "subtitle-display subtitle-display-text"
            :editing-class-name "subtitle-display subtitle-display-editing"
            :input-class-name "subtitle-display-input"
            :placeholder "Click subtitle to add description"
            :max-chars max-subtitle-chars
            :min-rows 2
            :max-rows 16
            :on-open-edit #(do
                             (rf/dispatch [:set-frame-actions-open (:frameId frame) true])
                             (rf/dispatch [:set-active-frame (:frameId frame)]))
            :on-close-edit #(rf/dispatch [:set-frame-actions-open (:frameId frame) false])
            :on-change #(rf/dispatch [:frame-direction-changed (:frameId frame) %])
            :on-save #(rf/dispatch [:save-frame-description (:frameId frame) %])
            :on-focus #(rf/dispatch [:set-active-frame (:frameId frame)])
            :keep-editing-on-blur? #(or @upload-submit-blur?*
                                        (keep-frame-editing-open? (:frameId frame)))}]
          (when editable?
            [:<>
             [:div.frame-action-buttons
              [waterfall-row/waterfall-row
               {:class-name "frame-action-buttons-row"
                :actions actions
                :menu-title "Frame actions"
                :menu-aria-label "Frame actions"}]]
             [confirm-dialog/confirm-dialog
              {:item selected-item
               :on-cancel #(reset! confirm* nil)
               :on-confirm (fn []
                             (when-let [event (:dispatch-event selected-item)]
                               (rf/dispatch event))
                             (reset! confirm* nil))}]
             [upload-dialog/upload-dialog
              {:open @upload-open?*
               :active-frame-id (:frameId frame)
               :on-close (fn []
                           (reset! upload-open?* false)
                           (when @upload-submit-blur?*
                             (reset! upload-submit-blur?* false)
                             (.requestAnimationFrame js/window
                                                     (fn []
                                                       (blur-subtitle-input! (:frameId frame))))))
               :on-submit (fn [image-data-url]
                            (reset! upload-submit-blur?* true)
                            (rf/dispatch [:replace-frame-image (:frameId frame) image-data-url]))}]])]]
        ]))))
