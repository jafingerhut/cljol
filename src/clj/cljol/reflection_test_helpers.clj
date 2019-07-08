(ns cljol.reflection-test-helpers
  (:import (java.lang.reflect Field Method Modifier))
  (:import (org.openjdk.jol.info ClassLayout GraphLayout
                                 ClassData FieldData))
  (:import (org.openjdk.jol.vm VM))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.reflect :as ref]
            [clojure.data :as data]
            [cljol.dig9 :as d]))


;; The intent is that the cljol.dig9 namespace _should not_ require
;; this one, nor use anything from it, although this one can use
;; things from there.  I only want this namespace to be required when
;; someone wants to run code that compares the return results of
;; multiple different methods of accessing info about classes and
;; instances.

;; Note that JOL's focus is on information about Java objects that is
;; stored in each object instance.  I have not looked extensively for
;; it, but it might not include any facilities for returning
;; information about class or interface methods.  This restricts the
;; comparison of data returned by JOL vs. data returned by
;; Java/Clojure reflection APIs to that about object instances and
;; fields, and we will ignore any information about methods.


(defn full-data-via-java-reflect-api [klass]
  (ref/type-reflect klass :ancestors true))


(defn per-instance-fields-via-java-reflect-api [klass]
  (->> (full-data-via-java-reflect-api klass)
       :members
       (filter (fn [member]
                 (and (instance? clojure.reflect.Field member)
                      (not (contains? (:flags member) :static)))))))


;; Explanation for why I am leaving $<digit> alone, but replacing
;; other occurrences of "$" with ".":

;; I saw some classes in io.github.classgraph itself that had fields
;; whose type names ended with "$" and a single digit.

;; My attempt to avoid replacing those is admittedly hackish and I do
;; not understand why the "$<digit>" occur in the first place.

(defn convert-jra-type-name [type-name-str]
  (-> type-name-str
      (str/replace #"[$]([^0-9])" ".$1")
      (str/replace "<>" "[]")))


;; The 'common data' available via both the Java reflection API and
;; JOL v0.9 that is available about per-object-instance fields appears
;; to be only this:

;; field name
;; type of field
;; in which class this field is declared (could be a superclass)

;; JOL v0.9 also returned a java.lang.reflect.Field object for each
;; field, so all of that info can be obtained from there as well, but
;; I do not see any value in comparing that to 'itself'.

;; JOL v0.9 also returns this data about each field that is not
;; available from the Java reflection API, named via the keys as I
;; have chosen to be returned from the function
;; cljol.dig9/ClassData->map :

;; :is-contended? - boolean
;; :contended-group - a String, which I have only seen as null because
;;     all examples I have seen have :is-contended? false.
;; :vm-offset - long

(defn per-instance-fields-common-data-via-java-reflect-api [klass]
  (->> (per-instance-fields-via-java-reflect-api klass)
       (map (fn [m]
              {:field-name (str (:name m))
               :type-str (convert-jra-type-name (str (:type m)))
               :declaring-class-str (str (:declaring-class m))}))
       set))


(defn full-data-via-jol [klass]
  (->> (ClassData/parseClass klass)
       d/ClassData->map))


(defn per-instance-fields-common-data-via-jol [klass]
  (->> (full-data-via-jol klass)
       :fields
       (map (fn [m]
              {:field-name (:field-name m)
               :type-str (:type-class m)
               :declaring-class-str (:host-class m)}))
       set))


(defn compare-fields-jra-vs-jol [klass]
  (let [d (data/diff
           (per-instance-fields-common-data-via-java-reflect-api klass)
           (per-instance-fields-common-data-via-jol klass))
        [unique-to-jra unique-to-jol common] d]
    (if (and (nil? unique-to-jra)
             (nil? unique-to-jol))
      :same
      d)))


(comment

(do
(require '[cljol.reflection-test-helpers :as t])
(in-ns 'cljol.reflection-test-helpers)
(use 'clojure.repl)
(use 'clojure.pprint)
)


(do

(import '(java.lang.reflect Field Method Modifier))
(import '(org.openjdk.jol.info ClassLayout GraphLayout
                               ClassData FieldData))
(import '(org.openjdk.jol.vm VM))
(require '[cljol.dig9 :as d])
(require '[cljol.reflection-test-helpers :as t])
(require '[clojure.string :as str])
(require '[clojure.reflect :as ref])
(require '[clojure.data :as data])

)


(convert-jra-type-name "io.github.classgraph.ClassGraph$ScanResultProcessor")
(do

(import '(io.github.classgraph ClassGraph ClassInfo))
;;(def scan-result (.. (ClassGraph.) verbose enableAllInfo scan))
(def scan-result (.. (ClassGraph.) enableAllInfo scan))
(type scan-result)
(def allklass (into {} (.getAllClassesAsMap scan-result)))
(type allklass)
(count allklass)

(def diffs0
  (->> allklass
       (mapv (fn [[class-name-str class-info]]
               (let [klass (. class-info loadClass)]
                 {:class-name-str class-name-str
                  :klass klass
                  :diffs (compare-fields-jra-vs-jol klass)})))))

(def diffs1
  (->> diffs0
       (remove #(= :same (:diffs %)))))

)

(count allklass)
(count diffs0)
(count diffs1)


;; Separate out differences that are only because JOL
;; found "__methodImplCache" and java reflection API did not.  Note:
;; Later I found out that the reason for those differences was that
;; JOL returned all per-instance fields of a class, whereas
;; clojure.reflect/type-reflect was only returning the fields declared
;; directly in that class, ignoring any fields defined in
;; superclasses.  When I added the `:ancestors true` options to the
;; clojure.reflect/type-reflect call, they returned the same lists of
;; per-instance fields (probably for all fields, but I only did
;; careful checking on the per-instance fields).

(defn only-diff-is-method-impl-cache? [diff]
  (and (vector? diff)
       (= 3 (count diff))
       (nil? (nth diff 0))
       (nil? (nth diff 2))
       (let [x (nth diff 1)]
         (and (set? x)
              (= 1 (count x))
              (map? (first x))
              (= "__methodImplCache" (:field-name (first x)))))))

(pprint diffs1)
(def d (nth diffs1 1))
(pprint (:diffs d))
(only-diff-is-method-impl-cache? (:diffs d))

(def diffs2 (group-by #(only-diff-is-method-impl-cache? (:diffs %)) diffs1))

(count diffs2)
(keys diffs2)
(count (get diffs2 false))
;; 870
(count (get diffs2 true))
;; 2297
(pprint (nth (get diffs2 false) 100))

(def klass (Class/forName "clojure.lang.ExceptionInfo"))
(pprint (per-instance-fields-common-data-via-java-reflect-api klass))
(pprint (ref/type-reflect klass :ancestors true))
(pprint (per-instance-fields-common-data-via-jol klass))

(def klass (Class/forName "java.lang.RuntimeException"))
(def klass (Class/forName "java.lang.Exception"))
(def klass (Class/forName "java.lang.Throwable"))
(def klass (Class/forName "java.lang.IExceptionInfo"))

(pprint (compare-fields-jra-vs-jol (class "a")))
(pprint (compare-fields-jra-vs-jol (class 1)))
(pprint (compare-fields-jra-vs-jol (class (class 1))))

(def f1 (per-instance-fields-via-java-reflect-api (class "a")))
(def f1 (per-instance-fields-via-java-reflect-api (class 1)))
(def f1 (per-instance-fields-via-java-reflect-api (class (class 1))))
(pprint f1)

(def f1 (per-instance-fields-common-data-via-java-reflect-api (class "a")))
(def f1 (per-instance-fields-common-data-via-java-reflect-api (class 1)))
(def f1 (per-instance-fields-common-data-via-java-reflect-api (class (class 1))))

(def f1 (full-data-via-java-reflect-api (class "a")))
(pprint f1)

(keys f1)
(def ff1 (frequencies (map type (:members f1))))
(pprint ff1)

(def f2 (full-data-via-jol (class "a")))
(def f2 (full-data-via-jol (class 1)))
(def f2 (full-data-via-jol (class (class 1))))
(pprint f2)

(def f2 (per-instance-fields-common-data-via-jol (class "a")))
(def f2 (per-instance-fields-common-data-via-jol (class 1)))
(def f2 (per-instance-fields-common-data-via-jol (class (class 1))))
(pprint f2)

(require '[clojure.reflect :as ref])
(apropos "reflect")
(def sr (ref/type-reflect (class "a")))
(class sr)
(pprint sr)
(keys sr)
(:bases sr)
(:flags sr)
(keys (first (:members sr)))
(pprint (frequencies (map keys (:members sr))))
(def maybe-flds (filter #(contains? % :type) (:members sr)))
(count maybe-flds)
(pprint maybe-flds)
(def per-class-flds (filter #(contains? (:flags %) :static) maybe-flds))
(count per-class-flds)
(def per-instance-flds (remove #(contains? (:flags %) :static) maybe-flds))
(count per-instance-flds)
(pprint per-instance-flds)

)
