(ns webapp.components.traffic-indicator
  (:require [reagent.core :as r]))

(defn frame-status-stats [frames]
  (reduce
   (fn [{:keys [pending errors]} frame]
     (let [status (:status frame)]
       {:pending (if (or (= "queued" status)
                         (= "processing" status))
                   (inc pending)
                   pending)
        :errors (if (= "failed" status)
                  (inc errors)
                  errors)}))
   {:pending 0 :errors 0}
   (or frames [])))

(defn signal-state [{:keys [pending-api-requests wait-lights-visible? frames]}]
  (let [{:keys [pending errors]} (frame-status-stats frames)]
    (cond
      (pos? errors) :red
      (or (pos? (or pending-api-requests 0))
          (true? wait-lights-visible?)
          (pos? pending))
      :yellow
      :else :green)))

(defn traffic-indicator [state]
  (r/with-let [prev-phase* (r/atom nil)
               prev-activity* (r/atom nil)
               visible?* (r/atom false)
               dimmed?* (r/atom false)
               details-open?* (r/atom false)
               blink-step* (r/atom 0)
               blink-interval-id* (atom nil)
               hide-timeout-id* (atom nil)
               inactivity-timeout-id* (atom nil)]
    (let [{:keys [pending errors]} (frame-status-stats (:frames state))
          phase (signal-state state)
          changed? (not= phase @prev-phase*)
          pending-api-requests (or (:pending-api-requests state) 0)
          activity-key [pending-api-requests
                        pending
                        errors
                        (:wait-lights-visible? state)]
          activity-changed? (not= activity-key @prev-activity*)
          start-wait-blink! (fn []
                              (when (nil? @blink-interval-id*)
                                (reset! blink-step* 0)
                                (reset! blink-interval-id*
                                        (js/setInterval
                                         (fn []
                                           (swap! blink-step* #(mod (inc %) 4)))
                                         220))))
          stop-wait-blink! (fn []
                             (when-let [id @blink-interval-id*]
                               (js/clearInterval id)
                               (reset! blink-interval-id* nil)))
          cancel-inactivity-reset! (fn []
                                     (when-let [id @inactivity-timeout-id*]
                                       (js/clearTimeout id)
                                       (reset! inactivity-timeout-id* nil)))
          schedule-dim-hide! (fn []
                               (cancel-inactivity-reset!)
                               (when-let [id @hide-timeout-id*]
                                 (js/clearTimeout id)
                                 (reset! hide-timeout-id* nil))
                               (reset! inactivity-timeout-id*
                                       (js/setTimeout
                                        (fn []
                                          (stop-wait-blink!)
                                          (reset! dimmed?* true)
                                          (reset! hide-timeout-id*
                                                  (js/setTimeout
                                                   (fn []
                                                     (reset! visible?* false))
                                                   340)))
                                        2000)))]
      (when activity-changed?
        (reset! prev-activity* activity-key)
        (reset! dimmed?* false)
        (reset! visible?* true)
        (schedule-dim-hide!))
      (when changed?
        (reset! prev-phase* phase)
        (when-let [id @hide-timeout-id*]
          (js/clearTimeout id)
          (reset! hide-timeout-id* nil))
        (if (= phase :yellow)
          (do
            (start-wait-blink!)
            (reset! visible?* true))
          (do
            (stop-wait-blink!)
            (reset! visible?* true))))
      (when (= phase :yellow)
        (when @visible?*
          (start-wait-blink!))
        (when activity-changed?
          (reset! visible?* true)
          (start-wait-blink!)
          (schedule-dim-hide!)))
      (let [wait-step (mod @blink-step* 4)
            wait-green-on? (<= wait-step 2)
            wait-red-on? (= wait-step 3)
            red-on? (if (= phase :yellow) wait-red-on? (= phase :red))
            green-on? (if (= phase :yellow) wait-green-on? (= phase :green))
            label (case phase
                    :green "Ready"
                    :yellow "Working"
                    :red "Error"
                    "Ready")
            events (reverse (or (:events state) []))]
        (when (or (= phase :yellow) @visible?*)
          [:aside.traffic-indicator
           {:class (when @dimmed?* "is-dimmed")
            :role "status"
            :aria-live "polite"
            :aria-label (str "System status: " label)
            :on-mouse-enter #(reset! details-open?* true)
            :on-mouse-leave #(reset! details-open?* false)}
           [:button.traffic-summary
            {:type "button"
             :title "Show wait-lights details"
             :on-click #(swap! details-open?* not)}
            [:div.traffic-lights
             [:span.traffic-light.light-red {:class (when red-on? "is-on") :aria-hidden true}]
             [:span.traffic-light.light-yellow {:aria-hidden true}]
             [:span.traffic-light.light-green {:class (when green-on? "is-on") :aria-hidden true}]]
            [:div.traffic-label label]]
           (when (and @details-open?* (seq events))
             [:div.traffic-details
              [:div.traffic-details-title "Wait-lights events"]
              [:ul.traffic-events
               (for [{:keys [id ts kind message]} events]
                 ^{:key id}
                 [:li {:class (str "traffic-event " (name kind))}
                  [:span.traffic-event-time (str ts " ")]
                  [:span.traffic-event-msg message]])]])])))
    (finally
      (when-let [id @blink-interval-id*]
        (js/clearInterval id))
      (when-let [id @hide-timeout-id*]
        (js/clearTimeout id))
      (when-let [id @inactivity-timeout-id*]
        (js/clearTimeout id)))))
