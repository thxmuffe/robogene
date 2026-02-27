(ns webapp.shared.controls
  (:require [clojure.string :as str]
            [goog.object :as gobj]
            [re-frame.core :as rf]))

(def new-chapter-frame-id "__new_chapter__")

(defn frame-node-list []
  (array-seq (.querySelectorAll js/document ".frame[data-frame-id]")))

(defn frame-id-of [el]
  (.getAttribute el "data-frame-id"))

(defn frame-centers [el]
  (let [r (.getBoundingClientRect el)]
    {:x (+ (.-left r) (/ (.-width r) 2))
     :y (+ (.-top r) (/ (.-height r) 2))
     :el el}))

(defn adjacent-frame-id [current-id delta]
  (let [nodes (vec (frame-node-list))
        n (count nodes)]
    (when (pos? n)
      (let [idx (or (some (fn [[i el]]
                            (when (= current-id (frame-id-of el)) i))
                          (map-indexed vector nodes))
                    (if (neg? delta) 0 (dec n)))
            next-idx (mod (+ idx (if (neg? delta) -1 1)) n)
            next-el (nth nodes next-idx nil)]
        (some-> next-el frame-id-of)))))

(defn nearest-vertical-frame-id [current-id direction]
  (let [nodes (frame-node-list)
        current-el (some (fn [el] (when (= current-id (frame-id-of el)) el)) nodes)]
    (when current-el
      (let [{cx :x cy :y} (frame-centers current-el)
            candidates (->> nodes
                            (filter (fn [el]
                                      (let [{x :x y :y} (frame-centers el)]
                                        (case direction
                                          :up (< y (- cy 8))
                                          :down (> y (+ cy 8))
                                          false))))
                            (map (fn [el]
                                   (let [{x :x y :y} (frame-centers el)
                                         dy (js/Math.abs (- y cy))
                                         dx (js/Math.abs (- x cx))]
                                     {:id (frame-id-of el)
                                      :dy dy
                                      :dx dx})))
                            (sort-by (fn [{:keys [dy dx]}] [dy dx])))]
        (or (:id (first candidates))
            (case direction
              :up (some-> nodes last frame-id-of)
              :down (some-> nodes first frame-id-of)
              nil))))))

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

(defn on-window-keydown [e]
  (when-not (typing-target? (.-target e))
    (case (.-key e)
      "Escape" (rf/dispatch [:escape-pressed])
      "f" (do
            (.preventDefault e)
            (rf/dispatch [:toggle-frame-fullscreen]))
      "F" (do
            (.preventDefault e)
            (rf/dispatch [:toggle-frame-fullscreen]))
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

(defn on-media-double-click [e]
  (.preventDefault e)
  (.stopPropagation e)
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
  (fn [_]
    (navigate-frame! chapter-id frame-id)))

(defn on-media-nav-click [delta]
  (fn [e]
    (.preventDefault e)
    (.stopPropagation e)
    (rf/dispatch [:navigate-relative-frame delta])))

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

(defn resize-textarea-to-content! [el]
  (when (= "TEXTAREA" (some-> el .-tagName))
    (when-let [style (some-> el .-style)]
      (gobj/set style "height" "auto")
      (gobj/set style "height" (str (.-scrollHeight el) "px")))))

(defn on-frame-editor-enable [frame-id]
  (fn [e]
    (rf/dispatch [:set-frame-actions-open frame-id true])
    (resize-textarea-to-content! (.-currentTarget e))
    (focus-current-target! e)))

(defn on-frame-editor-focus [e]
  (resize-textarea-to-content! (.-currentTarget e))
  (.stopPropagation e))

(defn on-frame-editor-keydown [frame-id busy? editable?]
  (fn [e]
    (let [enter? (= "Enter" (.-key e))
          submit? (and (not busy?) editable? enter? (or (.-metaKey e) (.-ctrlKey e)))]
      (cond
        submit?
        (do
          (.preventDefault e)
          (.stopPropagation e)
          (rf/dispatch [:generate-frame frame-id]))
        (and (not editable?) (or enter? (= " " (.-key e))))
        (do
          (.preventDefault e)
          (.stopPropagation e)
          (rf/dispatch [:set-frame-actions-open frame-id true]))
        :else
        (.stopPropagation e)))))

(defn on-frame-send-click [frame-id busy? editable?]
  (fn [e]
    (.preventDefault e)
    (.stopPropagation e)
    (if editable?
      (when-not busy?
        (rf/dispatch [:generate-frame frame-id]))
      (rf/dispatch [:set-frame-actions-open frame-id true]))))

(defn on-frame-editor-change [frame-id editable?]
  (fn [e]
    (resize-textarea-to-content! (.-target e))
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
     (when-let [next-id (nearest-vertical-frame-id frame-id direction)]
       (rf/dispatch [:set-active-frame next-id])))))

(rf/reg-fx
 :move-active-frame-horizontal-dom
 (fn [{:keys [frame-id delta]}]
   (when-let [next-id (adjacent-frame-id frame-id delta)]
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
