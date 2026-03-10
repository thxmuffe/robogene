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

(defn- close-editing! [frame-id]
  (rf/dispatch [:set-frame-actions-open frame-id false])
  (focus-subtitle! frame-id))

(defn- keep-editing-on-blur? [frame-id]
  (let [active-el (.-activeElement js/document)
        frame-actions-selector (str ".frame-action-buttons[data-frame-id=\"" frame-id "\"]")]
    (or (interaction/closest? active-el frame-actions-selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(defn editable-subtitle-display [{:keys [frameId description]} editing?]
  (let [saved-description (or description "")
        current-input @(rf/subscribe [:frame-draft frameId])
        subtitle (str/trim (or (when editing? current-input) description ""))]
    [:<>
     [:> Box (cond-> {:className (str "subtitle-display" (when editing? " subtitle-display-editing"))}
               (not editing?)
               (assoc :onClick #(enable-editing! frameId %)
                      :onDoubleClick #(enable-editing! frameId %)))
      (if editing?
        [:> Textarea
         {:className "subtitle-display-input"
          :defaultValue current-input
          :data-frame-id frameId
          :autosize true
          :minRows 2
          :maxRows 8
          :autoFocus true
          :placeholder "Describe this frame..."
          :styles #js {:root #js {:width "100%"}
                       :wrapper #js {:width "100%"}
                       :input #js {:width "100%"}}
          :onFocus (fn [e]
                     (interaction/stop! e)
                     (rf/dispatch [:set-active-frame frameId]))
          :onBlur (fn [_]
                    (js/setTimeout
                     (fn []
                       (when-not (keep-editing-on-blur? frameId)
                         (rf/dispatch [:frame-direction-changed frameId saved-description])
                         (close-editing! frameId)))
                     0))
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
                             (close-editing! frameId))

                           submit?
                           (do
                             (interaction/halt! e)
                             (rf/dispatch [:save-frame-description frameId current-input])
                             (close-editing! frameId))

                           :else nil)))}]
        [:span {:className "subtitle-display-text"
                :data-frame-id frameId
                :role "button"
                :tabIndex 0
                :title "Click subtitle to edit description"
                :onFocus #(rf/dispatch [:set-active-frame frameId])
                :onKeyDown (fn [e]
                             (when (or (= "Enter" (.-key e))
                                       (= " " (.-key e)))
                               (interaction/prevent! e)
                               (enable-editing! frameId e)))}
         (if (seq subtitle)
           subtitle
           "Click subtitle to add description")])]
     (when editing?
       [frame-action-buttons/frame-action-buttons
        {:frame-id frameId
         :submit-disabled? (= current-input saved-description)
         :on-submit (fn []
                      (rf/dispatch [:save-frame-description frameId current-input])
                      (close-editing! frameId))
         :on-cancel (fn []
                      (rf/dispatch [:frame-direction-changed frameId saved-description])
                      (close-editing! frameId))
         :on-generate (fn []
                        (close-editing! frameId)
                        (rf/dispatch [:generate-frame frameId]))}])]))
