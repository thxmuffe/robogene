(ns webapp.shared.controls
  (:require [goog.object :as gobj]
            [re-frame.core :as rf]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]))

(def new-chapter-frame-id "__new_chapter__")

(defn activate-frame! [frame-id]
  (rf/dispatch [:set-active-frame frame-id]))

(defn navigate-frame! [chapter-id frame-id]
  (activate-frame! frame-id)
  (rf/dispatch [:navigate-frame chapter-id frame-id]))

(defn open-new-chapter-panel! []
  (activate-frame! new-chapter-frame-id)
  (rf/dispatch [:set-new-chapter-panel-open true]))

(defn on-window-keydown [e]
  (when-not (interaction/editable-target? (.-target e))
    (case (.-key e)
      "Escape" (rf/dispatch [:escape-pressed])
      "f" (do
            (interaction/prevent! e)
            (rf/dispatch [:toggle-frame-fullscreen]))
      "F" (do
            (interaction/prevent! e)
            (rf/dispatch [:toggle-frame-fullscreen]))
      "ArrowLeft" (do
                    (interaction/prevent! e)
                    (rf/dispatch [:keyboard-arrow "ArrowLeft"]))
      "ArrowRight" (do
                     (interaction/prevent! e)
                     (rf/dispatch [:keyboard-arrow "ArrowRight"]))
      "ArrowUp" (do
                  (interaction/prevent! e)
                  (rf/dispatch [:keyboard-arrow "ArrowUp"]))
      "ArrowDown" (do
                    (interaction/prevent! e)
                    (rf/dispatch [:keyboard-arrow "ArrowDown"]))
      "Enter" (do
                (interaction/prevent! e)
                (rf/dispatch [:open-active-frame]))
      nil)))

(defn on-media-double-click [e]
  (interaction/halt! e)
  (rf/dispatch [:toggle-frame-fullscreen]))

(defn register-global-listeners! []
  (.addEventListener js/window "focus"
                     (fn [_]
                       (rf/dispatch [:force-refresh])))
  (.addEventListener js/window "hashchange"
                     (fn [_]
                       (rf/dispatch [:hash-changed (.-hash js/location)])))
  (.addEventListener js/window "keydown" on-window-keydown)
  (.addEventListener js/document "visibilitychange"
                     (fn [_]
                       (when-not (.-hidden js/document)
                         (rf/dispatch [:force-refresh])))))

(defn on-frame-activate [frame-id]
  (fn [_]
    (activate-frame! frame-id)))

(defn on-frame-click [chapter-id frame-id]
  (fn [e]
    (when-not (interaction/interactive-child-event? e)
      (navigate-frame! chapter-id frame-id))))

(defn on-media-nav-click [delta]
  (fn [e]
    (interaction/halt! e)
    (rf/dispatch [:navigate-relative-frame delta])))

(defn on-frame-keydown-open [chapter-id frame-id]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (interaction/prevent! e)
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
    (interaction/stop! e)
    (js/setTimeout
     (fn []
       (.focus el))
     0)))

(defn resolve-textarea [el]
  (cond
    (= "TEXTAREA" (some-> el .-tagName)) el
    (fn? (some-> el .-querySelector)) (.querySelector el "textarea")
    :else nil))

(defn resize-textarea-to-content! [el]
  (when-let [ta (resolve-textarea el)]
    (when-let [style (some-> ta .-style)]
      (gobj/set style "height" "auto")
      (gobj/set style "height" (str (.-scrollHeight ta) "px")))))

(defn on-frame-editor-enable [frame-id]
  (fn [e]
    (rf/dispatch [:set-frame-actions-open frame-id true])
    (resize-textarea-to-content! (.-currentTarget e))
    (focus-current-target! e)))

(defn on-frame-editor-enable-keydown [frame-id]
  (fn [e]
    (when (or (= "Enter" (.-key e))
              (= " " (.-key e)))
      (interaction/prevent! e)
      ((on-frame-editor-enable frame-id) e))))

(defn on-frame-editor-close [frame-id]
  (fn [e]
    (interaction/stop! e)
    (rf/dispatch [:set-frame-actions-open frame-id false])))

(defn on-frame-editor-focus [e]
  (resize-textarea-to-content! (.-currentTarget e))
  (interaction/stop! e))

(defn on-frame-editor-keydown [frame-id]
  (fn [e]
    (let [key (.-key e)
          escape? (= "Escape" key)
          enter? (= "Enter" key)
          submit? (and enter? (or (.-metaKey e) (.-ctrlKey e)))]
      (cond
        escape?
        (do
          (interaction/halt! e)
          (rf/dispatch [:set-frame-actions-open frame-id false]))
        submit?
        (do
          (interaction/halt! e)
          (rf/dispatch [:generate-frame frame-id]))
        :else
        (interaction/stop! e)))))

(defn on-frame-send-click [frame-id]
  (fn [e]
    (interaction/halt! e)
    (rf/dispatch [:generate-frame frame-id])))

(defn on-frame-editor-change [frame-id editable?]
  (fn [e]
    (resize-textarea-to-content! (.-target e))
    (when editable?
      (let [next-value (.. e -target -value)]
        (rf/dispatch [:frame-direction-changed frame-id next-value])))))

(defn on-new-chapter-form-keydown [e]
  (when (and (= "Enter" (.-key e))
             (not (.-shiftKey e)))
    (interaction/prevent! e)
    (rf/dispatch [:add-chapter])))

(defn on-new-chapter-teaser-click [_]
  (open-new-chapter-panel!))

(defn on-new-chapter-teaser-keydown [e]
  (when (or (= "Enter" (.-key e))
            (= " " (.-key e)))
    (interaction/prevent! e)
    (open-new-chapter-panel!)))

(defn frame-by-id [frames frame-id]
  (some (fn [frame] (when (= (:frameId frame) frame-id) frame)) frames))

(rf/reg-fx
 :scroll-frame-into-view
 (fn [frame-id]
   (when (seq (or frame-id ""))
     (when-let [el (.querySelector js/document (str ".frame[data-frame-id=\"" frame-id "\"]"))]
       (.scrollIntoView el #js {:behavior "smooth"
                                :block (if (= frame-id "__new_chapter__") "center" "nearest")
                                :inline "nearest"})))))

(rf/reg-fx
 :move-active-frame-vertical
 (fn [{:keys [frame-id direction]}]
   (when (seq (or frame-id ""))
     (when-let [next-id (frame-nav/nearest-vertical-frame-id frame-id direction)]
       (rf/dispatch [:set-active-frame next-id])))))

(rf/reg-fx
 :move-active-frame-horizontal-dom
 (fn [{:keys [frame-id delta]}]
   (when-let [next-id (frame-nav/adjacent-frame-id frame-id delta)]
     (rf/dispatch [:set-active-frame next-id]))))

(rf/reg-event-fx
 :keyboard-arrow
 (fn [{:keys [db]} [_ key]]
   (let [view (get-in db [:route :view])]
     (if (= :frame view)
       (case key
         "ArrowLeft" {:db db :dispatch [:navigate-relative-frame -1]}
         "ArrowRight" {:db db :dispatch [:navigate-relative-frame 1]}
         {:db db})
       (case key
         "ArrowLeft" {:db db
                      :move-active-frame-horizontal-dom {:frame-id (:active-frame-id db)
                                                         :delta -1}}
         "ArrowRight" {:db db
                       :move-active-frame-horizontal-dom {:frame-id (:active-frame-id db)
                                                          :delta 1}}
         "ArrowUp" {:db db
                    :move-active-frame-vertical {:frame-id (:active-frame-id db)
                                                 :direction :up}}
         "ArrowDown" {:db db
                      :move-active-frame-vertical {:frame-id (:active-frame-id db)
                                                   :direction :down}}
         {:db db})))))

(rf/reg-event-fx
 :open-active-frame
 (fn [{:keys [db]} _]
   (let [route (:route db)
         active-id (:active-frame-id db)
         active-frame (frame-by-id (:gallery-items db) active-id)]
     (cond
       (= :frame (:view route))
       {:db db}

       (= active-id new-chapter-frame-id)
       {:db db
        :dispatch [:set-new-chapter-panel-open true]}

       (some? active-frame)
       {:db db
        :dispatch [:navigate-frame (:chapterId active-frame) (:frameId active-frame)]}

       :else
       {:db db}))))
