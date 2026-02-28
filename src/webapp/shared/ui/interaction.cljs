(ns webapp.shared.ui.interaction)

(def editable-selector
  "input, textarea, select, [contenteditable=''], [contenteditable='true'], [contenteditable]")

(def interactive-selector
  (str editable-selector
       ", button, a, [role='button'], [role='menuitem'], [role='option'], [data-ui-interactive='true']"))

(defn node->element [node]
  (cond
    (nil? node) nil
    (= 1 (.-nodeType node)) node
    (= 3 (.-nodeType node)) (.-parentElement node)
    :else nil))

(defn has-closest? [el]
  (fn? (some-> el .-closest)))

(defn closest? [el selector]
  (and (some? el)
       (has-closest? el)
       (some? (.closest el selector))))

(defn editable-target? [target]
  (let [el (node->element target)]
    (or (true? (some-> el .-isContentEditable))
        (closest? el editable-selector))))

(defn interactive-target? [target]
  (let [el (node->element target)]
    (or (editable-target? el)
        (closest? el interactive-selector))))

(defn interactive-child-event? [e]
  (let [target (node->element (.-target e))
        current (node->element (.-currentTarget e))
        interactive-el (and (some? target)
                            (has-closest? target)
                            (.closest target interactive-selector))]
    (and (some? interactive-el)
         (not= interactive-el current))))

(defn stop! [e]
  (.stopPropagation e))

(defn prevent! [e]
  (.preventDefault e))

(defn halt! [e]
  (prevent! e)
  (stop! e))
