(ns webapp.components.frame
  "Compat shim: render a frame row using the generic item component."
  (:require [re-frame.core :as rf]
            [webapp.components.item :as item]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.interaction :as interaction]))

(defn frame-owner-page [owner-type]
  (if (= "character" (str owner-type))
    :roster
    :saga))

(defn frame->entity [frame]
  {:id (:frameId frame)
   :title (or (:title frame) (str "Frame " (:frameNumber frame)))
   :description (:description frame)
   :vanityRole "frame"
   :payload {:imageUrl (:imageUrl frame)
             :imageStatus (:imageStatus frame)
             :frameNumber (:frameNumber frame)
             :chapterId (:chapterId frame)
             :ownerType (:ownerType frame)}})

(defn- default-on-click [frame]
  (let [chapter-id (:chapterId frame)
        frame-id (:frameId frame)
        owner-type (or (:ownerType frame) "saga")]
    (fn [e]
      (when-not (interaction/interactive-child-event? e)
        (controls/navigate-frame! chapter-id frame-id (frame-owner-page owner-type))))))

(defn frame
  "Render a frame using the generic item component. Accepts legacy frame opts."
  ([frame] [frame frame {}])
  ([frame {:keys [clickable? active? media-nav? image-fit] :as opts}]
   (let [entity (frame->entity frame)
         clickable? (if (nil? clickable?) true clickable?)
         frame-id (:frameId frame)
         on-click (or (:on-click opts)
                      (when clickable?
                        (default-on-click frame)))
         item-opts (merge {:clickable? clickable?
                           :active? active?
                           :image-nav? media-nav?
                           :image-fit (or image-fit "contain")
                           :image-status (:imageStatus frame)
                           :on-click on-click
                           :on-click-edit #(rf/dispatch [:set-frame-actions-open frame-id true])
                           :on-save #(rf/dispatch [:save-frame-description frame-id %])
                           :on-generate #(rf/dispatch [:generate-frame frame-id nil])
                           :on-generate-without-roster #(rf/dispatch [:generate-frame-without-roster frame-id nil])
                           :on-upload #(rf/dispatch [:open-upload-dialog frame-id])
                           :on-download #(when-let [url (:imageUrl frame)]
                                           (controls/download-image! url (str "frame-" (:frameNumber frame) ".png")))
                           :on-clear-image #(rf/dispatch [:clear-frame-image frame-id])
                           :on-delete #(rf/dispatch [:delete-frame frame-id])
                           :on-image-load #(rf/dispatch [:frame-image-loaded frame-id (:imageUrl frame)])
                           :on-image-error #(rf/dispatch [:frame-image-error frame-id (:imageUrl frame)])
                           :on-nav-left #(rf/dispatch [:navigate-relative-frame -1])
                           :on-nav-right #(rf/dispatch [:navigate-relative-frame 1])}
                          opts)]
     [item/item entity item-opts])))
