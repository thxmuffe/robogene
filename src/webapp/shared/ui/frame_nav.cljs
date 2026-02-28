(ns webapp.shared.ui.frame-nav)

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
            step (if (neg? delta) -1 1)
            next-idx (mod (+ idx step) n)
            next-el (nth nodes next-idx nil)]
        (some-> next-el frame-id-of)))))

(defn nearest-vertical-frame-id [current-id direction]
  (let [nodes (frame-node-list)
        current-el (some (fn [el] (when (= current-id (frame-id-of el)) el)) nodes)]
    (when current-el
      (let [{cx :x cy :y} (frame-centers current-el)
            candidates (->> nodes
                            (filter (fn [el]
                                      (let [{y :y} (frame-centers el)]
                                        (case direction
                                          :up (< y (- cy 8))
                                          :down (> y (+ cy 8))
                                          false))))
                            (map (fn [el]
                                   (let [{x :x y :y} (frame-centers el)]
                                     {:id (frame-id-of el)
                                      :dy (js/Math.abs (- y cy))
                                      :dx (js/Math.abs (- x cx))})))
                            (sort-by (fn [{:keys [dy dx]}] [dy dx])))]
        (or (:id (first candidates))
            (case direction
              :up (some-> nodes last frame-id-of)
              :down (some-> nodes first frame-id-of)
              nil))))))
