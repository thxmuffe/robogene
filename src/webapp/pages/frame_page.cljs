(ns webapp.pages.frame-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.components.frame :as frame]
            [webapp.components.social-media-buttons :as social-media-buttons]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            ["@mantine/core" :refer [ActionIcon Box Button Group]]
            ["react-icons/fa6" :refer [FaXmark]]))

(defn prev-next-by-id [frames frame-id]
  (loop [remaining (seq frames)
         prev nil]
    (if-let [active-frame (first remaining)]
      (if (= (:frameId active-frame) frame-id)
        {:prev prev
         :next (second remaining)
         :active active-frame}
        (recur (rest remaining) active-frame))
      {:prev nil :next nil :active nil})))

(defn owner-display-name [from-page chapter-id saga roster]
  (let [rows (if (= :roster from-page) roster saga)
        id-key (if (= :roster from-page) :characterId :chapterId)]
    (some->> rows
             (some (fn [row]
                     (when (= (id-key row) chapter-id)
                       (or (some-> (:name row) str/trim not-empty)
                           (some-> (:description row) str/trim not-empty))))))))

(defn top-controls [from-page owner-name saga-name]
  (let [show-back? (some? from-page)]
    [:> Group {:className "detail-controls"
               :gap "xs"
               :wrap "wrap"}
     (when show-back?
       [back-button/back-button
        {:label "Back"
         :on-click #(rf/dispatch [:navigate-from-page])}])
     [:> Button
      {:variant "default"
       :size "sm"
       :className "roster-nav-btn"
       :onClick #(rf/dispatch [:navigate-roster-page saga-name])}
      "Roster"]]))

(defn nav-controls [chapter-id frame-neighbors from-page]
  (let [prev-frame (:prev frame-neighbors)
        next-frame (:next frame-neighbors)]
    [:> Group {:className "detail-controls"
               :gap "xs"
               :wrap "wrap"}
     [:> Button
      {:variant "default"
      :size "sm"
       :disabled (nil? prev-frame)
       :onClick #(when prev-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId prev-frame) from-page]))}
      "Previous"]
     [:> Button
      {:variant "default"
       :size "sm"
       :disabled (nil? next-frame)
       :onClick #(when next-frame
                   (rf/dispatch [:navigate-frame chapter-id (:frameId next-frame) from-page]))}
      "Next"]
     [:> Button
      {:variant "filled"
       :size "sm"
       :color "orange"
       :onClick #(rf/dispatch [:toggle-frame-fullscreen])}
      "Fullscreen (F)"]]))

(defn handle-frame-page-key-down! [{:keys [fullscreen? active-frame-id description-editor-open? from-page]} e]
  (let [key (or (.-key e) "")
        lower-key (str/lower-case key)]
    (cond
      (and (= "Escape" key)
           (or description-editor-open?
               (interaction/modal-open?)))
      (do
        (interaction/halt! e)
        (rf/dispatch [:cancel-open-edit-db-items]))

      (not (interaction/ignore-global-keydown? e))
      (cond
        (= "Enter" key)
        (when active-frame-id
          (interaction/halt! e)
          (rf/dispatch [:set-frame-actions-open active-frame-id true]))

        (= "Escape" key)
        (do
          (interaction/halt! e)
          (cond
            fullscreen?
            (rf/dispatch [:set-frame-fullscreen false])

            :else
            (do
              (when active-frame-id
                (rf/dispatch [:set-active-frame active-frame-id]))
              (when from-page
                (rf/dispatch [:navigate-from-page])))))

        (= "f" lower-key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:toggle-fullscreen-shortcut]))

        (= "ArrowLeft" key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:navigate-relative-frame -1]))

        (= "ArrowRight" key)
        (do
          (interaction/halt! e)
          (rf/dispatch [:navigate-relative-frame 1]))

        :else nil)

      :else nil)))

(defn frame-page [route saga-name]
  (r/with-let [key-context* (r/atom nil)
               focused-subtitle-key* (r/atom nil)
               key-handler (fn [e]
                             (handle-frame-page-key-down! @key-context* e))]
    (.addEventListener js/window "keydown" key-handler)
    (let [chapter-id (:chapter route)
          saga @(rf/subscribe [:saga])
          roster @(rf/subscribe [:roster])
          frame-id (:frame-id route)
          from-page (:from-page route)
          owner-type (if (= :roster from-page) "character" "saga")
          ordered @(rf/subscribe [:frames-for-owner owner-type chapter-id])
          owner-name (owner-display-name from-page chapter-id saga roster)
          fullscreen? (true? (:fullscreen? route))
          description-editor-open? @(rf/subscribe [:frame-edit-open? frame-id])
          frame-neighbors (prev-next-by-id ordered frame-id)
          active-frame (:active frame-neighbors)]
      (reset! key-context* {:fullscreen? fullscreen?
                            :active-frame-id frame-id
                            :description-editor-open? description-editor-open?
                            :from-page from-page})
      (let [focus-key [frame-id fullscreen? description-editor-open?]]
        (when (and active-frame
                   (not description-editor-open?)
                   (not= focus-key @focused-subtitle-key*))
          (reset! focused-subtitle-key* focus-key)
          (.requestAnimationFrame js/window
                                  (fn []
                                    (frame-nav/focus-subtitle! frame-id)))))
      [:section {:className "frame-page-section"}
       (if active-frame
         [:> Box {:className (str "detail-page" (when fullscreen? " detail-page-fullscreen"))}
           (when-not fullscreen?
             [top-controls from-page owner-name saga-name])
          [frame/frame active-frame
            {:clickable? false
             :media-nav? true
             :image-fit "contain"}]
           (when-not fullscreen?
             [nav-controls chapter-id frame-neighbors from-page])
           (if fullscreen?
             [:> ActionIcon
              {:className "fullscreen-close"
               :color "orange"
               :aria-label "Close fullscreen"
               :title "Close fullscreen"
               :variant "filled"
               :radius "xl"
               :onClick #(rf/dispatch [:set-frame-fullscreen false])}
              [:> FaXmark]]
             [social-media-buttons/social-media-buttons {:saga-name saga-name}])]
          [:> Box {:className "detail-missing"}
           [:p "Frame not found in this chapter."]
           (when from-page
             [back-button/back-button
              {:label "Back"
               :on-click #(rf/dispatch [:navigate-from-page])}])])])
    (finally
      (.removeEventListener js/window "keydown" key-handler))))
