(ns webapp.views.traffic-indicator
  (:require [clojure.string :as str]
            [reagent.core :as r]))

(defn has-frame-error? [episodes]
  (boolean
   (some (fn [episode]
           (some (fn [frame]
                   (= "failed" (:status frame)))
                 (:frames episode)))
         episodes)))

(defn has-frame-pending? [episodes]
  (boolean
   (some (fn [episode]
           (some (fn [frame]
                   (or (= "queued" (:status frame))
                       (= "processing" (:status frame))))
                 (:frames episode)))
         episodes)))

(defn status-error? [status-text]
  (let [v (str/lower-case (str (or status-text "")))]
    (or (str/includes? v "failed")
        (str/includes? v "error"))))

(defn status-working? [status-text]
  (let [v (str/lower-case (str (or status-text "")))]
    (or (str/includes? v "queue")
        (str/includes? v "processing")
        (str/includes? v "loading")
        (str/includes? v "adding")
        (str/includes? v "deleting")
        (str/includes? v "removing")
        (str/includes? v "creating"))))

(defn signal-state [{:keys [pending-api-requests wait-lights-visible? status episodes]}]
  (cond
    (or (status-error? status) (has-frame-error? episodes)) :red
    (or (pos? (or pending-api-requests 0))
        (true? wait-lights-visible?)
        (status-working? status)
        (has-frame-pending? episodes))
    :yellow
    :else :green))

(defn traffic-indicator [state]
  (r/with-let [prev-phase* (r/atom nil)
               visible?* (r/atom false)
               blink-step* (r/atom 0)
               blink-interval-id* (atom nil)
               hide-timeout-id* (atom nil)]
    (let [phase (signal-state state)
          changed? (not= phase @prev-phase*)
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
          schedule-hide! (fn []
                           (when-let [id @hide-timeout-id*]
                             (js/clearTimeout id))
                           (reset! hide-timeout-id*
                                   (js/setTimeout
                                    (fn []
                                      (reset! visible?* false))
                                    1500)))]
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
            (reset! visible?* true)
            (schedule-hide!))))
      (when (= phase :yellow)
        (start-wait-blink!)
        (when-not @visible?*
          (reset! visible?* true)))
      (let [wait-step (mod @blink-step* 4)
            wait-green-on? (<= wait-step 2)
            wait-red-on? (= wait-step 3)
            red-on? (if (= phase :yellow) wait-red-on? (= phase :red))
            green-on? (if (= phase :yellow) wait-green-on? (= phase :green))
            label (case phase
                    :green "Ready"
                    :yellow "Working"
                    :red "Error"
                    "Ready")]
        (when (or (= phase :yellow) @visible?*)
          [:aside.traffic-indicator
           {:role "status"
            :aria-live "polite"
            :aria-label (str "System status: " label)}
           [:div.traffic-lights
            [:span.traffic-light.light-red {:class (when red-on? "is-on") :aria-hidden true}]
            [:span.traffic-light.light-yellow {:aria-hidden true}]
            [:span.traffic-light.light-green {:class (when green-on? "is-on") :aria-hidden true}]]
           [:div.traffic-label label]])))
    (finally
      (when-let [id @blink-interval-id*]
        (js/clearInterval id))
      (when-let [id @hide-timeout-id*]
        (js/clearTimeout id)))))
