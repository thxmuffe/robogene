(ns webapp.components.item
  "Generic Item component - renders a single entity unit.
   An Item is a leaf node (frame, character image, etc).
   Styling applied via vanityRole CSS classes; original domain-specific logic stays in options."
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.components.db-text :as db-text]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.dialog.confirm-dialog :as confirm-dialog]
            [webapp.dialog.upload-dialog :as upload-dialog]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]
            ["react-icons/fa6" :refer [FaCamera FaDownload FaEraser FaTrashCan FaWandMagic FaWandMagicSparkles]]
            ["@mantine/core" :refer [Box Card Image]]))

(def max-description-chars 500)

(defn- clamp-text [text]
  (let [value (or text "")]
    (subs value 0 (min (count value) max-description-chars))))

(defn- image-extension [image-url]
  (cond
    (str/includes? (str image-url) "data:image") ".png"
    (str/includes? (str image-url) ".png") ".png"
    (str/includes? (str image-url) ".jpg") ".jpg"
    (str/includes? (str image-url) ".jpeg") ".jpeg"
    (str/includes? (str image-url) ".gif") ".gif"
    (str/includes? (str image-url) ".webp") ".webp"
    :else ".png"))

(defn item-image
  "Render entity image or placeholder.
   
   Props from options:
   - image-field: Key in payload for image URL (default :imageUrl)
   - image-fit: CSS object-fit value (default \"contain\")
   - image-loading?: Boolean for loading state
   - image-error?: Boolean for error state"
  [entity options]
  (let [{:keys [image-field image-fit image-loading? image-error?]} options
        image-field (or image-field :imageUrl)
        image-fit (or image-fit "contain")
        image-url (or (get (:payload entity) image-field)
                      (get entity image-field))]
    (if (and image-url (not (str/blank? (str image-url))))
      [:> Image
       {:key (str (:id entity) "|" image-url)
        :src (str image-url)
        :alt (str (:title entity) " image")
        :fit image-fit
       :onLoad (when-let [on-load (:on-image-load options)]
                 #(on-load))
       :onError (when-let [on-error (:on-image-error options)]
                  #(on-error))}]
      [:> Box {:className "placeholder-img"}
       [:div.placeholder-text "No image"]])))

(defn item-status-note
  "Status overlay for image generation/upload/error states."
  [entity options]
  (let [{:keys [image-status]} options
        note-kind (cond
                    (= image-status "uploading") :uploading
                    (= image-status "processing") :processing
                    (= image-status "queued") :queued
                    (:image-loading? options) :loading-image
                    (:image-error? options) :failed
                    :else nil)
        label (case note-kind
                :uploading "Uploading..."
                :processing "Generating..."
                :queued "Queued..."
                :loading-image "Loading image..."
                :failed "Image failed to load"
                nil)]
    (when label
      [:div {:className (str "frame-status-note"
                             (when (= note-kind :loading-image) " is-image-loading")
                             (when (= note-kind :uploading) " is-uploading")
                             (when (= note-kind :failed) " is-failed"))}
       (when (#{:uploading :processing :queued :loading-image} note-kind)
         [:div {:className (str "spinner" (when (= note-kind :uploading) " spinner-reverse"))}])
       [:div.placeholder-text label]])))

(defn item-description-editor
  "Editable description field with actions and dialogs."
  [entity options editing-atom]
  (let [{:keys [description-field on-save on-generate on-generate-without-roster on-upload on-download on-clear-image on-delete on-click-edit]} options
        description-field (or description-field :description)
        description (get (:payload entity) description-field (or (:description entity) ""))
        image-url (or (get-in entity [:payload :imageUrl])
                      (:imageUrl entity))]
    [:div.meta
     [:div {:className (str "subtitle-display db-text-description-row"
                            (when @editing-atom " subtitle-display-editing"))}
      [db-text/db-text
       {:id (:id entity)
        :value description
        :editing? @editing-atom
        :multiline? true
        :class-name "subtitle-display-text"
        :input-class-name "subtitle-display-input"
        :placeholder "Add description..."
        :max-chars max-description-chars
        :min-rows 2
        :max-rows 16
        :on-open-edit #(do
                        (reset! editing-atom true)
                        (when on-click-edit (on-click-edit)))
        :on-close-edit #(reset! editing-atom false)
        :on-save (when on-save
                   (fn [text]
                     (reset! editing-atom false)
                     (on-save (clamp-text text))))}]
      
      (when @editing-atom
        [:div.frame-action-buttons.frame-action-buttons-row
         [waterfall-row/waterfall-row
          {:actions (vec (filter
                           some?
                           [{:id "generate" :label "Generate" :icon FaWandMagicSparkles :on-select on-generate}
                            {:id "generate-no-roster" :label "No Roster" :icon FaWandMagic :on-select on-generate-without-roster}
                            {:id "upload" :label "Upload" :icon FaCamera :on-select on-upload}
                            {:id "download" :label "Download" :icon FaDownload :on-select on-download}
                            (when (seq (or image-url ""))
                              {:id "clear-image" :label "Remove Image" :icon FaEraser :on-select on-clear-image})
                            {:id "delete" :label "Delete" :icon FaTrashCan :color "red" :on-select on-delete}]))
           :mandatory-count 2}]])]
     
     (when-let [confirm-fn (:on-confirm-delete options)]
       [confirm-dialog/confirm-dialog
        {:item entity
         :on-cancel (constantly nil)
         :on-confirm confirm-fn}])
     
     (when-let [upload-opts (:upload-dialog-opts options)]
       [upload-dialog/upload-dialog upload-opts])]))

(defn item
  "Generic item component - renders a single entity as a card.
   
   Entity structure:
   - :id UUID
   - :title Title text
   - :description Short description (subtitle)
   - :payload Map with flexible fields (imageUrl, metadata, etc)
   - :vanityRole Type hint (\"frame\", \"character\", etc) for styling
   
   Options map:
   - :clickable? (default true) - Click to navigate
   - :active? (default false) - Active state styling
   - :image-nav? (default false) - Show prev/next nav zones
   - :image-fit (default \"contain\") - CSS object-fit
   - :image-field (default :imageUrl) - Payload key for image
   - :description-field (default :description) - Payload key for description
   - :image-status (optional) - \"queued\", \"processing\", \"uploading\", \"failed\"
   - :on-click (fn) - Click handler
   - :on-save (fn text) - Save description handler
   - :on-generate (fn) - Generate image handler
   - :on-upload (fn) - Upload image handler
   - :on-download (fn) - Download image handler
   - :on-delete (fn) - Delete item handler
   - :on-image-load (fn) - Image loaded handler
   - :on-image-error (fn) - Image error handler"
  ([entity]
   [item entity {}])
  ([{:keys [id title description vanityRole payload] :as entity} options]
   (r/with-let [editing-atom (r/atom false)]
     (let [clickable? (or (:clickable? options) true)
           active? (:active? options false)
           image-nav? (:image-nav? options false)
           class-name (str "frame"
                           (when clickable? " frame-clickable")
                           (when active? " frame-active")
                           (when @editing-atom " frame-editing")
                           " item-" (str/lower-case (or vanityRole "generic")))]
       [:> Card
        {:className class-name
         :data-frame-id id
         :onClick (when (:on-click options)
                    (:on-click options))}
        [:> Box {:className "frame-main"}
         [:> Box {:className "media-shell"}
          [item-image entity options]
          (when image-nav?
            [:div.media-nav-zones
             [:div.media-nav-zone.nav-prev
              {:onClick (when-let [on-nav-left (:on-nav-left options)]
                          #(do (interaction/halt! %)
                               (on-nav-left)))}]
             [:div.media-nav-zone.nav-next
              {:onClick (when-let [on-nav-right (:on-nav-right options)]
                          #(do (interaction/halt! %)
                               (on-nav-right)))}]])
          [item-status-note entity options]]
         [item-description-editor entity options editing-atom]]]))))
