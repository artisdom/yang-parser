(ns tst.parse.calc2
  (:use parse.core
        parse.transform
        tupelo.test
        clojure.test)
  (:require
    [clojure.data :as cd]
    [clojure.java.io :as io]
    [clojure.set :as set]
    [clojure.string :as str]
    [instaparse.core :as insta]
    [schema.core :as s]
    [tupelo.core :as t]
    [tupelo.enlive :as te]
    [tupelo.gen :as tgen]
    [tupelo.parse :as tp]
    [tupelo.schema :as tsk]
    [tupelo.string :as ts]
    [tupelo.x-forest :as tf]
    )
  (:import [java.util.concurrent TimeoutException]
           [java.util List]))
(t/refer-tupelo)

(def ^:dynamic *rpc-timeout-ms* 200)
(def ^:dynamic *rpc-delay-simulated-ms* 30)

(def rpc-schema-hiccup
  [:rpc
   [:identifier "add"]
   [:description [:string "Add 2 numbers"]]
   [:input
    [:leaf [:identifier "x"] [:type [:identifier "decimal64"]]]
    [:leaf [:identifier "y"] [:type [:identifier "decimal64"]]]]
   [:output
    [:leaf [:identifier "result"] [:type [:identifier "decimal64"]]]]])

(def rpc-msg-id (atom 100))
(def rpc-msg-id-map (atom {}))

(s/defn rpc-call :- s/Any
  [msg :- tsk/KeyMap]
  (let [rpc-msg-id     (swap! rpc-msg-id inc)
        msg            (update-in msg [:attrs] glue {:message-id rpc-msg-id
                                                     :xmlns      "urn:ietf:params:xml:ns:netconf:base:1.0"})
        result-promise (promise)]
    (swap! rpc-msg-id-map glue {rpc-msg-id result-promise})
    (future ; Simulate calling out to http server in another thread
      (try
        (Thread/sleep *rpc-delay-simulated-ms*) ; simulated network delay
        (with-spy-enabled :current
          (let
            [rpc-result        (validate-parse-rpc-enlive (tf/hiccup->enlive rpc-schema-hiccup) msg)
             ; rpc-result-tree   (spyx (validate-parse-rpc-tree (tf/hiccup->tree rpc-schema-hiccup)
             ; (tf/enlive->tree msg)))

             rpc-reply-msg-id  (fetch-in rpc-result [:attrs :message-id])
             fpc-reply-promise (grab rpc-reply-msg-id @rpc-msg-id-map)]
            (deliver fpc-reply-promise rpc-result)))
        (catch Exception e
          (deliver result-promise ; deliver any exception to caller
            (RuntimeException. (str "rpc-call-2: failed  msg=" msg \newline "  caused by=" (.getMessage e)))))))
    result-promise )) ; return promise to caller immediately

(defn add [x y]
  (let [result-promise (rpc-call
                         (tf/hiccup->enlive
                           [:rpc
                            [:add {:xmlns "my-own-ns/v1"}
                             [:x (str x)]
                             [:y (str y)]]]))
        rpc-result     (deref result-promise *rpc-timeout-ms* ::timeout-failure)
        _              (when (instance? Throwable rpc-result)
                         (throw (RuntimeException. (.getMessage rpc-result))))
        result         (te/get-leaf rpc-result [:rpc-reply :data])]
    (when (= ::timeout-failure rpc-result)
      (throw (TimeoutException. (format "Timeout Exceed=%s  add: %s %s; " *rpc-timeout-ms* x y))))
    result))

(dotest
  (binding [*rpc-timeout-ms*        200
            *rpc-delay-simulated-ms* 10]
    (reset! rpc-msg-id 100)
    (is (rel= 5 (add 2 3) :digits 9))))


;-----------------------------------------------------------------------------
(dotest
  (let [state       (atom {})
        yang-forest (tf/with-forest (tf/new-forest)
                      (let [abnf-src        (io/resource "yang3.abnf")
                            yp              (create-abnf-parser abnf-src)
                            yang-src        (slurp (io/resource "calc.yang"))
                            yang-tree       (yp yang-src)
                            yang-ast-hiccup (yang-transform yang-tree)
                            yang-hid        (tf/add-tree-hiccup yang-ast-hiccup)
                            ]
                        (reset! state (vals->map yang-hid))))]
    (tf/with-forest yang-forest
      (with-map-vals @state [yang-hid]
        (let [rpc-hid (tf/find-hid yang-hid [:module :rpc])]
          (tx-rpc rpc-hid)
          (is= (tf/hid->bush rpc-hid)
            [{:tag :rpc, :name :add}
             [{:tag :input}
              [{:tag :leaf, :type :decimal64, :name :x}]
              [{:tag :leaf, :type :decimal64, :name :y}]]
             [{:tag :output} [{:tag :leaf, :type :decimal64, :name :result}]]])
          (is= (rpc->api rpc-hid)
            '(fn fn-add [x y] (fn-add-impl x y)))
          (with-spy-enabled :default
            (is= (rpc-marshall rpc-hid [2 3])
              [:rpc [:add {:xmlns "my-own-ns/v1"} [:x "2"] [:y "3"]]]))

          )))))
