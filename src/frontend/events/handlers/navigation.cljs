(ns frontend.events.handlers.navigation
  (:require [re-frame.core :as rf]
            [frontend.events.model :as model]
            [frontend.events.handlers.shared :as shared]))

(rf/reg-event-fx
 :navigate-frame
 (fn [{:keys [db]} [_ episode-id frame-number]]
   (let [episode (or episode-id (get-in db [:route :episode]) (get-in db [:latest-state :storyId]) "local")]
     {:db db
      :set-hash (model/frame-hash episode frame-number)})))

(rf/reg-event-fx
 :navigate-index
 (fn [{:keys [db]} _]
   {:db db
    :set-hash ""}))

(rf/reg-event-fx
 :navigate-relative-frame
 (fn [{:keys [db]} [_ delta]]
   (let [route (:route db)]
     (if (= :frame (:view route))
       (let [episode-id (:episode route)
             frames-in-episode (or (->> (:episodes db)
                                        (some (fn [episode]
                                                (when (= (:episodeId episode) episode-id)
                                                  (:frames episode)))))
                                   (->> (:gallery-items db)
                                        (filter (fn [frame] (= (:episodeId frame) episode-id)))
                                        vec)
                                   [])
             ordered (model/ordered-frames frames-in-episode)
             current-frame (:frame-number route)
             idx (model/frame-index-by-number ordered current-frame)
             count-frames (count ordered)
             safe-idx (if (number? idx) idx 0)
             target-idx (when (pos? count-frames)
                          (mod (+ safe-idx delta) count-frames))]
         (if (and (number? target-idx) (pos? count-frames))
           {:db db
            :dispatch [:navigate-frame episode-id (model/frame-number-of (nth ordered target-idx))]}
           {:db db}))
       {:db db}))))

(rf/reg-event-fx
 :move-active-frame-horizontal
 (fn [{:keys [db]} [_ delta]]
   (let [all-frames (model/ordered-frames (:gallery-items db))
         current-id (:active-frame-id db)
         current-frame (shared/frame-by-id all-frames current-id)]
     (cond
       (and (= current-id shared/new-episode-frame-id) (seq all-frames))
       {:db db
        :dispatch [:set-active-frame (if (neg? delta)
                                       (:frameId (last all-frames))
                                       (:frameId (first all-frames)))]}

       (some? current-frame)
       (let [episode-id (:episodeId current-frame)
             episode-frames (->> all-frames
                                 (filter (fn [frame] (= (:episodeId frame) episode-id)))
                                 model/ordered-frames)
             count-frames (count episode-frames)
             idx (shared/frame-index-by-id episode-frames (:frameId current-frame))
             safe-idx (if (number? idx) idx 0)
             target-idx (when (pos? count-frames)
                          (mod (+ safe-idx delta) count-frames))
             next-frame (when (number? target-idx)
                          (nth episode-frames target-idx nil))]
         (if next-frame
           {:db db
            :dispatch [:set-active-frame (:frameId next-frame)]}
           {:db db}))

       :else
       {:db db}))))

(rf/reg-event-fx
 :move-active-frame-vertical
 (fn [{:keys [db]} [_ direction]]
   (if (some? (:active-frame-id db))
     {:db db
      :move-active-frame-vertical {:frame-id (:active-frame-id db)
                                   :direction direction}}
     {:db db})))

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
         "ArrowLeft" {:db db :dispatch [:move-active-frame-horizontal -1]}
         "ArrowRight" {:db db :dispatch [:move-active-frame-horizontal 1]}
         "ArrowUp" {:db db :dispatch [:move-active-frame-vertical :up]}
         "ArrowDown" {:db db :dispatch [:move-active-frame-vertical :down]}
         {:db db})))))
