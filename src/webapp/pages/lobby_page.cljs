(ns webapp.pages.lobby-page
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            ["@mantine/core" :refer [Button Group Select Stack Text Title]]))

(defn- read-files-in-order! [file-list]
  (let [files (array-seq file-list)]
    (letfn [(read-next! [remaining]
              (when-let [file (first remaining)]
                (let [reader (js/FileReader.)]
                  (set! (.-onload reader)
                        (fn [event]
                          (rf/dispatch [:lobby/add-staged-item
                                        {:label (.-name file)
                                         :image-url (.. event -target -result)
                                         :image-status "uploading"}])
                          (read-next! (rest remaining))))
                  (set! (.-onerror reader)
                        (fn [_]
                          (rf/dispatch [:lobby/add-empty-slot])
                          (read-next! (rest remaining))))
                  (.readAsDataURL reader file))))]
      (read-next! files))))

(defn- staged-item-card [item idx total]
  (let [{:keys [id image-url label]} item
        title (or label
                  (when (seq image-url) (str "Image " (inc idx)))
                  (str "Slot " (inc idx)))
        subtitle (if (seq image-url)
                   (str "Sequence position " (inc idx))
                   "Intentional missing image or failed import")]
    [:article.frame
     [:div.frame-main
      [:div.media-shell
       (if (seq image-url)
         [:img {:className "frame-image"
                :src image-url
                :alt (or label (str "Lobby item " (inc idx)))}]
         [:div.placeholder-img
          [:div.placeholder-text "Empty slot"]])]
      [:div.meta
       [:div.subtitle-display.db-text-description-row
        [:div.subtitle-display-text
         [:div.chapter-name title]
         [:div.chapter-description subtitle]]]
        [:> Group {:gap "xs" :justify "center"}
         [:> Button {:size "xs"
                     :variant "default"
                     :disabled (zero? idx)
                     :onClick #(rf/dispatch [:lobby/move-staged-item id -1])}
          "Up"]
         [:> Button {:size "xs"
                     :variant "default"
                     :disabled (>= idx (dec total))
                     :onClick #(rf/dispatch [:lobby/move-staged-item id 1])}
          "Down"]
         [:> Button {:size "xs"
                     :color "red"
                     :variant "light"
                     :onClick #(rf/dispatch [:lobby/remove-staged-item id])}
          "Remove"]]]]]))

(defn lobby-page-view []
  (r/with-let [input-ref (atom nil)
               synced-target* (r/atom ::unset)]
    (let [route @(rf/subscribe [:route])
          target-id @(rf/subscribe [:lobby-target-id])
          source-name @(rf/subscribe [:lobby-source-name])
          items @(rf/subscribe [:lobby-items])
          target-options @(rf/subscribe [:lobby-target-options])
          route-target-id (:target-id route)]
      (when (not= route-target-id @synced-target*)
        (reset! synced-target* route-target-id)
        (when (some? route-target-id)
          (rf/dispatch [:lobby/set-target route-target-id])))
      [:> Stack {:gap "md" :className "search-page"}
       [:div.collection-header
        [:div.chapter-header-body
         [:> Title {:order 2} "Lobby"]
         [:> Text
          "Stage ordered images here, then normalize them into an existing robogene sequence."]]]
       [:div.chapter-header
        [:div.chapter-header-main
         [:div.collection-search-input
          [:label.dir-label {:htmlFor "lobby-source"} "Source"]
          [:> Select {:id "lobby-source"
                      :value source-name
                      :data (clj->js [{:value "chatgpt" :label "ChatGPT"}
                                      {:value "gemini" :label "Gemini"}
                                      {:value "grok" :label "Grok"}
                                      {:value "other" :label "Other"}])
                      :allowDeselect false
                      :onChange #(rf/dispatch [:lobby/set-source %])}]]
         [:div.collection-search-input
          [:label.dir-label {:htmlFor "lobby-target"} "Target sequence"]
          [:> Select {:id "lobby-target"
                      :placeholder "Choose chapter or character"
                      :value target-id
                      :data (clj->js target-options)
                      :searchable true
                      :clearable false
                      :nothingFoundMessage "No supported sequences yet"
                      :onChange #(rf/dispatch [:lobby/set-target %])}]]
         [:div.upload-toolbar
          [:input {:ref #(reset! input-ref %)
                   :type "file"
                   :multiple true
                   :accept "image/*"
                   :style {:display "none"}
                   :onChange (fn [event]
                               (let [files (.. event -target -files)]
                                 (when (pos? (.-length files))
                                   (read-files-in-order! files))
                                 (set! (.. event -target -value) "")))}]
          [:> Button {:variant "filled"
                      :onClick #(some-> @input-ref .click)}
           "Add images"]
          [:> Button {:variant "default"
                      :onClick #(rf/dispatch [:lobby/add-empty-slot])}
           "Add empty slot"]
          [:> Button {:variant "light"
                      :color "red"
                      :disabled (empty? items)
                      :onClick #(rf/dispatch [:lobby/clear])}
           "Clear"]
          [:> Button {:variant "filled"
                      :disabled (or (empty? items)
                                    (str/blank? (or target-id "")))
                      :onClick #(rf/dispatch [:lobby/import])}
           "Import to sequence"]]]]
       (if (seq items)
         [:div.gallery
          (map-indexed (fn [idx item]
                         ^{:key (:id item)}
                         [staged-item-card item idx (count items)])
                       items)]
         [:div.status
          [:p "No staged items yet."]
          [:p "Add images in order, or insert empty slots where an image is missing."]])])))
