(ns repl-tooling.editor-integration.connection
  (:require [reagent.core :as r]
            [promesa.core :as p]
            [clojure.string :as str]
            [repl-tooling.editor-helpers :as helpers]
            [repl-tooling.eval :as eval]
            [repl-tooling.repl-client.clojure :as clj-repl]
            [repl-tooling.editor-integration.evaluation :as e-eval]
            [repl-tooling.editor-integration.autocomplete :as autocomplete]
            [repl-tooling.integrations.repls :as repls]
            [repl-tooling.editor-integration.renderer :as renderer]
            [repl-tooling.editor-integration.schemas :as schemas]
            [repl-tooling.repl-client.nrepl :as nrepl]
            [repl-tooling.commands-to-repl.all-cmds :as cmds]
            [schema.core :as s]
            [paprika.schemas :as schema :include-macros true]))

; FIXME: This only here because of tests
(defn disconnect!
  "Disconnect all REPLs. Indempotent."
  []
  (repls/disconnect! :clj-eval)
  (repls/disconnect! :clj-aux)
  (repls/disconnect! :cljs-aux)
  (repls/disconnect! :cljs-eval))

(s/defn result-for-renderer
  [res :- schemas/EvalResult,
   state
   {:keys [filename]} :- {:filename s/Str, s/Any s/Any}
   {:keys [get-config]}]
  (let [repl (if (e-eval/need-cljs? (get-config) filename)
               (:cljs/repl @state)
               (:clj/repl @state))]
    (renderer/parse-result (:result res) repl state)))

(defn- features-for [state {:keys [editor-data] :as opts} _repl-kind]
  {:autocomplete #(p/let [data (editor-data)]
                    (autocomplete/command state opts data))
   :eval-and-render (fn eval-and-render
                      ([code range] (eval-and-render code range nil))
                      ([code range pass]
                       (p/let [data (editor-data)]
                         (cmds/eval-range state
                                          data
                                          (assoc opts :pass pass)
                                          (constantly [range code])))))
   :eval (fn [code eval-opts] (e-eval/eval-with-promise state opts code eval-opts))
   :result-for-renderer #(p/let [data (editor-data)]
                           (result-for-renderer % state data opts))})

(def ^:private default-opts
  {:on-start-eval identity
   :open-editor identity
   :get-rendered-results (constantly [])
   :on-eval identity
   :on-result identity
   :on-stdout identity
   :on-stderr identity
   :editor-data identity
   :notify identity
   :get-config identity ;FIXME
   :prompt (fn [ & _] (js/Promise. (fn [])))})

(defn connect-evaluator!
  ""
  [evaluators opts]
  (js/Promise.
   (fn [resolve]
     (let [state (atom evaluators)
           options (merge default-opts opts)]

       ; TODO: Check this last parameter
       (swap! state assoc
              :editor/commands (cmds/all state options :clj)
              :editor/features (features-for state options :clj))
       (resolve state)))))

(defn- tr-kind [kind]
  (let [kinds {:clj "Clojure"
               :cljs "ClojureScript"
               :cljr "ClojureCLR"
               :clje "Clojerl"
               :bb "Babaska"}]
    (kinds kind (-> kind name str/capitalize))))

(defn- prepare-cljs [primary host port state options]
  (swap! state merge {:cljs/repl primary
                      :repl/info {:host host :port port :kind :cljs :kind-name (tr-kind :cljs)}
                      :editor/commands (cmds/all state options :cljs)
                      :editor/features (features-for state options :cljs)}))

(defn- prepare-joker [primary host port state options]
  (swap! state merge {:clj/repl primary
                      :clj/aux primary
                      :repl/info {:host host :port port
                                  :kind :joker :kind-name (tr-kind :joker)}
                      :editor/commands (cmds/all state options :joker)
                      :editor/features (features-for state options :joker)}))

(defn- prepare-generic [primary aux host port state options kind]
  (when (= :clj kind)
    (eval/evaluate aux ":aux-connected" {:ignore true} #(clj-repl/disable-limits! aux)))

  (swap! state merge {:clj/aux aux
                      :clj/repl primary
                      :repl/info {:host host :port port :kind kind :kind-name (tr-kind kind)}
                      :editor/commands (cmds/all state options kind)
                      :editor/features (features-for state options kind)}))

(defn- connection-error! [error notify]
  (disconnect!)
  (if (= "ECONNREFUSED" error)
    (notify {:type :error
             :title "REPL not connected"
             :message (str "Connection refused. Ensure that you have a "
                           "Socket REPL started on this host/port")})
    (do
      (notify {:type :error
               :title "REPL not connected"
               :message (str "Unknown error while connecting to the REPL: "
                             error)})
      (.error js/console error)))
  nil)

(defn- callback-fn [state callbacks output]
  (let [{:keys [on-stdout on-stderr on-result on-disconnect on-patch]} callbacks]
    (when (nil? output)
      (cmds/handle-disconnect! state)
      (on-disconnect))
    (when-let [out (:out output)] (and on-stdout (on-stdout out)))
    (when-let [out (:err output)] (and on-stderr (on-stderr out)))
    (when (and on-result (or (contains? output :result)
                             (contains? output :error)))
      (on-result (helpers/parse-result output)))
    (when-let [patch (:patch output)]
      (on-patch (update patch :result helpers/parse-result)))))

(defn- find-patch [id maybe-coll]
  (let [elem (if (instance? reagent.ratom/RAtom maybe-coll)
               (dissoc @maybe-coll :editor-state :repl)
               maybe-coll)]
    (if (and (instance? renderer/Patchable elem)
             (= id (:id elem)))
      maybe-coll
      (when (coll? elem)
        (->> elem
             (map #(find-patch id %))
             flatten
             (filter identity))))))

(defn- prepare-patch [{:keys [on-patch get-rendered-results] :as callbacks}]
  (if on-patch
    callbacks
    (assoc callbacks
           :on-patch (fn [{:keys [id result]}]
                       (doseq [patchable (find-patch id (get-rendered-results))]
                         (swap! patchable assoc :value
                                (renderer/parse-result result
                                                       (:repl @patchable)
                                                       (:editor-state @patchable))))))))

; Config Options:
; {:project-paths [...]
;  :eval-mode (enum :clj :cljs :prefer-clj :prefer-cljs)}
(schema/defn-s connect!
  "Connects to a clojure-like REPL that supports the socket REPL protocol.
Expects host, port, and some callbacks:
* on-start-eval -> a function that'll be called when an evaluation starts
* on-eval -> a function that'll be called when an evaluation ends
* editor-data -> a function that'll be called when a command needs editor's data.
  Editor's data is a map (or a promise that resolves to a map) with the arguments:
    :contents - the editor's contents.
    :filename - the current file's name. Can be nil if file was not saved yet.
    :range - a vector containing [[start-row start-col] [end-row end-col]], representing
      the current selection
* open-editor -> asks the editor to open an editor. Expects a map with `:filename`,
  `:line` and maybe `:contents`. If there's `:contents` key, it defines a \"virtual
  file\" so it's better to open up an read-only editor
* notify -> when something needs to be notified, this function will be called with a map
  containing :type (one of :info, :warning, or :error), :title and :message
* get-config -> when some function needs the configuration from the editor, this fn
  is called without arguments. Need to return a map with the config options.
* get-rendered-results -> gets all results that are rendered on the editor. This is
  used so that the REPL can 'patch' these results when new data appears (think
  of resolving promises in JS)
* on-patch -> patches the result. Optional, if you send a :get-rendered-results
  callback, one will be generated for you
* prompt -> when some function needs an answer from the editor, it'll call this
  callback passing :title, :message, and :arguments (a vector that is composed by
  :key and :value). The callback needs to return a `Promise` with one of the
  :key from the :arguments, or nil if nothing was selected.
* on-copy -> a function that receives a string and copies its contents to clipboard
* on-stdout -> a function that receives a string when some code prints to stdout
* on-stderr -> a function that receives a string when some code prints to stderr
* on-result -> returns a clojure EDN with the result of code
* on-disconnect -> called with no arguments, will disconnect REPLs. Can be called more
than once

Returns a promise that will resolve to a map with two repls: :clj/aux will be used
to autocomplete/etc, :clj/repl will be used to evaluate code."
  [host :- s/Str
   port :- s/Int
   {:keys [notify] :as opts} :- s/Any]
  (p/catch
   (p/let [options (-> default-opts (merge opts) prepare-patch)
           state (r/atom {:editor/callbacks options})
           callback (partial callback-fn state options)
           [kind primary] (repls/connect-repl! :clj-eval host port callback)
           _ (eval/eval primary "1234")
           _ (case kind
               :cljs (prepare-cljs primary host port state options)
               :joker (prepare-joker primary host port state options)
               (p/let [[_ aux] (repls/connect-repl! :clj-aux host port callback)]
                 (prepare-generic primary aux host port state options kind)))
           nrepl? (instance? nrepl/Evaluator primary)]
     (notify {:type :info :title (str (tr-kind kind)
                                      (if nrepl? " nREPL" " socket REPL")
                                      " Connected")})
     state)
   #(connection-error! % notify)))
