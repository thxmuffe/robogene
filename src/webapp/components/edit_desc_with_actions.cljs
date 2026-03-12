(ns webapp.components.edit-desc-with-actions
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [webapp.components.waterfall-row :as waterfall-row]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Box TextInput Textarea]]))

(defn- trim-to-max [value max-chars]
  (let [text (or value "")]
    (if (and max-chars (> (count text) max-chars))
      (subs text 0 max-chars)
      text)))

(defn- default-keep-editing-on-blur? [edit-id]
  (let [active-el (.-activeElement js/document)
        selector (str "[data-edit-desc-id=\"" edit-id "\"]")]
    (or (interaction/closest? active-el selector)
        (interaction/closest? active-el "[role='dialog'][aria-modal='true']")
        (interaction/closest? active-el "[role='menu'], .mantine-Menu-dropdown"))))

(defn- sync-draft! [draft* value max-chars]
  (let [next-value (trim-to-max value max-chars)]
    (when (not= @draft* next-value)
      (reset! draft* next-value))))

(defn edit-desc-with-actions
  [{:keys [id
           title
           desc
           title-visible?
           editing?
           actions
           prefix-content
           action-content
           on-open-edit
           on-close-edit
           on-save
           on-title-change
           on-desc-change
           on-focus
           keep-editing-on-blur?
           class-name
           display-class-name
           editing-class-name
           title-class-name
           desc-class-name
           title-input-class-name
           desc-input-class-name
           actions-class-name
           menu-title
           menu-aria-label
           title-placeholder
           desc-placeholder
           title-input-placeholder
           desc-input-placeholder
           title-max-chars
           desc-max-chars
           title-min-rows
           title-max-rows
           desc-min-rows
           desc-max-rows
           show-actions-while-editing-only?
           extra-content
           display-title-as
           display-desc-as
           on-display-key-down]
    :or {title-visible? true
         display-title-as :div
         display-desc-as :div
         desc-min-rows 2
         desc-max-rows 16}}]
  (r/with-let [local-editing?* (r/atom false)
               edit-session-id* (r/atom 0)
               title-draft* (r/atom (trim-to-max title title-max-chars))
               desc-draft* (r/atom (trim-to-max desc desc-max-chars))
               action-pointer-down?* (r/atom false)]
    (let [editing-open? (if (some? editing?) editing? @local-editing?*)
          saved-title (trim-to-max title title-max-chars)
          saved-desc (trim-to-max desc desc-max-chars)
          title-visible? (and title-visible? (some? title))
          current-title (trim-to-max @title-draft* title-max-chars)
          current-desc (trim-to-max @desc-draft* desc-max-chars)
          dirty? (or (not= current-title saved-title)
                     (not= current-desc saved-desc))
          show-actions? (and (or (seq actions)
                                 prefix-content
                                 action-content)
                             (or (not show-actions-while-editing-only?)
                                 editing-open?))
          keep-editing? (or keep-editing-on-blur?
                            default-keep-editing-on-blur?)
          open-edit! (fn [e]
                       (interaction/stop! e)
                       (sync-draft! title-draft* title title-max-chars)
                       (sync-draft! desc-draft* desc desc-max-chars)
                       (when-not editing-open?
                         (swap! edit-session-id* inc)
                         (if (some? editing?)
                           (when on-open-edit
                             (on-open-edit))
                           (reset! local-editing?* true)))
                       (when on-focus
                         (on-focus)))
          close-edit! (fn []
                        (if (some? editing?)
                          (when on-close-edit
                            (on-close-edit))
                          (reset! local-editing?* false)))
          commit! (fn []
                    (when on-save
                      (on-save {:title current-title
                                :desc current-desc})))
          display-root-props (cond-> {:className (str (or display-class-name "")
                                                     (when (and editing-open? (seq editing-class-name))
                                                       (str " " editing-class-name))
                                                     (when (seq class-name) (str " " class-name)))
                                      :data-edit-desc-id id}
                               (not editing-open?)
                               (assoc :onClick open-edit!
                                      :onDoubleClick open-edit!))]
      (when-not editing-open?
        (sync-draft! title-draft* title title-max-chars)
        (sync-draft! desc-draft* desc desc-max-chars))
      [:> Box display-root-props
       (if editing-open?
         [:<>
          (when title-visible?
            [:> TextInput
             {:key (str id "-title-" @edit-session-id*)
              :className title-input-class-name
              :defaultValue current-title
              :maxLength title-max-chars
              :autoFocus true
              :placeholder title-input-placeholder
              :onFocus (fn [e]
                         (interaction/stop! e)
                         (when on-focus
                           (on-focus)))
              :onClick interaction/stop!
              :onChange (fn [e]
                          (let [next-value (trim-to-max (.. e -target -value) title-max-chars)]
                            (reset! title-draft* next-value)
                            (when on-title-change
                              (on-title-change next-value))))
              :onBlur (fn [_]
                        (js/setTimeout
                         (fn []
                           (cond
                             @action-pointer-down?*
                             (reset! action-pointer-down?* false)

                             (keep-editing? id)
                             nil

                             dirty?
                             (do
                               (commit!)
                               (close-edit!))

                             :else
                             (close-edit!)))
                         0))
              :onKeyDown (fn [e]
                           (let [key (.-key e)
                                 submit? (and (= "Enter" key)
                                              (or (.-metaKey e) (.-ctrlKey e)))]
                             (cond
                               (or (= "Escape" key) submit?)
                               (do
                                 (interaction/halt! e)
                                 (.blur (.-currentTarget e)))

                               on-display-key-down
                               (on-display-key-down e)

                               :else nil)))}])
          [:div.db-item-description-row
           [:> Textarea
            {:key (str id "-desc-" @edit-session-id*)
             :className desc-input-class-name
             :defaultValue current-desc
             :autosize true
             :minRows desc-min-rows
             :maxRows desc-max-rows
             :maxLength desc-max-chars
             :placeholder desc-input-placeholder
             :onFocus (fn [e]
                        (interaction/stop! e)
                        (when on-focus
                          (on-focus)))
             :onClick interaction/stop!
             :onChange (fn [e]
                         (let [next-value (trim-to-max (.. e -target -value) desc-max-chars)]
                           (reset! desc-draft* next-value)
                           (when on-desc-change
                             (on-desc-change next-value))))
             :onBlur (fn [_]
                       (js/setTimeout
                        (fn []
                          (cond
                            @action-pointer-down?*
                            (reset! action-pointer-down?* false)

                            (keep-editing? id)
                            nil

                            dirty?
                            (do
                              (commit!)
                              (close-edit!))

                            :else
                            (close-edit!)))
                        0))
             :onKeyDown (fn [e]
                          (let [key (.-key e)
                                submit? (and (= "Enter" key)
                                             (or (.-metaKey e) (.-ctrlKey e)))]
                            (cond
                              (or (= "Escape" key) submit?)
                              (do
                                (interaction/halt! e)
                                (.blur (.-currentTarget e)))

                              on-display-key-down
                              (on-display-key-down e)

                              :else nil)))}]]
          (when show-actions?
            [:div {:className (or actions-class-name "")
                   :data-edit-desc-id id
                   :onMouseDown interaction/prevent!
                   :onPointerDown interaction/prevent!
                   :onMouseDownCapture (fn [_]
                                         (reset! action-pointer-down?* true))
                   :onPointerDownCapture (fn [_]
                                           (reset! action-pointer-down?* true))}
             (when (or (seq actions) prefix-content)
               [waterfall-row/waterfall-row
                {:class-name actions-class-name
                 :prefix-content prefix-content
                 :actions actions
                 :menu-title menu-title
                 :menu-aria-label menu-aria-label
                 :on-action-pointer-down #(reset! action-pointer-down?* true)}])
             action-content])
          extra-content]
         [:<>
          (when title-visible?
            [(or display-title-as :div) {:className title-class-name
                                         :role "button"
                                         :tabIndex 0
                                         :data-edit-desc-id id
                                         :onFocus #(when on-focus (on-focus))
                                         :onKeyDown (fn [e]
                                                      (when (or (= "Enter" (.-key e))
                                                                (= " " (.-key e)))
                                                        (interaction/prevent! e)
                                                        (open-edit! e)))}
             (or (some-> saved-title str/trim not-empty)
                 title-placeholder)])
          [(or display-desc-as :div) {:className desc-class-name
                                      :role "button"
                                      :tabIndex 0
                                      :data-edit-desc-id id
                                      :onFocus #(when on-focus (on-focus))
                                      :onKeyDown (fn [e]
                                                   (when (or (= "Enter" (.-key e))
                                                             (= " " (.-key e)))
                                                     (interaction/prevent! e)
                                                     (open-edit! e)))}
           (or (some-> saved-desc str/trim not-empty)
               desc-placeholder)]
          (when show-actions?
            [:div {:className (or actions-class-name "")
                   :data-edit-desc-id id}
             (when (or (seq actions) prefix-content)
               [waterfall-row/waterfall-row
                {:class-name actions-class-name
                 :prefix-content prefix-content
                 :actions actions
                 :menu-title menu-title
                 :menu-aria-label menu-aria-label}])
             action-content])])])))
