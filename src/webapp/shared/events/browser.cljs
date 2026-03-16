(ns webapp.shared.events.browser
  (:require [re-frame.core :as rf]))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (set! (.-hash js/location) hash)))
