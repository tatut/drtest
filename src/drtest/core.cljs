(ns drtest.core
  "Declarative Reagent test runner."
  (:require [drtest.step :as ds]
            [cljs.test :refer [is] :include-macros true]
            [reagent.core :as r]))

(defn- step-info [step]
  (merge
   (ds/step-defaults step)
   (cond
     (map? step)
     step

     (fn? step)
     (merge {::ds/type ::ds/fn}
            (meta step))

     :else
     (throw (ex-info "Unrecognized step type" {:step step})))))

(defn- take-screenshot [step step-num step-count failed? then-fn]
  (let [{::ds/keys [label type]} (step-info step)
        div (.createElement js/document "div")]
    ;; Setup screenshot HUD display in screenshot
    (.setAttribute div "style"
                   (str "width: 100%; "
                        "height: 50px; "
                        "background-color: " (if failed? "red" "wheat") "; "
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
    (.appendChild div (.createTextNode js/document (str (if failed?
                                                          "Failed at step "
                                                          "After step ")
                                                        step-num " / " step-count ": "
                                                        (or label type))))
    (.then (js/screenshot)
           #(do
              ;; Remove HUD
              (.removeChild js/document.body div)

              ;; Continue with the tests
              (then-fn)))))

(defn- run-step* [{:keys [screenshots? done] :as opts} step-num step-count ctx [step & steps]]
  (let [{::ds/keys [label type screenshot? wait-render?]} (step-info step)
        take-screenshot? (if (some? screenshot?)
                           ;; If defined per step, use that
                           screenshot?

                           ;; Otherwise use common option
                           screenshots?)]
    (println "Running step: " (or label "<no label>") " (" type ")")
    (ds/execute step ctx
                ;; Ok callback => run next step
                (fn [ctx]
                  (is true (str "[OK] Step " (or label type)))
                  (let [cont (fn []
                               (if (seq steps)
                                 (let [next-step #(run-step* opts (inc step-num) step-count ctx steps)]
                                   (if wait-render?
                                     (do
                                       (r/force-update-all)
                                       (r/after-render next-step))
                                     (next-step)))
                                 (done)))]
                    (if take-screenshot?
                      (take-screenshot step step-num step-count false cont)
                      (cont))))

                ;; Fail callback => call done immediately
                (fn [error error-data]
                  (is false (str "[FAIL] " error "\n  " (pr-str error-data)))
                  (if screenshots?
                    (take-screenshot step step-num step-count true done)
                    (done))))))


(defn- check-options [{:keys [initial-context done] :as options}]
  (when-not (fn? done)
    (throw (ex-info ":done callback must be specified" {:done done})))
  (when-not (map? initial-context)
    (throw (ex-info ":initial-context map must be provided" {:initial-context initial-context}))))

(defn- check-steps [steps]
  (let [invalid-steps (remove ds/valid-step? steps)]
    (when (seq invalid-steps)
      (throw (ex-info (str "Found " (count invalid-steps) " invalid steps, check step descriptors.")
                      {:invalid-steps (vec invalid-steps)})))))

(defn- expand-steps [steps]
  (into []
        (mapcat (fn [step]
                  (if (or (seq? step)
                          (vector? step))
                    step
                    [step])))
        steps))

(defn run-steps [{:keys [screenshots? initial-context done] :as opts} & steps]
  (let [steps (expand-steps steps)]
    (try
      (check-options opts)
      (check-steps steps)
      (catch js/Object e
        (is false (str (ex-message e) ", info:\n" (pr-str (ex-data e))))
        (if done
          (done)
          (throw e))))
    (let [step-count (count steps)]
      (run-step* opts 1 step-count initial-context
                 (concat steps [{::ds/type ::ds/cleanup ::ds/label "Post test cleanup"}])))))
