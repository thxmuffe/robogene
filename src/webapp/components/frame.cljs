(ns webapp.components.frame
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.frame-description-editor :as description-editor]
            ["@mantine/core" :refer [Badge Box Card Image]]))

(defn on-editor-enable [frame-id]
  (fn [e]
    (interaction/stop! e)
    (rf/dispatch [:set-frame-actions-open frame-id true])
    nil))

(defn on-editor-enable-keydown [frame-id]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (interaction/prevent! e)
      ((on-editor-enable frame-id) e))))

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
   {:src (or imageUrl "")
    :alt (str "Frame " frameId)
    :fit image-fit
    :onLoad #(rf/dispatch [:frame-image-loaded frameId imageUrl])
    :onError #(rf/dispatch [:frame-image-error frameId imageUrl])}])

(defn subtitle-display [{:keys [frameId description]} frame-input]
  (let [subtitle (str/trim (or frame-input description ""))]
    [:> Box {:className "subtitle-display"
             :data-frame-id frameId
             :role "button"
             :tabIndex 0
             :title "Click subtitle to edit description"
             :onFocus #(rf/dispatch [:set-active-frame frameId])
             :onClick (on-editor-enable frameId)
             :onDoubleClick (on-editor-enable frameId)
             :onKeyDown (on-editor-enable-keydown frameId)}
     [:span {:className "subtitle-display-text"}
      (if (seq subtitle)
        subtitle
        "Click subtitle to add description")]]))

(defn frame-placeholder [{:keys [status]}]
  (let [label (case status
                "processing" "Generating..."
                "queued" "Queued..."
                "failed" "Generation failed"
                "Add subtitle and generate")]
    [:> Box {:className "placeholder-img"}
     (when (or (= status "queued") (= status "processing"))
      [:div.spinner])
     [:div.placeholder-text label]]))

(defn frame
  ([frame frame-input]
   [frame frame-input {:clickable? true}])
  ([frame frame-input {:keys [clickable? active? actions-open? media-nav? image-fit]
                       :or {clickable? true active? false actions-open? false media-nav? false image-fit "contain"}}]
   (r/with-let [was-editable* (r/atom false)]
     (let [has-image? (not (str/blank? (or (:imageUrl frame) "")))
           busy? (or (= "queued" (:status frame)) (= "processing" (:status frame)))
           image-ui @(rf/subscribe [:frame-image-ui (:frameId frame)])
           image-loading? (= :loading image-ui)
           image-error? (= :error image-ui)
           editable? (true? actions-open?)
           frame* (assoc frame :actionsOpen actions-open?)
           attrs {:data-frame-id (:frameId frame)
                  :className (str "frame frame-panel"
                                  (when clickable? " frame-clickable")
                                  (when active? " frame-active"))
                  :onMouseEnter (controls/on-frame-activate (:frameId frame))}
           nav-attrs (cond-> {:className "frame-nav-surface"}
                       clickable? (assoc :onClick (on-frame-click (:chapterId frame) (:frameId frame) (:ownerType frame))))]
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
        [:> Box {:className "media-shell"}
         [:> Box nav-attrs
          (if has-image?
            [:<>
             [frame-image frame* image-fit]
             (when (or busy? image-loading?)
               [:div.media-loading-overlay
                [:div.spinner]
                [:div.placeholder-text (if busy? "Generating..." "Loading image...")]])
             (when (and image-error? (not busy?))
               [:div.media-loading-overlay
                [:div.placeholder-text "Image failed to load"]])]
            [frame-placeholder frame])]
         (if editable?
           [description-editor/frame-description-editor frame* frame-input]
           [subtitle-display frame* frame-input])
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
        (when busy?
          [:> Badge {:className "badge queue badge-queue-overlay"
                     :size "sm"}
           "In Queue"])]))))
