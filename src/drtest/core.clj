(ns drtest.core
  "Convenience macros")

(defmacro define-drtest [name options & steps]
  `(cljs.test/deftest ~name
     (cljs.test/async
      done#

      (try
        (drtest.core/run-steps
         (merge ~options
                {:done done#})

         ~@steps)

        (catch js/Error e#
          (cljs.test/is false (str "Error in test: " (.-message e#)))
          (done#))))))
