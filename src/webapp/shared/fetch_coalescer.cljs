(ns webapp.shared.fetch-coalescer)

(defn create-coalesced-runner [task]
  (let [inflight?* (atom false)
        queued?* (atom false)]
    (letfn [(run []
              (if @inflight?*
                (do
                  (reset! queued?* true)
                  (js/Promise.resolve false))
                (do
                  (reset! inflight?* true)
                  (-> (js/Promise.resolve)
                      (.then (fn [] (task)))
                      (.finally
                       (fn []
                         (reset! inflight?* false)
                         (when @queued?*
                           (reset! queued?* false)
                           (run))))))))]
      run)))
