(ns webapp.components.editable-subtitle-display
  (:require [clojure.string :as str]
            [reagent.core :as r]
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

(def max-subtitle-chars 500)

(defn- clamp-subtitle [text]
  (let [value (subs (or text "") 0 (min (count (or text "")) max-subtitle-chars))]
    value))

(defn editable-subtitle-display [{:keys [frameId description]} editing?]
  (let [skip-blur-revert?* (r/atom false)]
    (fn [{:keys [frameId description]} editing?]
      (let [saved-description (clamp-subtitle description)
            current-input (clamp-subtitle @(rf/subscribe [:frame-draft frameId]))
            subtitle (str/trim (or (when editing? current-input) saved-description ""))
            subtitle-props (cond-> {:className (str "subtitle-display" (when editing? " subtitle-display-editing"))}
                             (not editing?)
                             (assoc :onClick #(enable-editing! frameId %)
                                    :onDoubleClick #(enable-editing! frameId %)))
            close-without-blur-revert! (fn []
                                         (reset! skip-blur-revert?* true)
                                         (close-editing! frameId))]
        [:> Box subtitle-props
         (if editing?
           [:<>
            [:> Textarea
             {:className "subtitle-display-input"
              :defaultValue current-input
              :data-frame-id frameId
              :autosize true
              :minRows 2
              :maxRows 16
              :maxLength max-subtitle-chars
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
                           (if @skip-blur-revert?*
                             (reset! skip-blur-revert?* false)
                             (when-not (keep-editing-on-blur? frameId)
                               (rf/dispatch [:frame-direction-changed frameId saved-description])
                               (close-editing! frameId))))
                         0))
              :onClick interaction/stop!
              :onDoubleClick interaction/stop!
              :onChange (fn [e]
                          (rf/dispatch [:frame-direction-changed frameId (clamp-subtitle (.. e -target -value))]))
              :onKeyDown (fn [e]
                           (let [key (.-key e)
                                 submit? (and (= "Enter" key)
                                              (or (.-metaKey e) (.-ctrlKey e)))]
                             (cond
                               (= "Escape" key)
                               (do
                                 (interaction/halt! e)
                                 (rf/dispatch [:frame-direction-changed frameId saved-description])
                                 (close-without-blur-revert!))

                               submit?
                               (do
                                 (interaction/halt! e)
                                 (rf/dispatch [:save-frame-description frameId current-input])
                                 (close-without-blur-revert!))

                               :else nil)))}]
            [frame-action-buttons/frame-action-buttons
             {:frame-id frameId
              :submit-disabled? (= current-input saved-description)
              :on-submit (fn []
                           (rf/dispatch [:save-frame-description frameId current-input])
                           (close-without-blur-revert!))
              :on-cancel (fn []
                           (rf/dispatch [:frame-direction-changed frameId saved-description])
                           (close-without-blur-revert!))
              :on-generate (fn []
                             (rf/dispatch [:save-frame-description frameId current-input])
                             (close-without-blur-revert!)
                             (rf/dispatch [:generate-frame frameId current-input]))}]]
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
              "Click subtitle to add description")])]))))
