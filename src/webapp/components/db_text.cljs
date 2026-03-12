(ns webapp.components.db-text
  (:require [reagent.core :as r]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [Box]]
            ["react-dom" :refer [flushSync]]))

(defn- trim-to-max [value max-chars]
  (let [text (or value "")]
    (if (and max-chars (> (count text) max-chars))
      (subs text 0 max-chars)
      text)))

(defn- autosize-textarea! [textarea-el max-rows]
  (when textarea-el
    (let [computed-style (.getComputedStyle js/window textarea-el)
          line-height (js/parseFloat (or (.-lineHeight computed-style) "0"))
          border-top (js/parseFloat (or (.-borderTopWidth computed-style) "0"))
          border-bottom (js/parseFloat (or (.-borderBottomWidth computed-style) "0"))
          padding-top (js/parseFloat (or (.-paddingTop computed-style) "0"))
          padding-bottom (js/parseFloat (or (.-paddingBottom computed-style) "0"))
          max-height (when (and max-rows (pos? line-height))
                       (+ (* max-rows line-height)
                          padding-top
                          padding-bottom
                          border-top
                          border-bottom))
          scroll-height (.-scrollHeight textarea-el)
          next-height (if max-height
                        (min scroll-height max-height)
                        scroll-height)]
      (set! (.. textarea-el -style -height) "auto")
      (set! (.. textarea-el -style -height) (str next-height "px"))
      (set! (.. textarea-el -style -overflowY)
            (if (and max-height (> scroll-height max-height))
              "auto"
              "hidden")))))

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
           on-open-edit
           on-close-edit
           on-save
           on-change
           on-focus
           keep-editing-on-blur?]
    :or {min-rows 2
         max-rows 16}}]
  (r/with-let [local-editing?* (r/atom false)
               draft* (r/atom (trim-to-max value max-chars))
               cancel-on-blur?* (r/atom false)
               textarea-el* (r/atom nil)]
    (let [editing-open? (if (some? editing?) editing? @local-editing?*)
          saved-value (trim-to-max value max-chars)
          field-value (if editing-open? @draft* saved-value)
          close-edit! (fn []
                        (if (some? editing?)
                          (when on-close-edit
                            (on-close-edit))
                          (reset! local-editing?* false)))
          open-edit! (fn []
                       (when-not editing-open?
                         (flushSync
                          (fn []
                            (reset! draft* saved-value)
                            (if (some? editing?)
                              (when on-open-edit
                                (on-open-edit))
                              (reset! local-editing?* true))))))
          cancel-edit! (fn []
                         (reset! cancel-on-blur?* false)
                         (reset! draft* saved-value)
                         (close-edit!))
          save-and-close! (fn []
                            (let [current-value (trim-to-max @draft* max-chars)]
                              (when on-save
                                (on-save current-value))
                              (close-edit!)))
          root-props {:className (str "db-text"
                                      (when (seq display-class-name)
                                        (str " " display-class-name))
                                      (when (and editing-open? (seq editing-class-name))
                                        (str " " editing-class-name))
                                      (when (seq class-name)
                                        (str " " class-name)))
                      :data-db-text-id id}
          field-props {:className input-class-name
                       :value field-value
                       :readOnly (not editing-open?)
                       :placeholder placeholder
                       :maxLength max-chars
                       :rows (when multiline? min-rows)
                       :data-db-text-id id
                       :onPointerDown (fn [e]
                                        (when-not editing-open?
                                          (open-edit!)
                                          (when on-focus
                                            (on-focus))))
                       :onFocus (fn [e]
                                  (interaction/stop! e)
                                  (when on-focus
                                    (on-focus)))
                       :onClick (fn [e]
                                  (when editing-open?
                                    (interaction/stop! e)))
                       :onChange (fn [e]
                                   (let [next-value (trim-to-max (.. e -target -value) max-chars)]
                                     (reset! draft* next-value)
                                     (when multiline?
                                       (autosize-textarea! (.-currentTarget e) max-rows))
                                     (when on-change
                                       (on-change next-value))))
                       :onBlur (fn [_]
                                 (js/setTimeout
                                  (fn []
                                    (cond
                                      @cancel-on-blur?*
                                      (cancel-edit!)

                                      (and keep-editing-on-blur?
                                           (keep-editing-on-blur?))
                                      nil

                                      (not= (trim-to-max @draft* max-chars) saved-value)
                                      (save-and-close!)

                                      :else
                                      (close-edit!)))
                                  0))
                       :onKeyDown (fn [e]
                                    (let [key (.-key e)
                                          submit? (and (= "Enter" key)
                                                       (or (.-metaKey e) (.-ctrlKey e)))]
                                      (cond
                                        (and (not editing-open?)
                                             (or (= "Enter" key) (= " " key)))
                                        (do
                                          (interaction/prevent! e)
                                          (open-edit!))

                                        (or (= "Escape" key) submit?)
                                        (do
                                          (interaction/halt! e)
                                          (when (= "Escape" key)
                                            (reset! cancel-on-blur?* true))
                                          (.blur (.-currentTarget e)))

                                        :else nil)))}]
      (when-not editing-open?
        (reset! draft* saved-value))
      (when multiline?
        (.requestAnimationFrame js/window
                                (fn []
                                  (autosize-textarea! @textarea-el* max-rows))))
      [:> Box root-props
       (if multiline?
         [:textarea (cond-> field-props
                      true (assoc :ref #(reset! textarea-el* %))
                      editing-open? (assoc :autoFocus true)
                      multiline? (assoc :rows min-rows))]
         [:input (cond-> field-props
                   true (assoc :type "text")
                   editing-open? (assoc :autoFocus true))])])))
