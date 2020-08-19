(ns drtest.step
  "Defines multimethod and implementations for step definitions.
  A single step execution is the execute multimethod which dispatches
  on the type of the first argument (step-descriptor).

  If the step-descriptor is a map, the invocation will use :drtest.step/type key
  as the value.

  The first argument is the step descriptor. The second argument
  is a context map which can carry any user defined values.
  The third and fourth arguments are ok/fail callbacks.

  If the step succeeds it will call ok callback with the new context map.
  If the step fails it will call fail callback with the error and optional
  error data map."

  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            ["react-dom/test-utils" :as test-utils]
            [clojure.string :as str]))


(defn ^{:arglists '([type label? & keys-and-values])} step
  [type & args]
  (let [label (when (string? (first args))
                (first args))
        keys-and-values (if label
                          (rest args)
                          args)]
    (when-not (keyword? type)
      (throw (ex-info "Type must be a keyword" {:type type})))
    (when-not (even? (count keys-and-values))
      (throw (ex-info "Uneven number of keys/values" {:count (count keys-and-values)})))
    (merge {::type type}
           (when label
             {::label label})
           (into {}
                 (map vec)
                 (partition 2 keys-and-values)))))

(defn- resolve-ctx
  "Resolve values in step descriptor with current context.
  If value is a function call it with the current context.
  Otherwise use value as is."
  ([step-descriptor ctx]
   (resolve-ctx step-descriptor ctx (constantly true)))
  ([step-descriptor ctx keys-to-resolve]
   (into {}
         (map (fn [[key val :as kv]]
                (if (and (keys-to-resolve key)
                         (or (fn? val)
                             (keyword? val)))
                  [key (val ctx)]
                  kv)))
         step-descriptor)))

(defn valid-step? [step-descriptor]
  (or (fn? step-descriptor)
      (and (map? step-descriptor)
           (contains? step-descriptor ::type))))

(defn- descriptor-dispatch [step-descriptor]
  (cond
    (fn? step-descriptor) ::fn
    :else (::type step-descriptor)))

(defmulti execute
  "Execute a single test step. "
  (fn [step-descriptor ctx ok fail]
    (descriptor-dispatch step-descriptor)))

(defmulti step-defaults
  "Per step defaults"
  (fn [step-descriptor]
    (descriptor-dispatch step-descriptor)))

(defmethod step-defaults :default [_]
  nil)

(defmethod execute ::fn [step-fn ctx ok fail]
  (try
    (let [result (step-fn ctx)]
      (cond
        (false? result)
        (fail "Step function returned false" {})

        (true? result)
        (ok ctx)

        (map? result)
        (ok ctx)

        :else
        (fail "Step function returned invalid result. Expected boolean result or new context map."
              {:result result})))
    (catch js/Error e
      (fail (str "Error in function step: " (.-message e)) {:error e
                                                            :fn step-fn}))))

(defmethod execute :default [step-descriptor ctx ok fail]
  (fail "Can't execute step, unrecognized step-descriptor type."
        {:step-descriptor step-descriptor}))

(defmethod execute :render [step-descriptor ctx ok fail]
  (let [{:keys [component container]} (resolve-ctx step-descriptor ctx)]
    (if-not (vector? component)
      (fail "Invalid :component, expected a reagent component vector."
            {:component component})
      (let [c (or container
                  (.appendChild js/document.body
                                (.createElement js/document "div")))]
        (try
          (rdom/render component c
                       #(ok (merge ctx {::container c
                                        ::container-created? (nil? container)})))
          (catch js/Error e
            (fail (str "Render failed: " (.-message e))
                  {:error e})))))))

(defmethod execute ::cleanup [_ {::keys [container container-created?] :as ctx} ok fail]
  (when container-created?
    (.removeChild js/document.body container))
  (ok ctx))

(defn- check-attributes
  "Return map of all attributes that don't have the expected value."
  [attributes element]
  (reduce (fn [acc [attr expected-val]]
            (let [actual-val (.getAttribute element (if (keyword? attr)
                                                      (name attr)
                                                      attr))]
              (if (not= (str expected-val) (str actual-val))
                (assoc acc attr actual-val)
                acc)))
          {}
          attributes))

(defn- text-found [expected-text txt]
  (cond
    (instance? js/RegExp expected-text)
    (boolean (re-matches expected-text txt))

    (string? expected-text)
    (str/includes? txt expected-text)

    :else (throw (ex-info "Expected text must be string or regular expression."
                          {:expected-text expected-text}))))


(defn- with-selector [selector c timeout ok fail]
  (if-not (string? selector)
    (fail "Selector must be a string" {:selector selector})
    (let [start (.getTime (js/Date.))
          timeout (or timeout 2000)]
      ((fn find [] (if-let [element (.querySelector c selector)]
                      (ok element)
                      (let [now (.getTime (js/Date.))]
                        (if (>= (- now start) timeout)
                          (fail "Expected element was not found within timeout limit."
                                {:selector selector
                                 :timeout timeout})
                          (.setTimeout js/window find 50)))))))))

;; Expect an element with selector to be present
(defmethod execute :expect [step-descriptor ctx ok fail]
  (let [{:keys [selector attributes text value as timeout]} (resolve-ctx step-descriptor ctx)
        c (::container ctx)]
    (try
      (with-selector selector c timeout
        (fn [element]
          (if-not element
            (fail "Expected element was not found" {:selector selector})
            (let [wrong-attributes (and attributes (check-attributes attributes element))]
              (if (seq wrong-attributes)
                (fail "Element did not have expected attribute values"
                      {:expected-attributes attributes
                       :wrong-attributes wrong-attributes})
                (if (and text (not (text-found text (.-innerText element))))
                  (fail "Element did not have expected text"
                        {:expected-text text
                         :text (.-innerText element)})
                  (if (and value (not (text-found value (.-value element))))
                    (fail "Element did not have expected value"
                          {:expected-value value
                           :value (.-value element)})
                    (ok (if as
                          (assoc ctx as element)
                          ctx))))))))
        fail)
      (catch js/Error e
        (fail "Error in :expect step." {:step-descriptor step-descriptor})))))

(defmethod execute :expect-no [step-descriptor ctx ok fail]
  (let [{:keys [selector]} (resolve-ctx step-descriptor ctx)]
    (if (.querySelector (::container ctx) selector)
      (fail "Expected element to not be present, but it was." {:selector selector})
      (ok ctx))))

(defmethod execute :expect-count [step-descriptor ctx ok fail]
  (let [{:keys [selector count as]} (resolve-ctx step-descriptor ctx #(not= % :as))]
    (if-not (number? count)
      (fail "Expected element count must be specified as :count key." {:count count})
      (if-not (string? selector)
        (fail "Expected string :selector" {:selector selector})
        (let [qs (-> ctx ::container (.querySelectorAll selector))
              actual-count (.-length qs)]
          (if (not= actual-count count)
            (fail "Unexpected number of elements" {:expected-count count
                                                   :actual-count actual-count
                                                   :selector selector})
            (ok (if as
                  (assoc ctx as
                         (vec (for [i (range (.-length qs))]
                                (.item qs i))))
                  ctx))))))))

(defn with-element [step-descriptor ctx fail func]
  (let [{:keys [selector element in]} (resolve-ctx step-descriptor ctx)]
    (if (and (nil? element)
             (not (string? selector)))
      (fail "No element or string selector specified" {:selector selector
                                                       :element element})
      (if-let [elt (or element
                       (.querySelector (or in (::container ctx)) selector))]
        (func elt)
        (fail "Can't find element." {:selector selector})))))

;; Click on an element
(defmethod execute :click [step-descriptor ctx ok fail]
  (with-element step-descriptor ctx fail
    (fn [elt]
      (if (.hasAttribute elt "disabled")
        (fail "Can't click, element is disabled." {:element elt})
        (do
          (.click elt)
          (r/after-render #(ok ctx)))))))

(defmethod step-defaults :click [_]
  {::wait-render? true})

(def simulate-change (aget test-utils "Simulate" "change"))

;; Type text into an element
(defmethod execute :type [step-descriptor ctx ok fail]
  (with-element step-descriptor ctx fail
    (fn [elt]
      (let [{:keys [text overwrite?]} (resolve-ctx step-descriptor ctx #{:text})]
        (try
          (set! (.-value elt) (if overwrite?
                                text
                                (str (.-value elt) text)))
          (simulate-change elt)
          (r/after-render #(ok ctx))
          (catch js/Error e
            (fail (str "Exception in :type step: " (.-message e)) {:error e})))))))

(defmethod step-defaults :type [_]
  {::wait-render? true})

(defmethod execute :wait [step-descriptor ctx ok fail]
  (let [{:keys [ms]} (resolve-ctx step-descriptor ctx)]
    (if-not (number? ms)
      (fail "Wait time must be specified as a number in :ms" {:ms ms})
      (.setTimeout js/window #(ok ctx) ms))))

(defmethod execute :wait-promise [step-descriptor ctx ok fail]
  (let [{:keys [promise as]} (resolve-ctx step-descriptor ctx)]
    (if-not (instance? js/Promise promise)
      (fail "Expected a Promise instance" {:promise promise})
      (-> promise
          (.then (fn [val]
                   (ok (if as
                         (assoc ctx as val)
                         ctx))))
          (.catch (fn [reason]
                    (fail "Promise was rejected" {:promise promise
                                                  :reason reason})))))))
