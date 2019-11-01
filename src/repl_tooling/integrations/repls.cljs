(ns repl-tooling.integrations.repls
  (:require [repl-tooling.repl-client.connection :as connection]
            [clojure.string :as str]
            [clojure.core.async :include-macros true :as async]
            [repl-tooling.repl-client.source :as source]
            [repl-tooling.repl-client.clojure :as clj]
            [repl-tooling.eval :as eval]))

;; Detection

(defn- detect-output-kind [row chan]
  ; FIXME: Detect closed port here
  (when-let [row-kind (re-find #":using-(.*)-repl" (str row))]
    (async/put! chan (keyword (second row-kind)))))

(defn connect-and-detect! [host port]
  (. (connection/connect! host port)
    then
    #(let [{:keys [conn buffer]} %
           kind-chan (async/promise-chan)]
       (.write conn "\n")
       (.write conn (str "#?(:cljs :using-cljs-repl :clj :using-clj-repl "
                         ":cljr :using-cljr-repl "
                         ":joker :using-joker-repl "
                         ":bb :using-bb-repl)\n"))
       (.write conn ":using-unknown-repl\n")
       {:conn conn
        :control (connection/treat-buffer!
                  buffer (fn [out] (detect-output-kind out kind-chan)) identity)
        :repl-kind (js/Promise. (fn [resolve] (-> kind-chan async/<! resolve async/go)))})))

;; REPLs
(defn add-to-eval-queue [command opts callback pending-evals eval-cmd]
  (let [command (source/parse-command command true)
        id (or (:id opts) (gensym))]
    (if-let [result (:result command)]
      (let [pending (assoc opts :command result :callback callback :id id)]
        (swap! pending-evals assoc id pending)
        (eval-cmd pending))
      (callback command))
    id))

(defrecord Generic [pending-evals eval-cmd]
  eval/Evaluator
  (evaluate [_ command opts callback]
    (add-to-eval-queue command opts callback pending-evals eval-cmd))
  (break [_ _]))

;; Integrations, at last
(defn capture-eval-result [pending-evals on-output result]
  (let [[id edn-result] result
        {:keys [callback pass ignore]} (get @pending-evals id)
        msg (merge pass edn-result)]
    (when-not ignore (on-output msg))
    (callback msg)))

(defn- send-command! [^js conn id cmd control ex-type]
  (let [command (source/wrap-command2 id cmd ex-type true)]
    (swap! control update :pending-evals conj id)
    (.write conn (:result command))))

(defn- send-namespace [^js conn ns-command namespace control]
  (when namespace
    (swap! control update :ignore-output conj #"^\n?.*?=> " #"nil\n")
    (.write conn (str "(" ns-command namespace ")"))))

(defn- instantiate-correct-evaluator [repl-kind ^js conn control on-output]
  (let [pending-evals (atom {})
        cmd! (fn [id command ex]
               (send-command! conn id command control ex))
        eval-command (case repl-kind
                       :bb (fn [{:keys [command id]}]
                             (cmd! id command "Exception"))
                       :joker (fn [{:keys [command namespace id]}]
                                (send-namespace conn "joker.core/ns " namespace control)
                                (let [command (str/replace-all (source/wrap-command id command "Error")
                                                               #"clojure\.core/"
                                                               "joker.core/")]
                                  (swap! control update :pending-evals conj id)
                                  (.write conn command)))
                       :cljs (fn [{:keys [command namespace id]}]
                               (send-namespace conn "in-ns '" namespace control)
                               (cmd! id command ":default"))
                       :cljr (fn [{:keys [command namespace id]}]
                               (send-namespace conn "in-ns '" namespace control)
                               (cmd! id command "System.Exception"))
                       (fn [{:keys [command namespace id]}]
                         (send-namespace conn "ns " namespace control)
                         (cmd! id command "Throwable")))]

    (if (= :clj repl-kind)
      (clj/prepare-unrepl-evaluator conn control on-output)
      (do
        (swap! control assoc :ignore-prompt true)
        (connection/prepare-evals control
                                  #(if-let [out %] (on-output {:out out}) (on-output nil))
                                  #(capture-eval-result pending-evals on-output %))
        (->Generic pending-evals eval-command)))))

(defonce connections (atom {}))
(defn connect-repl! [id host port on-output]
  (.. (connect-and-detect! host port)
      (then (fn [{:keys [conn control repl-kind]}]
              (prn :ONE)
              (swap! connections assoc id conn)
              (.then ^js repl-kind
                     (fn [kind]
                       [kind (instantiate-correct-evaluator kind conn control on-output)]))))))

(defn disconnect! [id]
  (when-let [conn ^js (get @connections id)]
    (.end conn)))
