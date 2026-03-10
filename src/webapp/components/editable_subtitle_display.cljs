(ns webapp.components.editable-subtitle-display
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.frame-action-buttons :as frame-action-buttons]
            ["@mantine/core" :refer [Box Textarea]]))

(defn- focus-subtitle! [frame-id]
  (.requestAnimationFrame js/window
                          (fn []
                            (frame-nav/focus-subtitle! frame-id))))

(defn- enable-editing! [frame-id e]
  (interaction/stop! e)
  (rf/dispatch [:set-frame-actions-open frame-id true]))

(defn editable-subtitle-display [{:keys [frameId description]} editing?]
  (let [saved-description (or description "")
        current-input @(rf/subscribe [:frame-draft frameId])
        subtitle (str/trim (or (when editing? current-input) description ""))]
    [:<>
     [:> Box {:className (str "subtitle-display" (when editing? " subtitle-display-editing"))
              :data-frame-id frameId
              :role (if editing? "group" "button")
              :tabIndex 0
              :title (if editing?
                       "Edit frame description"
                       "Click subtitle to edit description")
              :onFocus #(rf/dispatch [:set-active-frame frameId])
              :onClick #(when-not editing?
                          (enable-editing! frameId %))
              :onDoubleClick #(when-not editing?
                                (enable-editing! frameId %))
              :onKeyDown (fn [e]
                           (when (and (not editing?)
                                      (or (= "Enter" (.-key e))
                                          (= " " (.-key e))))
                             (interaction/prevent! e)
                             (enable-editing! frameId e)))}
      (if editing?
        [:> Textarea
         {:className "subtitle-display-input"
          :defaultValue current-input
          :autosize true
          :minRows 2
          :maxRows 8
          :autoFocus true
          :placeholder "Describe this frame..."
          :styles #js {:root #js {:width "100%"}
                       :wrapper #js {:width "100%"}
                       :input #js {:width "100%"}}
          :onFocus interaction/stop!
          :onClick interaction/stop!
          :onDoubleClick interaction/stop!
          :onChange (fn [e]
                      (rf/dispatch [:frame-direction-changed frameId (.. e -target -value)]))
          :onKeyDown (fn [e]
                       (let [key (.-key e)
                             submit? (and (= "Enter" key)
                                          (or (.-metaKey e) (.-ctrlKey e)))]
                         (cond
                           (= "Escape" key)
                           (do
                             (interaction/halt! e)
                             (rf/dispatch [:frame-direction-changed frameId saved-description])
                             (rf/dispatch [:set-frame-actions-open frameId false])
                             (focus-subtitle! frameId))

                           submit?
                           (do
                             (interaction/halt! e)
                             (rf/dispatch [:save-frame-description frameId current-input])
                             (rf/dispatch [:set-frame-actions-open frameId false])
                             (focus-subtitle! frameId))

                           :else nil)))}]
        [:span {:className "subtitle-display-text"}
         (if (seq subtitle)
           subtitle
           "Click subtitle to add description")])]
     (when editing?
       [frame-action-buttons/frame-action-buttons
        {:frame-id frameId
         :submit-disabled? (= current-input saved-description)
         :on-submit (fn []
                      (rf/dispatch [:save-frame-description frameId current-input])
                      (rf/dispatch [:set-frame-actions-open frameId false])
                      (focus-subtitle! frameId))
         :on-cancel (fn []
                      (rf/dispatch [:frame-direction-changed frameId saved-description])
                      (rf/dispatch [:set-frame-actions-open frameId false])
                      (focus-subtitle! frameId))
         :on-generate (fn []
                        (rf/dispatch [:set-frame-actions-open frameId false])
                        (focus-subtitle! frameId)
                        (rf/dispatch [:generate-frame frameId]))}])]))
