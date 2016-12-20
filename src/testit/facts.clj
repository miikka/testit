(ns testit.facts
  (:require [clojure.test :refer :all])
  (:import (clojure.lang IExceptionInfo)))

;;
;; Common predicates:
;;

(def any (constantly true))
(defn truthy [v] (if v true false))
(defn falsey [v] (if-not v true false))

;;
;; fact and facts macros:
;;

(defn- name-and-body [form]
  (if (and (-> form first string?)
           (-> form count (mod 3) (= 1)))
    ((juxt first rest) form)
    [nil form]))

(defmacro fact [& form]
  (let [[name [value arrow expected]] (name-and-body form)]
    `(is (~arrow ~expected ~value) ~(or name (str value " " arrow " " expected)))))

(defmacro facts [& form]
  (let [[name body] (name-and-body form)]
    `(testing ~name
       ~@(for [[value arrow expected] (partition 3 body)]
           `(fact ~value ~arrow ~expected)))))

(defmacro facts-for [& forms]
  (let [[name [form-to-test & fact-forms]] (if (and (-> forms first string?)
                                                    (-> forms count dec (mod 2) (= 1)))
                                             ((juxt first rest) forms)
                                             [nil forms])
        result (gensym)]
    `(testing ~name
       (let [~result ~form-to-test]
         ~@(for [[arrow expected] (partition 2 fact-forms)]
             `(fact ~result ~arrow ~expected))))))

;;
;; Extending clojure.test for =>, =not=> and =throw=>
;;

(def ^:private sym-fn-or-seq? (some-fn symbol? fn? seq?))

(defn- expected-fn? [body]
  (and (-> body seq?)
       (-> body first sym-fn-or-seq?)))

(defn invoke [f & args]
  (if (fn? f)
    (apply f args)
    (= f (first args))))

(declare =>)
(defmethod assert-expr '=> [msg [_ & body]]
  (assert-expr msg (if (expected-fn? body)
                     (cons `invoke body)
                     (cons `= body))))

(declare =not=>)
(defmethod assert-expr '=not=> [msg [_ & body]]
  (assert-expr msg (if (expected-fn? body)
                     (cons (list `complement `invoke) body)
                     (cons `not= body))))

(defn cause-seq [^Throwable exception]
  (if exception
    (lazy-seq
      (cons exception
            (cause-seq (.getCause exception))))))

(defn exception-match? [expected exception]
  (cond
    (class? expected) (instance? expected exception)
    (instance? Throwable expected) (and (instance? (class expected) exception)
                                        (= (.getMessage expected)
                                           (.getMessage exception)))
    (fn? expected) (expected exception)
    (seq expected) (->> (map vector expected (cause-seq exception))
                        (every? (fn [[exp exc]]
                                  (exception-match? exp exc))))))

(declare =throws=>)
(defmethod assert-expr '=throws=> [msg [_ e & body]]
  (assert-expr msg `(try
                      ~@body
                      (is false "Should throw")
                      (catch Throwable ex#
                        (exception-match? ~e ex#)))))

;;
;; contains helper:
;;

(defn- get! [m k]
  (if (contains? m k)
    (get m k)
    (reduced false)))

(defn- deep-compare [actual expected]
  (reduce-kv (fn [_ expected-k expected-v]
               (let [actual-v (get! actual expected-k)]
                 (or (cond
                       ; they are equal?
                       (= expected-v actual-v)
                       true
                       ; both are maps, go deeper:
                       (and (map? expected-v)
                            (map? actual-v))
                       (deep-compare actual-v expected-v)
                       ; expected is fn?
                       (or (symbol? expected-v)
                           (fn? expected-v))
                       (expected-v actual-v)
                       ; none of the above, fail:
                       :else false)
                     (reduced false))))
             false
             expected))

(defn contains [expected]
  {:pre [(map? expected)]}
  (fn [actual]
    (deep-compare actual expected)))

;;
;; ex-info helper:
;;

(defn ex-info? [message data]
  {:pre [(or (nil? message)
             (string? message)
             (fn? message))
         (or (nil? data)
             (map? data)
             (fn? data))]}
  (let [message-check (if (fn? message)
                        message
                        (partial = message))
        data-check (if (fn? data)
                     data
                     (contains data))]
    (fn [e]
      (and (instance? IExceptionInfo e)
           (message-check (.getMessage ^Throwable e))
           (data-check (.getData ^IExceptionInfo e))))))

