(ns drtest.core
  "Declarative Reagent test runner."
  (:require [drtest.step :as ds]))

(defn- take-screenshot [{::ds/keys [label type] :as step-declaration} step-num step-count then-fn]
  ;; Setup screenshot HUD display in screenshot
  (let [div (.createElement js/document "div")]
    (.setAttribute div "style"
                   (str "width: 100%; "
                        "height: 50px; "
                        "background-color: wheat; "
                        "position: fixed; "
                        "bottom: 0px; "
                        "left: 0px; "
                        "z-index: 99999; "
                        "padding: 5px; "
                        "border: solid 1px black;"))
    (.appendChild js/document.body div)
    (let [prg (.createElement js/document "progress")]
      (.setAttribute prg "value" (str step-num))
      (.setAttribute prg "max" (str step-count))
      (.setAttribute prg "style" "width: 200px; height: 20px;")
      (.appendChild div prg))
    (.appendChild div (.createTextNode js/document (str "After step " step-num " / " step-count ": "
                                                        (or label type)))))
  (.then (js/screenshot)
         #(do
            ;; Remove HUD
            (.removeChild js/document.body div)

            ;; Continue with the tests
            (then-fn))))

(defn- run-step* [{:keys [screenshots? done] :as opts} step-num step-count ctx [step & steps]]
  (ds/execute step
              ;; Ok callback => run next step
              (fn [ctx]
                (is true (str "[OK] Step " (or (::ds/label) (::ds/type type))))
                (let [cont #(if (seq steps)
                              (run-step* opts (inc step-num) step-count ctx steps)
                              (done))]
                  (if screenshots?
                    (take-screenshot step step-num step-count cont)
                    (cont))))

              ;; Fail callback => call done immediately
              (fn [error & [error-data]]
                (is false (str "[FAIL] " error "\n  " (pr-str error-data)))
                (done))))


(defn- check-options [{:keys [initial-context done] :as options}]
  (when-not (fn? done)
    (throw (ex-info ":done callback must be specified" {:done done})))
  (when-not (map? initial-context)
    (throw (ex-info ":initial-context map must be provided" {:initial-context initial-context}))))

(defn run-steps [{:keys [screenshots? initial-context] :as opts} & steps]
  (check-options opts)
  (let [invalid-steps (remove ds/valid-step? steps)]
    (when (seq invalid-steps)
      (throw (ex-info (str "Found " (count invalid-steps) " invalid steps, check step descriptors.")
                      {:invalid-steps (vec invalid-steps)}))))
  (let [step-count (count steps)]
    (run-step* opts 1 step-count initial-context steps)))
