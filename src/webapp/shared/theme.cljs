(ns webapp.shared.theme
  (:require ["@mui/material/styles" :refer [createTheme]]))

(def app-theme
  (createTheme
   #js {:palette
        #js {:mode "light"
             :primary #js {:main "#20639b"}
             :secondary #js {:main "#ff7a18"}
             :error #js {:main "#8b1e3f"}
             :background #js {:default "#ffe8c2"}}
        :shape #js {:borderRadius 12}
        :typography
        #js {:fontFamily "\"Avenir Next\",\"Trebuchet MS\",sans-serif"}}))
