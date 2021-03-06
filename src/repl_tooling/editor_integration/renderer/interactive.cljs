(ns repl-tooling.editor-integration.renderer.interactive
  (:require [reagent.core :as r]
            [reagent.dom :as rdom]
            [clojure.string :as str]
            [repl-tooling.eval :as eval]
            [repl-tooling.editor-integration.renderer.protocols :as proto]
            [repl-tooling.ui.pinkie :as pinkie]
            [sci.core :as sci]
            [repl-tooling.editor-integration.configs :as configs]))

(defn- edn? [obj]
  (or (number? obj)
      (string? obj)
      (coll? obj)
      (boolean? obj)
      (nil? obj)
      (regexp? obj)
      (symbol? obj)
      (keyword? obj)))

(defn- norm-evt [obj]
  (->> obj
       js/Object.getPrototypeOf
       js/Object.getOwnPropertyNames
       (map #(let [norm (-> %
                            (str/replace #"[A-Z]" (fn [r] (str "-" (str/lower-case r))))
                            keyword)]
               [norm (aget obj %)]))
       (filter (comp edn? second))
       (into {})))

(defn- run-evt-fun! [e fun state repl additional-args]
  (.preventDefault e)
  (.stopPropagation e)
  (.. (eval/eval repl
                 (str "(" fun " '"
                      (pr-str (norm-evt (.-target e)))
                      " '" (pr-str @state)
                      " " (->> additional-args (map #(str "'"(pr-str %))) (str/join " "))
                      ")")
                 {:ignore true})
      (then #(reset! state (:result %)))))

(defn- prepare-fn [fun state repl]
  (fn [ & args]
    (if (-> args first edn?)
      (fn [e] (run-evt-fun! e fun state repl args))
      (run-evt-fun! (first args) fun state repl []))))

(defn- bindings-for [state fns repl]
  (->> fns
       (map (fn [[f-name f-body]] [(->> f-name name (str "?") symbol)
                                   (prepare-fn f-body state repl)]))
       (into {'?state @state})))

(defn- treat-error [hiccup]
  (let [d (. js/document createElement "div")]
    (rdom/render hiccup d)
    hiccup))

(defn- render-interactive [{:keys [state html fns] :as edn} repl editor-state]
  (let [state (r/atom state)
        code (pr-str html)
        html (fn [state]
               (try
                 (-> {:code code
                      :bindings (bindings-for state fns repl)
                      :editor-state editor-state}
                     configs/evaluate-code
                     pinkie/tag-inject
                     treat-error)
                 (catch :default e
                   (.log js/console e)
                   [:div.error "Can't render this code - " (pr-str e)])))]
    [html state]))

(defrecord Interactive [edn repl editor-state]
  proto/Renderable
  (as-html [_ ratom _]
    (render-interactive edn repl editor-state)))
