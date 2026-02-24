(ns webapp.shared.controls
  (:require [clojure.string :as str]
            [re-frame.core :as rf]))

(def new-chapter-frame-id "__new_chapter__")

(defn typing-target? [el]
  (let [tag (some-> el .-tagName str/lower-case)]
    (or (= tag "input")
        (= tag "textarea")
        (= tag "select")
        (true? (.-isContentEditable el)))))

(defn activate-frame! [frame-id]
  (rf/dispatch [:set-active-frame frame-id]))

(defn navigate-frame! [chapter-id frame-id]
  (activate-frame! frame-id)
  (rf/dispatch [:navigate-frame chapter-id frame-id]))

(defn open-new-chapter-panel! []
  (activate-frame! new-chapter-frame-id)
  (rf/dispatch [:set-new-chapter-panel-open true]))

(defn on-window-focus [_]
  (rf/dispatch [:force-refresh]))

(defn on-window-hashchange [_]
  (rf/dispatch [:hash-changed (.-hash js/location)]))

(defn on-document-visibilitychange [_]
  (when-not (.-hidden js/document)
    (rf/dispatch [:force-refresh])))

(defn on-window-keydown [e]
  (when-not (typing-target? (.-target e))
    (case (.-key e)
      "Escape" (rf/dispatch [:navigate-index])
      "ArrowLeft" (do
                    (.preventDefault e)
                    (rf/dispatch [:keyboard-arrow "ArrowLeft"]))
      "ArrowRight" (do
                     (.preventDefault e)
                     (rf/dispatch [:keyboard-arrow "ArrowRight"]))
      "ArrowUp" (do
                  (.preventDefault e)
                  (rf/dispatch [:keyboard-arrow "ArrowUp"]))
      "ArrowDown" (do
                    (.preventDefault e)
                    (rf/dispatch [:keyboard-arrow "ArrowDown"]))
      "Enter" (do
                (.preventDefault e)
                (rf/dispatch [:open-active-frame]))
      nil)))

(defn register-global-listeners! []
  (.addEventListener js/window "focus" on-window-focus)
  (.addEventListener js/window "hashchange" on-window-hashchange)
  (.addEventListener js/window "keydown" on-window-keydown)
  (.addEventListener js/document "visibilitychange" on-document-visibilitychange))

(defn on-frame-activate [frame-id]
  (fn [_]
    (activate-frame! frame-id)))

(defn on-frame-click [chapter-id frame-id]
  (fn [_]
    (navigate-frame! chapter-id frame-id)))

(defn on-frame-keydown-open [chapter-id frame-id]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (.preventDefault e)
      (navigate-frame! chapter-id frame-id))))

(defn on-frame-blur-close-actions [frame-id actions-open?]
  (fn [e]
    (when (true? actions-open?)
      (let [container (.-currentTarget e)]
        (js/setTimeout
         (fn []
           (let [active-el (.-activeElement js/document)]
             (when (and (some? active-el)
                        (not (.contains container active-el)))
               (rf/dispatch [:set-frame-actions-open frame-id false]))))
         60)))))

(defn focus-current-target! [e]
  (let [el (.-currentTarget e)]
    (.stopPropagation e)
    (js/setTimeout
     (fn []
       (.focus el))
     0)))

(defn on-frame-editor-enable [frame-id]
  (fn [e]
    (rf/dispatch [:set-frame-actions-open frame-id true])
    (focus-current-target! e)))

(defn on-frame-editor-focus [e]
  (.stopPropagation e))

(defn on-frame-editor-keydown [frame-id busy? editable?]
  (fn [e]
    (let [enter? (= "Enter" (.-key e))
          submit? (and (not busy?) enter? (not (.-shiftKey e)))]
      (cond
        submit?
        (do
          (.preventDefault e)
          (.stopPropagation e)
          (rf/dispatch [:set-frame-actions-open frame-id true])
          (rf/dispatch [:generate-frame frame-id]))
        (and (not editable?) enter?)
        (do
          (.preventDefault e)
          (.stopPropagation e)
          (rf/dispatch [:set-frame-actions-open frame-id true]))
        :else
        (.stopPropagation e)))))

(defn on-frame-editor-change [frame-id editable?]
  (fn [e]
    (when editable?
      (let [next-value (.. e -target -value)]
        (rf/dispatch [:frame-direction-changed frame-id next-value])))))

(defn on-new-chapter-form-keydown [e]
  (when (and (= "Enter" (.-key e))
             (not (.-shiftKey e)))
    (.preventDefault e)
    (rf/dispatch [:add-chapter])))

(defn on-new-chapter-teaser-click [_]
  (open-new-chapter-panel!))

(defn on-new-chapter-teaser-keydown [e]
  (when (or (= "Enter" (.-key e))
            (= " " (.-key e)))
    (.preventDefault e)
    (open-new-chapter-panel!)))
