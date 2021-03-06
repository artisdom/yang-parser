(ns parse.core
  (use parse.transform)
  (:require
    [instaparse.core :as insta]
    [schema.core :as s]
    [tupelo.core :as t]
    [tupelo.forest :as tf]
    [tupelo.parse :as tp]
    [tupelo.schema :as tsk] ))
(t/refer-tupelo)

(def rpc-msg-id-map (atom {}))
(def rpc-msg-id (atom 100))

(defn reset-rpc-msg-id [val] (reset! rpc-msg-id val))
(defn next-rpc-msg-id [] (swap! rpc-msg-id inc))

(defn instaparse-failure? [arg] (instance? instaparse.gll.Failure arg))

(def type-marshall-map {:decimal64 str
                        :int64     str
                        :string    str})

; #todo maybe use ns & vars like:
; #todo:   parse.unmarshall/decimal64
; #todo:   parse.unmarshall/int64
; #todo:   parse.unmarshall/string
(def type-unmarshall-map {:decimal64 tp/parse-double
                          :int64     tp/parse-long
                          :string    str})

(def rpc-fn-map {:add  (fn fn-add [& args] (apply + args))
                 :mult (fn fn-mult [& args] (apply * args))
                 :mul3 (fn fn-mul3 [& args] (apply * args))
                 :pow  (fn fn-power [x y] (Math/pow x y))})

(defn validate-parse-leaf-tree
  "Validate & parse a leaf msg value given a leaf arg-schema (Enlive-format)."
  [arg-schema arg-val]
  (let
    [arg-name-schema (fetch-in arg-schema [:tag])
     arg-type-schema (fetch-in arg-schema [:type])
     arg-name-val    (fetch-in arg-val [:tag])
     >>              (assert (= arg-name-schema arg-name-val))
     ; #todo does not yet verify any attrs;  what rules?
     parser-fn       (grab arg-type-schema type-unmarshall-map)
     parsed-value    (parser-fn (grab ::tf/value arg-val))]
    parsed-value)

  #_(try
      (catch Exception e
        (throw (RuntimeException. (str "validate-parse-arg-val: failed for arg-schema=" arg-schema \newline
                                    "  arg-val=" arg-val \newline
                                    "  caused by=" (.getMessage e))))))
  )

(defn validate-parse-rpc-tree
  "Validate & parse a rpc msg valueue given an rpc schema-hid (Enlive-format)."
  [schema-hid rpc-hid]
  (let
    [rpc-tree       (tf/hid->tree rpc-hid)
     schema-tree    (tf/hid->tree schema-hid)
     >>             (assert (= :rpc
                              (fetch-in schema-tree [:tag])
                              (fetch-in rpc-tree [:tag])))
     rpc-attrs      (dissoc rpc-tree ::tf/kids :tag)
     schema-tag     (tf/find-leaf-value schema-hid [:rpc :identifier])
     rpc-tag        (it-> rpc-tree
                      (grab ::tf/kids it)
                      (only it)
                      (fetch-in it [:tag]))
     >>             (assert (= schema-tag rpc-tag))
     ; #todo does not yet verify any attrs ;  what rules?

     fn-args-schema (grab ::tf/kids (tf/find-tree schema-hid [:rpc :input]))
     fn-args-rpc    (grab ::tf/kids (tf/find-tree rpc-hid [:rpc rpc-tag]))

     parsed-args    (mapv validate-parse-leaf-tree fn-args-schema fn-args-rpc)
     rpc-fn         (grab rpc-tag rpc-fn-map)
     rpc-fn-result  (apply rpc-fn parsed-args)
     result-hid     (tf/add-node (glue rpc-attrs {:tag :rpc-reply})
                      [(tf/add-leaf {:tag :data} rpc-fn-result)])]
    result-hid)

  #_(try
      (catch Exception e
        (throw (RuntimeException. (str "validate-parse-rpc: failed for schema-hid=" (pretty-str schema-hid) \newline
                                    "  rpc-hid=" (pretty-str rpc-hid) \newline
                                    "  caused by=" (.getMessage e))))))
  )

(def yang-root-names ; #todo
 [   "acme"
     "toaster"
     "turing-machine"
     "ietf-netconf-acm"
     "brocade-beacon"
     "brocade-rbridge"
     "brocade-terminal"
     "yuma-proc"
     "yuma-xsd" ])

(defn space-wrap [text] (str \space text \space))

(defn create-abnf-parser-raw
  "Given an ABNF syntax string, creates & returns a parser"
  [abnf-str]
  (let [root-parser    (insta/parser abnf-str :input-format :abnf)
        wrapped-parser (fn fn-wrapped-parser [yang-src]
                         (let [parse-result (try
                                              (root-parser yang-src)
                                              (catch Throwable e ; unlikely
                                                (throw (RuntimeException.
                                                         (str "root-parser: InstaParse failed for \n"
                                                           "yang-src=[[" (clip-str 222 yang-src) "]] \n"
                                                           "caused by=" (.getMessage e))))))]
                           (if (instaparse-failure? parse-result) ; This is the normal failure path
                             (throw (RuntimeException.
                                      (str "root-parser: InstaParse failed for \n "
                                        "yang-src=[[" (clip-str 222 yang-src) "]] \n"
                                        "caused by=[[" (pr-str parse-result) "]]")))
                             parse-result)))]
    wrapped-parser))

(defn create-parser-transformer
  "Given an ABNF syntax string, creates & returns a parser that wraps the yang source
  with a leading and trailing space."
  [abnf-str tx-map]
  (let [parser-raw           (create-abnf-parser-raw abnf-str)
        space-wrapped-parser (fn fn-space-wrapped-parser [yang-src]
                               (parser-raw (space-wrap yang-src)))
        parse-and-transform  (fn fn-parse-and-transform [src-text]
                               (let [ast-parse (space-wrapped-parser src-text)
                                     ast-tx    (insta/transform tx-map ast-parse)]
                                 (when (instaparse-failure? ast-tx)
                                   (throw (IllegalArgumentException. (str ast-tx))))
                                 ast-tx))]
    parse-and-transform))

(s/defn leaf-name->attrs
  [leaf-hid :- tf/HID]
  (let [name-kw    (keyword (tf/find-leaf-value leaf-hid [:leaf :identifier]))
        hid-remove (tf/find-hids leaf-hid [:leaf :identifier])]
    (tf/attrs-merge leaf-hid {:name name-kw})
    (tf/remove-kids leaf-hid hid-remove)))

(s/defn leaf-type->attrs
  [leaf-hid :- tf/HID]
  (let [type-kw    (keyword (tf/find-leaf-value leaf-hid [:leaf :type :identifier]))
        hid-remove (tf/find-hids leaf-hid [:leaf :type])]
    (tf/attrs-merge leaf-hid {:type type-kw})
    (tf/remove-kids leaf-hid hid-remove)))

(s/defn tx-leaf-type-ident
  "Within a [:leaf ...] node, convert [:type [:identifier 'decimal64']] ->
    {:type :decimal64} "
  [rpc-hid :- tf/HID]
  (tf/validate-hid rpc-hid)
  (let [rpc-leaf-paths (tf/find-paths rpc-hid [:rpc :* :leaf])
        rpc-leaf-hids  (mapv last rpc-leaf-paths)]
    (run! leaf-type->attrs rpc-leaf-hids)
    (run! leaf-name->attrs rpc-leaf-hids)
    (doseq [hid rpc-leaf-hids]
      (tf/attr-remove hid :tag)
      (tf/remove-all-kids hid))))

(s/defn tx-module-ns
  [module-hid :- tf/HID]
  (let [hids (tf/find-hids module-hid [:module :namespace])]
    (when (not-empty? hids)
      (let [ns-hid (only hids)]
        (tf/attrs-merge module-hid {:namespace (tf/find-leaf-value ns-hid [:namespace :string])})
        (tf/remove-kids module-hid [ns-hid])))))

(s/defn tx-module-contact
  [module-hid :- tf/HID]
  (let [hids (tf/find-hids module-hid [:module :contact])]
    (when (not-empty? hids)
      (let [hid (only hids)]
        (tf/attrs-merge module-hid {:contact (tf/find-leaf-value hid [:contact :string])})
        (tf/remove-kids module-hid [hid])))))

(s/defn tx-module-description
  [module-hid :- tf/HID]
  (let [hids (tf/find-hids module-hid [:module :description])]
    (when (not-empty? hids)
      (let [hid (only hids)]
        (tf/attrs-merge module-hid {:description (tf/find-leaf-value hid [:description :string])})
        (tf/remove-kids module-hid [hid])))))

(s/defn tx-module-revision
  [module-hid :- tf/HID]
  (let [hids (tf/find-hids module-hid [:module :revision])]
    (when (not-empty? hids)
      (let [hid (only hids)]
        (tf/attrs-merge module-hid {:revision (tf/find-leaf-value hid [:revision :iso-date])})
        (tf/remove-kids module-hid [hid])))))

(s/defn tx-rpc
  [rpc-hid]
  (tf/validate-hid rpc-hid)
  (tx-leaf-type-ident rpc-hid)
  (let [id-hid   (tf/find-hid rpc-hid [:rpc :identifier])
        desc-hid (tf/find-hid rpc-hid [:rpc :description])
        rpc-name (keyword (tf/hid->value id-hid))]
    (tf/attrs-merge rpc-hid {:name rpc-name})
    (tf/remove-kids rpc-hid [id-hid desc-hid])))

(s/defn tx-module   ; #todo need Tree datatype for schema
  [module-hid :- tf/HID]
  (let [ident-hid   (tf/find-hid module-hid [:module :identifier])
        ident-value (str->kw (tf/hid->value ident-hid))
        rpc-hid     (tf/find-hid module-hid [:module :rpc])]
    (tf/attrs-merge module-hid {:name ident-value})
    (tf/remove-kids module-hid [ident-hid])
    (tx-module-ns module-hid)
    (tx-module-contact module-hid)
    (tx-module-description module-hid)
    (tx-module-revision module-hid)
    (tx-rpc rpc-hid)))

(s/defn rpc->api :- [s/Any]
  [rpc-hid :- tf/HID]
  (let [rpc-name           (kw->str (tf/hid->attr rpc-hid :name))
        rpc-input-arg-hids (tf/find-hids rpc-hid [:rpc :input :*])
        rpc-arg-syms       (forv [hid rpc-input-arg-hids]
                             (kw->sym (tf/hid->attr hid :name)))
        fn-name            (symbol (str "fn-" rpc-name))
        fn-name-impl       (symbol (str fn-name "-impl"))
        fn-def             (vec->list ['fn fn-name rpc-arg-syms
                                       (vec->list (prepend fn-name-impl rpc-arg-syms))])]
    fn-def))

(s/defn rpc-call-marshall :- s/Any
  [schema-hid :- tf/HID
   args :- [s/Any]]
  (let [schema-fn-name  (tf/hid->attr schema-hid :name)
        schema-arg-hids (tf/find-hids schema-hid [:rpc :input :*])
        >>              (assert (= (count args) (count schema-arg-hids)))
        marshalled-args (map-let [hid schema-arg-hids
                                  arg args]
                          (let [arg-name       (tf/hid->attr hid :name)
                                arg-type       (tf/hid->attr hid :type)
                                marshall-fn    (fetch type-marshall-map arg-type)
                                marshalled-arg [arg-name (marshall-fn arg)]]
                            marshalled-arg))
        msg-hiccup      [:rpc (glue [schema-fn-name {:message-id (next-rpc-msg-id)}]
                                marshalled-args)]]
    msg-hiccup))

(s/defn rpc-call-unmarshall-args
  [schema-arg-hids :- [tf/HID]
   msg-arg-hids :- [tf/HID]]
  (map-let [schema-hid schema-arg-hids
            msg-hid msg-arg-hids]
    (assert (= (tf/hid->attr schema-hid :name) (tf/hid->attr msg-hid :tag)))
    (let [schema-arg-parse-fn (fetch type-unmarshall-map (tf/hid->attr schema-hid :type))
          msg-arg-raw         (tf/hid->value msg-hid)
          msg-arg-parsed      (schema-arg-parse-fn msg-arg-raw)]
      msg-arg-parsed)))

(s/defn rpc-call-unmarshall :- s/Any
  [schema-hid :- tf/HID
   msg-hid :- tf/HID]
  (assert (= :rpc (tf/hid->attr schema-hid :tag) (tf/hid->attr msg-hid :tag)))
  (let [schema-fn-name        (tf/hid->attr schema-hid :name)
        msg-fn-name           (tf/hid->attr (tf/find-hid msg-hid [:rpc :*]) :tag)
        >>                    (assert (= schema-fn-name msg-fn-name))
        schema-arg-hids       (tf/find-hids schema-hid [:rpc :input :*])
        msg-arg-hids          (tf/find-hids msg-hid [:rpc :* :*])
        >>                    (assert (= (count schema-arg-hids) (count msg-arg-hids)))
        rpc-args              (rpc-call-unmarshall-args schema-arg-hids msg-arg-hids)
        rpc-fn                (fetch rpc-fn-map msg-fn-name)
        rpc-call-unmarshalled {:rpc-fn rpc-fn :args rpc-args}]
    rpc-call-unmarshalled))

(s/defn invoke-rpc :- s/Any
  [rpc-call-unmarshalled-map :- tsk/Map]
  (with-map-vals rpc-call-unmarshalled-map [rpc-fn args]
    (apply rpc-fn args)))

(s/defn rpc-reply-marshall :- s/Any
  [schema-hid :- tf/HID
   msg-hid :- tf/HID
   result :- s/Any]
  (let [msg-attrs    (tf/hid->attrs (tf/find-hid msg-hid [:rpc :*]))
        reply-attrs  (glue {:xmlns "urn:ietf:params:xml:ns:netconf:base:1.0"}
                       (submap-by-keys msg-attrs #{:message-id}))
        reply-type   (tf/hid->attr (tf/find-hid schema-hid [:rpc :output :*]) :type)
        marshall-fn  (fetch type-marshall-map reply-type)
        reply-hiccup [:rpc-reply reply-attrs [:result (marshall-fn result)]]]
    reply-hiccup))

(s/defn reply-unmarshall :- s/Any
  [schema-hid :- tf/HID
   reply-hid :- tf/HID]
  (assert (= :rpc-reply (tf/hid->attr reply-hid :tag)))
  (let [result-unparsed   (tf/hid->value (tf/find-hid reply-hid [:rpc-reply :result]))
        reply-type        (tf/hid->attr (tf/find-hid schema-hid [:rpc :output :*]) :type)
        unparse-fn        (fetch type-unmarshall-map reply-type)
        result-parsed     (unparse-fn result-unparsed)]
    result-parsed ))
