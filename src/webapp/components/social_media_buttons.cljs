(ns webapp.components.social-media-buttons
  (:require ["@mantine/core" :refer [ActionIcon Group Tooltip]]
            ["react-icons/fa6" :refer [FaFacebookF FaLinkedinIn FaXTwitter FaLink]]))

(defn current-share-url []
  (.-href js/location))

(defn open-share! [base-url]
  (let [target (str base-url (js/encodeURIComponent (current-share-url)))]
    (.open js/window target "_blank" "noopener,noreferrer")))

(defn copy-link! []
  (let [url (current-share-url)
        clipboard (some-> js/navigator .-clipboard)]
    (if (fn? (some-> clipboard .-writeText))
      (.writeText clipboard url)
      (do
        (.prompt js/window "Copy link:" url)
        nil))))

(defn social-media-buttons [{:keys [saga-name]}]
  [:> Group {:className "detail-share"
             :gap "xs"
             :align "center"}
   [:> Group {:className "share-actions"
              :gap "xs"}
    [:> Tooltip {:label "Share on Facebook"}
     [:> ActionIcon
      {:className "share-icon-btn share-facebook"
       :aria-label "Share on Facebook"
       :variant "subtle"
       :radius "xl"
       :onClick #(open-share! "https://www.facebook.com/sharer/sharer.php?u=")}
      [:> FaFacebookF]]]
    [:> Tooltip {:label "Share on LinkedIn"}
     [:> ActionIcon
      {:className "share-icon-btn share-linkedin"
       :aria-label "Share on LinkedIn"
       :variant "subtle"
       :radius "xl"
       :onClick #(open-share! "https://www.linkedin.com/sharing/share-offsite/?url=")}
      [:> FaLinkedinIn]]]
    [:> Tooltip {:label "Share on X"}
     [:> ActionIcon
      {:className "share-icon-btn share-x"
       :aria-label "Share on X"
       :variant "subtle"
       :radius "xl"
       :onClick #(let [url (js/encodeURIComponent (current-share-url))
                        text (js/encodeURIComponent (str "Check out this " saga-name " frame"))]
                    (.open js/window
                           (str "https://twitter.com/intent/tweet?url=" url "&text=" text)
                           "_blank"
                           "noopener,noreferrer"))}
      [:> FaXTwitter]]]
    [:> Tooltip {:label "Copy link"}
     [:> ActionIcon
      {:className "share-icon-btn share-copy"
       :aria-label "Copy link"
       :variant "subtle"
       :radius "xl"
       :onClick copy-link!}
      [:> FaLink]]]]])
