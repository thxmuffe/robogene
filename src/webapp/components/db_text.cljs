(ns webapp.components.db-text
  (:require [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Box TextInput Textarea]]))

(defn- trim-to-max [value max-chars]
  (let [text (or value "")]
    (if (and max-chars (> (count text) max-chars))
      (subs text 0 max-chars)
      text)))

(defn- sync-draft! [draft* value max-chars]
  (let [next-value (trim-to-max value max-chars)]
    (when (not= @draft* next-value)
      (reset! draft* next-value))))

(defn db-text
  [{:keys [id
           value
           editing?
           multiline?
           class-name
           display-class-name
           editing-class-name
           input-class-name
           placeholder
           max-chars
           min-rows
           max-rows
           display-as
           on-open-edit
           on-close-edit
           on-save
           on-cancel
           on-change
           on-focus
           keep-editing-on-blur?
           on-display-key-down]
    :or {display-as :div
         min-rows 2
         max-rows 16}}]
  (r/with-let [local-editing?* (r/atom false)
               edit-session-id* (r/atom 0)
               draft* (r/atom (trim-to-max value max-chars))
               cancel-on-blur?* (r/atom false)]
    (let [editing-open? (if (some? editing?) editing? @local-editing?*)
          saved-value (trim-to-max value max-chars)
          current-value (trim-to-max @draft* max-chars)
          dirty? (not= current-value saved-value)
          open-edit! (fn [e]
                       (interaction/stop! e)
                       (sync-draft! draft* value max-chars)
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
          finish-save! (fn []
                         (when on-save
                           (on-save current-value)))
          root-props (cond-> {:className (str "db-text"
                                             (when (seq display-class-name)
                                               (str " " display-class-name))
                                             (when (and editing-open? (seq editing-class-name))
                                               (str " " editing-class-name))
                                             (when (seq class-name)
                                               (str " " class-name)))
                              :data-db-text-id id}
                       (not editing-open?)
                       (assoc :onClick open-edit!
                              :onDoubleClick open-edit!))]
      (when-not editing-open?
        (sync-draft! draft* value max-chars))
      [:> Box root-props
       (if editing-open?
         (let [common-props {:key (str id "-" @edit-session-id*)
                             :className input-class-name
                             :defaultValue current-value
                             :maxLength max-chars
                             :placeholder placeholder
                             :onFocus (fn [e]
                                        (interaction/stop! e)
                                        (when on-focus
                                          (on-focus)))
                             :onClick interaction/stop!
                             :onChange (fn [e]
                                         (let [next-value (trim-to-max (.. e -target -value) max-chars)]
                                           (reset! draft* next-value)
                                           (when on-change
                                             (on-change next-value))))
                             :onBlur (fn [_]
                                       (js/setTimeout
                                        (fn []
                                          (cond
                                            @cancel-on-blur?*
                                            (do
                                              (reset! cancel-on-blur?* false)
                                              (sync-draft! draft* value max-chars)
                                              (when on-cancel
                                                (on-cancel))
                                              (close-edit!))

                                            (and keep-editing-on-blur?
                                                 (keep-editing-on-blur?))
                                            nil

                                            dirty?
                                            (do
                                              (finish-save!)
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
                                                (when (= "Escape" key)
                                                  (reset! cancel-on-blur?* true))
                                                (.blur (.-currentTarget e)))

                                              on-display-key-down
                                              (on-display-key-down e)

                                              :else nil)))}]
           (if multiline?
             [:> Textarea (assoc common-props
                                 :autosize true
                                 :minRows min-rows
                                 :maxRows max-rows)]
             [:> TextInput (assoc common-props
                                  :autoFocus true)]))
         [(or display-as :div)
          {:className display-class-name
           :role "button"
           :tabIndex 0
           :data-db-text-id id
           :onFocus #(when on-focus (on-focus))
           :onKeyDown (fn [e]
                        (when (or (= "Enter" (.-key e))
                                  (= " " (.-key e)))
                          (interaction/prevent! e)
                          (open-edit! e)))}
          (or (some-> saved-value str not-empty)
              placeholder)])])))
