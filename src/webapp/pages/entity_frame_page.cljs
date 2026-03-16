(ns webapp.pages.entity-frame-page
  "Generic frame detail page using unified entity model.
   Renders a single item (frame) with full editing and action capabilities."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [reagent.core :as r]
            [webapp.shared.controls :as controls]
            [webapp.shared.ui.back-button :as back-button]
            [webapp.shared.ui.frame-nav :as frame-nav]
            [webapp.shared.ui.interaction :as interaction]
            [webapp.components.item :as item]
            ["@mantine/core" :refer [Box Button Group IconButton Stack]]))

(defn navigate-to-frame [frame-id direction]
  "Navigate to next/previous frame in sequence."
  (let [active-id @(rf/subscribe [:active-frame-id])]
    (if (= direction :next)
      (if-let [next-id (frame-nav/adjacent-frame-id active-id 1)]
        (rf/dispatch [:set-active-frame next-id]))
      (if-let [prev-id (frame-nav/adjacent-frame-id active-id -1)]
        (rf/dispatch [:set-active-frame prev-id])))))

(defn entity-frame-page [{:keys [frame-id]}]
  "Render a frame detail page for a given frame entity.
   
   Props:
   - frame-id: The UUID of the frame to display"
  (let [frame @(rf/subscribe [:entity frame-id])
        image-status (get-in frame [:payload :imageStatus])
        image-ui @(rf/subscribe [:frame-image-ui frame-id])]
    
    (if (nil? frame)
      [:div.entity-frame-page
       [:p "Loading frame..."]]
      
      [item/item
       frame
       {:clickable? false
        :image-nav? true
        :image-fit "contain"
        :image-field :imageUrl
        :image-status image-status
        
        :on-click-edit (fn []
                        (rf/dispatch [:ui-entity-editing-start frame-id]))
        
        :on-save (fn [text]
                  (rf/dispatch [:entity-update frame-id {:description text}]))
        
        :on-generate (fn []
                      (rf/dispatch [:post-generate-frame 
                                    {:frame-id frame-id
                                     :direction nil
                                     :on-success [:entity-update frame-id]
                                     :on-failure [:state-failed]}]))
        
        :on-upload (fn []
                    (rf/dispatch [:open-upload-dialog frame-id]))
        
        :on-download (fn []
                      (when-let [url (get-in frame [:payload :imageUrl])]
                        (controls/download-image! url (str (:title frame) ".png"))))
        
        :on-delete (fn []
                    (when (js/confirm "Delete this frame?")
                      (rf/dispatch [:entity-delete frame-id])))
        
        :on-nav-left (fn []
                      (navigate-to-frame frame-id :prev))
        
        :on-nav-right (fn []
                       (navigate-to-frame frame-id :next))
        
        :on-image-load (fn []
                        (rf/dispatch [:frame-image-loaded frame-id]))
        
        :on-image-error (fn []
                         (rf/dispatch [:frame-image-error frame-id]))}])))

(defn entity-frame-page-view []
  "Main page component that extracts frame-id from route."
  (let [route @(rf/subscribe [:route])]
    (if-let [frame-id (or (:frame-id route) 
                         (:active-frame-id route))]
      [entity-frame-page {:frame-id frame-id}]
      [:div.entity-frame-page
       [:p "No frame specified in route."]])))
