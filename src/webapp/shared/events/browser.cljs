(ns webapp.shared.events.browser
  (:require [re-frame.core :as rf]))

(rf/reg-fx
 :set-hash
 (fn [hash]
   (let [loc js/location
         pathname (.-pathname loc)]
     (if (re-find #"/index\.html$" pathname)
       (set! (.-href loc) (str (.replace pathname "/index.html" "/") hash))
       (set! (.-hash loc) hash)))))
