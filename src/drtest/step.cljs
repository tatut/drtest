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
            ["react-dom/test-utils" :as test-utils]
            [clojure.string :as str]))


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
                         (fn? val))
                  [key (val ctx)]
                  kv)))
         step-descriptor)))

(defn valid-step? [step-descriptor]
  (or (fn? step-descriptor)
      (and (map? step-descriptor)
           (contains? step-descriptor ::type))))

(defmulti execute
  "Execute a single test step. "
  (fn [step-descriptor ctx ok fail]
    (cond
      (fn? step-descriptor) ::fn
      :else (::type step-descriptor))))

(defmethod execute ::fn [step-fn ctx ok fail]
  (try
    (let [result (step-fn ctx)]
      (if (map? result)
        (fail "Step function returned invalid context, not a map."
              {:result result})
        (ok result)))
    (catch js/Error e
      (fail (.-message e) {:error e}))))

(defmethod execute :default [step-decriptor ctx ok fail]
  (fail "Can't execute step, unrecognized step-descriptor type."
        {:step-descriptor step-descriptor}))

(defmethod execute :render [{:keys [component container]} ctx ok fail]
  (if-not (vector? component)
    (fail "Invalid :component, expected a reagent component vector."
          {:component component})
    (let [c (or container
                (.appendChild js/document.body
                              (.createElement js/document "div")))]
      (try
        (r/render component c)
        (ok {::container c
             ::container-created? (nil? container)})
        (catch js/Error e
          (fail (str "Render failed: " (.-message e))
                {:error e}))))))

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

(defn- text-found [expected-text element]
  (let [txt (.-innerText element)]
    (cond
      (instance? js/RegExp expected-text)
      (boolean (re-matches expected-text txt))

      (string? expected-text)
      (str/includes? txt expected-text)

      :else (throw (ex-info "Expected text must be string or regular expression."
                            {:expected-text expected-text})))))

;; Expect an element with selector to be present
(defmethod execute :expect [step-descriptor ctx ok fail]
  (let [{:keys [selector attributes text as]} (resolve-ctx step-descriptor ctx)
        c (::container ctx)]
    (if-not (string? selector)
      (fail "Selector must be a string" {:selector selector})
      (try
        (let [element (.querySelector c selector)]
          (if-not element
            (fail "Expected element was not found" {:selector selector})
            (let [wrong-attributes (and attributes (check-attributes attributes element))]
              (if (seq wrong-attributes)
                (fail "Element did not have expected attribute values"
                      {:expected-attributes attributes
                       :wrong-attributes wrong-attributes})
                (if-not (and text (not (text-found text element)))
                  (fail "Element did not have expected text"
                        {:expected-text text
                         :text (.-innerText element)})
                  (ok (if as
                        (assoc ctx as element)
                        ctx)))))))
        (catch js/Error e
          (fail "Error in :expect step." {:step-descriptor step-descriptor}))))))

(defn with-element [step-descriptor ctx fail func]
  (let [{:keys [selector element]} (resolve-ctx step-descriptor ctx)]
    (if (and (nil? element)
             (not (string? selector)))
      (fail "No element or string selector specified" {:selector selector
                                                       :element element})
      (if-let [elt (or element
                       (.querySelector (::container ctx) selector))]
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

;; Type text into an element
(defmethod execute :type [step-descriptor ctx ok fail]
  (with-element step-descriptor ctx fail
    (fn [elt]
      (let [{text :text} (resolve-ctx step-descriptor ctx #{:text})]
        (try
          (set! (.-value elt) (str (.-value elt) text))
          (js/ReactTestUtils.Simulate.change elt)
          (r/after-render #(ok ctx))
          (catch js/Error e
            (fail (str "Exception in :type step: " (.-message e)) {:error e})))))))
