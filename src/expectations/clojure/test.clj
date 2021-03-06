;; copyright (c) 2018-2019 sean corfield, all rights reserved

(ns expectations.clojure.test
  "This namespace provides compatibility with clojure.test and related tooling.

  This namespace should be used standalone, without requiring the 'expectations'
  namespace -- this provides a translation layer from Expectations syntax down
  to clojure.test functionality.

  We do not support ClojureScript in clojure.test mode, sorry."
  (:require [clojure.data :as data]
            [clojure.string :as str]
            [clojure.test :as t]))

(def humane-test-output?
  "If Humane Test Output is available, activate it, and enable compatibility
  of our =? with it."
  (try
    (require 'pjstadig.humane-test-output)
    ((resolve 'pjstadig.humane-test-output/activate!))
    true
    (catch Throwable _)))

;; stub functions for :refer compatibility:
(defn- bad-usage [s]
  `(throw (IllegalArgumentException.
           (str ~s " should only be used inside expect"))))
(defmacro in           [& _] (bad-usage "in"))
(defmacro from-each    [& _] (bad-usage "from-each"))
(defmacro more-of      [& _] (bad-usage "more-of"))
(defmacro more->       [& _] (bad-usage "more->"))
(defmacro more         [& _] (bad-usage "more"))

(defn spec? [e]
  (and (keyword? e)
       (try
         (require 'clojure.spec.alpha)
         (when-let [get-spec (resolve 'clojure.spec.alpha/get-spec)]
           (boolean (get-spec e)))
         (catch Throwable _))))

;; smart equality extension to clojure.test assertion -- if the expected form
;; is a predicate (function) then the assertion is equivalent to (is (e a))
;; rather than (is (= e a)) and we need the type check done at runtime, not
;; as part of the macro translation layer
(defmethod t/assert-expr '=? [msg form]
  ;; (is (=? val-or-pred expr))
  (let [[_ e a] form
        conform? (spec? e)
        valid? (when conform? (resolve 'clojure.spec.alpha/valid?))
        explain-str? (when conform? (resolve 'clojure.spec.alpha/explain-str))]
    `(let [e# ~e
           a# ~a
           r# (cond ~conform?
                    (~valid? e# a#)
                    (fn? e#)
                    (e# a#)
                    :else
                    (= e# a#))
           humane?# (and humane-test-output? (not (fn? e#)) (not ~conform?))]
       (if r#
         (t/do-report {:type :pass, :message ~msg,
                       :expected '~form, :actual (if (fn? e#)
                                                   (list '~e a#)
                                                   a#)})
         (t/do-report {:type :fail, :message (if ~conform?
                                               (~explain-str? e# a#)
                                               ~msg)
                       :diffs (if humane?#
                                [[a# (take 2 (data/diff e# a#))]]
                                [])
                       :expected (if humane?# e# '~form)
                       :actual (cond (fn? e#)
                                     (list '~'not (list '~e a#))
                                     humane?#
                                     [a#]
                                     :else
                                     (list '~'not (list '~'=? e# a#)))}))
       r#)))

(defmacro ?
  "Wrapper for forms that might throw an exception so exception class names
  can be used as predicates. This is only needed for more-> so that you can
  thread exceptions into code that can parse information out of them, to be
  used with various expect predicates."
  [form]
  `(try ~form (catch Throwable t# t#)))

(defn all-report
  "Given an atom in which to accumulate results, return a function that
  can be used in place of clojure.test/do-report, which simply remembers
  all the reported results.

  This is used to support the semantics of expect/in."
  [store]
  (fn [m]
    (swap! store update (:type m) (fnil conj []) m)))

(defmacro expect
  "Translate Expectations DSL to clojure.test language."
  ([a] `(t/is ~a))
  ([e a] `(expect ~e ~a true ~e))
  ([e a ex?] `(expect ~e ~a ~ex? ~e))
  ([e a ex? e']
   (let [msg (when-not (= e e')
               (str "  within: "
                    (pr-str (if (and (sequential? e') (= 'expect (first e')))
                              e'
                              (list 'expect e' a)))))]
    (cond
     (and (sequential? a) (= 'from-each (first a)))
     (let [[_ bindings & body] a]
       (if (= 1 (count body))
         `(doseq ~bindings
            (expect ~e ~(first body) ~ex?))
         `(doseq ~bindings
            (expect ~e (do ~@body) ~ex?))))

     (and (sequential? a) (= 'in (first a)))
     (let [form `(~'expect ~e ~a)]
       `(let [a# ~(second a)]
          (cond (or (sequential? a#) (set? a#))
                (let [report#      t/do-report
                      all-reports# (atom nil)]
                  (with-redefs [t/do-report (all-report all-reports#)]
                    (doseq [~'x a#]
                      ;; TODO: really want x evaluated here!
                      (expect ~'x ~e ~ex? ~form)))
                  (if (contains? @all-reports# :pass)
                    ;; report all the passes (and no failures or errors)
                    (doseq [r# (:pass @all-reports#)] (t/do-report r#))
                    (do
                      ;; report all the errors and all the failures
                      (doseq [r# (:error @all-reports#)] (t/do-report r#))
                      (doseq [r# (:fail @all-reports#)] (t/do-report r#)))))
                (map? a#)
                (let [e# ~e]
                  (expect e# (select-keys e# (keys a#)) ~ex? ~form))
                :else
                (throw (IllegalArgumentException. "'in' requires map or sequence")))))

     (and (sequential? e) (= 'more (first e)))
     (let [es (mapv (fn [e] `(expect ~e ~a ~ex? ~e')) (rest e))]
       `(do ~@es))

     (and (sequential? e) (= 'more-> (first e)))
     (let [es (mapv (fn [[e a->]]
                      (if (and (sequential? a->)
                               (symbol? (first a->))
                               (let [s (name (first a->))]
                                 (or (str/ends-with? s "->")
                                     (str/ends-with? s "->>"))))
                        `(expect ~e (~(first a->) (? ~a) ~@(rest a->)) false ~e')
                        `(expect ~e (-> (? ~a) ~a->) false ~e')))
                    (partition 2 (rest e)))]
       `(do ~@es))

     (and (sequential? e) (= 'more-of (first e)))
     (let [es (mapv (fn [[e a]] `(expect ~e ~a ~ex? ~e'))
                    (partition 2 (rest (rest e))))]
       `(let [~(second e) ~a] ~@es))

     (and ex? (symbol? e) (resolve e) (class? (resolve e)))
     (if msg
       (if (isa? (resolve e) Throwable)
         `(t/is (~'thrown? ~e ~a) ~msg)
         `(t/is (~'instance? ~e ~a) ~msg))
       (if (isa? (resolve e) Throwable)
         `(t/is (~'thrown? ~e ~a))
         `(t/is (~'instance? ~e ~a))))

     (isa? (type e) java.util.regex.Pattern)
     (if msg
       `(t/is (re-find ~e ~a) ~msg)
       `(t/is (re-find ~e ~a)))

     :else
     (if msg
       `(t/is (~'=? ~e ~a) ~msg)
       `(t/is (~'=? ~e ~a)))))))

(comment
  (macroexpand '(expect (more-> 1 :a 2 :b 3 (-> :c :d)) {:a 1 :b 2 :c {:d 4}}))
  (macroexpand '(expect (more-of a 2 a) 4))
  (macroexpand '(expect (more-of {:keys [a b c]} 1 a 2 b 3 c) {:a 1 :b 2 :c 3})))

(defn- contains-expect?
  "Given a form, return true if it contains any calls to the 'expect' macro."
  [e]
  (when (and (coll? e) (not (vector? e)))
    (or (= 'expect (first e))
        (some contains-expect? e))))

(defmacro defexpect
  "Given a name (a symbol that may include metadata) and a test body,
  produce a standard 'clojure.test' test var (using 'deftest').

  (defexpect name expected actual) is a special case shorthand for
  (defexpect name (expect expected actual)) provided as an easy way to migrate
  legacy Expectation tests to the 'clojure.test' compatibility version."
  [n & body]
  (if (and (>= 2 (count body))
           (not (some contains-expect? body)))
    `(t/deftest ~n (expect ~@body))
    `(t/deftest ~n ~@body)))

(defmacro expecting
  "The Expectations version of clojure.test/testing."
  [string & body]
  `(t/testing ~string ~@body))

;; DSL functions copied from Expectations:
(defmacro side-effects [fn-vec & forms]
  (when-not (vector? fn-vec)
    (throw (IllegalArgumentException.
            "side-effects requires a vector as its first argument")))
  (let [side-effects-sym (gensym "conf-fn")]
    `(let [~side-effects-sym (atom [])]
       (with-redefs ~(vec (interleave fn-vec (repeat `(fn [& args#] (swap! ~side-effects-sym conj args#)))))
         ~@forms)
       @~side-effects-sym)))

(defn approximately
  "Given a value and an optional delta (default 0.001), return a predicate
  that expects its argument to be within that delta of the given value."
  ([^double v] (approximately v 0.001))
  ([^double v ^double d]
   (fn [x] (<= (- v (Math/abs d)) x (+ v (Math/abs d))))))

(defn functionally
  "Given a pair of functions, return a custom predicate that checks that they
  return the same result when applied to a value. May optionally accept a
  'difference' function that should accept the result of each function and
  return a string explaininhg how they actually differ.
  For explaining strings, you could use expectations/strings-difference.
  (only when I port it across!)

  Right now this produces pretty awful failure messages. FIXME!"
  ([expected-fn actual-fn]
   (functionally expected-fn actual-fn (constantly "not functionally equivalent")))
  ([expected-fn actual-fn difference-fn]
   (fn [x]
     (let [e-val (expected-fn x)
           a-val (actual-fn x)]
       (t/is (= e-val a-val) (difference-fn e-val a-val))))))
