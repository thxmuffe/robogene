(ns webapp.shared.events.browser
  (:require [re-frame.core :as rf]))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (let [loc js/location]
     (if (and (not= "/" (.-pathname loc))
              (not= "" (.-pathname loc)))
       (set! (.-href loc) (str "/" hash))
       (set! (.-hash loc) hash)))))
