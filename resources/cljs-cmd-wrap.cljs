(clojure.core/let [tooling$norm$jsbeam
                   (clojure.core/fn [js-obj]
                     (clojure.core/tagged-literal
                      'unrepl/browsable
                      [(if (clojure.core/= js/Function (type js-obj))
                         (let [splitted (-> js-obj .-name cljs.core/demunge
                                            (clojure.string/split #"/"))]
                           (clojure.core/tagged-literal 'unrepl/bad-symbol
                                                          [(->> splitted
                                                                butlast
                                                                (clojure.string/join ".")
                                                                not-empty)
                                                           (clojure.core/str
                                                            (clojure.core/last splitted)
                                                            " (function)")]))
                         (clojure.core/tagged-literal 'unrepl/bad-symbol [nil (pr-str js-obj)]))
                       {:repl-tooling/... `(quote
                                             ~(->> js-obj
                                                  js/Object.getPrototypeOf
                                                  js/Object.getOwnPropertyNames
                                                  (clojure.core/concat (js/Object.getOwnPropertyNames js-obj))
                                                  distinct
                                                  sort
                                                  (clojure.core/map #(clojure.core/symbol (str "." %)))))}]))

                   res-fn
                   (clojure.core/fn [res change-keywords?]
                     (clojure.core/cond
                       (clojure.core/instance? cljs.core/ExceptionInfo res)
                       (clojure.core/tagged-literal 'error
                         {:type "cljs.core.ExceptionInfo"
                          :data (.-data res)
                          :message (.-message res)
                          :trace (clojure.core/->> res .-stack clojure.string/split-lines)})

                       (clojure.core/instance? js/Error res)
                       (clojure.core/tagged-literal 'error
                         {:type (.-name res)
                          :message (.-message res)
                          :trace (clojure.core/->> res .-stack clojure.string/split-lines)})

                       (clojure.core/symbol? res)
                       (clojure.core/symbol (clojure.core/str "#unrepl/bad-symbol [nil "
                                                 (clojure.core/pr-str (clojure.core/str res))
                                                 "]"))

                       (clojure.core/and change-keywords? (clojure.core/keyword? res))
                       (clojure.core/symbol (clojure.core/str "#unrepl/bad-keyword ["
                                                 (clojure.core/pr-str (clojure.core/namespace res)) " "
                                                 (clojure.core/pr-str (clojure.core/name res))
                                                 "]"))

                       (clojure.core/keyword? res) res
                       (clojure.core/= nil res) res
                       (clojure.core/boolean? res) res
                       (clojure.core/number? res) res
                       (clojure.core/string? res) res
                       (clojure.core/regexp? res) res
                       (clojure.core/coll? res) res
                       :else (tooling$norm$jsbeam res)))]
  (try
    (clojure.core/let [res (do __COMMAND__)]
      [:result (clojure.core/pr-str
                (if (clojure.core/record? res)
                  (clojure.walk/postwalk (clojure.core/fn [a] (res-fn a false)) res)
                  (if (clojure.core/coll? res)
                    (clojure.walk/postwalk (clojure.core/fn [a] (res-fn a true)) res)
                    (res-fn res true))))])
    (catch :default e [:error (clojure.core/pr-str (res-fn e true))])))