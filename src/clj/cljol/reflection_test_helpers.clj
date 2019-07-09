(ns cljol.reflection-test-helpers
  (:import (java.lang.reflect Field Method Modifier))
  (:import (org.openjdk.jol.info ClassLayout GraphLayout
                                 ClassData FieldData))
  (:import (org.openjdk.jol.vm VM))
  (:import (io.github.classgraph ClassGraph ClassInfo))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.reflect :as ref]
            [clojure.data :as data]
            [cljol.version-info :as ver]
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
               :declaring-class-str (convert-jra-type-name
                                     (str (:declaring-class m)))}))
       set))


(defn full-data-via-jol [klass]
  (->> (ClassData/parseClass klass)
       d/ClassData->map))


(defn per-instance-fields-common-data-via-jol [klass]
  (->> (full-data-via-jol klass)
       :fields
       (map (fn [m]
              {:field-name (:field-name m)
               :type-str (convert-jra-type-name (:type-class m))
               :declaring-class-str (convert-jra-type-name (:host-class m))}))
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


(defn try-load-class [class-info]
  (try
    {:err nil :klass (. class-info loadClass)}
    (catch Exception e
      {:err e})))


(defn all-class-infos []
  (let [scan-result (.. (ClassGraph.) enableAllInfo scan)]
    (into {} (.getAllClassesAsMap scan-result))))


;; For at least some of the classes with these names, I got exceptions
;; while trying to include them in later analysis code.  I do not know
;; why that later code did not catch the exceptions as I would have
;; expected, given how it is written, but it did not.

(defn problem-klass [klass-name-str]
  (or (re-find #"^org\.apache\.xmlbeans\.XmlCursor$" klass-name-str)
      (re-find #"^org\.apache\.xmlbeans" klass-name-str)
      (re-find #"^org\.mozilla\.javascript\.xml\.impl\.xmlbeans" klass-name-str)
      (re-find #"^com\.google\.javascript\.jscomp\.ant" klass-name-str)))


(defn non-problem-class-infos [class-infos]
  (into {}
        (for [[class-name-str class-info] class-infos
              :when (not (problem-klass class-name-str))]
          [class-name-str class-info])))


(defn load-classes-and-compare-results [class-infos]
  (mapv (fn [[class-name-str class-info]]
          (let [{:keys [err klass]} (try-load-class class-info)
                
                {:keys [err-phase err diffs]}
                (if (nil? err)
                  (try
                    {:diffs (compare-fields-jra-vs-jol klass)}
                    (catch Exception e
                      {:err-phase :compare, :err e}))
                  ;; else
                  {:err-phase :load-class, :err err})]
            {:class-name-str class-name-str
             :klass (if (nil? err-phase) klass)
             :err-phase err-phase
             :err err
             :diffs diffs}))
        class-infos))


(defn report []
  (with-open [wrtr (io/writer (str "report-"
                                   (get @ver/version-data :stack-desc) ".txt"))]
    (binding [*out* wrtr]
      (let [allklass (all-class-infos)
            _ (println "Scan for classes found" (count allklass))
            mostklass (non-problem-class-infos allklass)
            _ (println "of which" (count mostklass)
                       "we will attempt to load and compare, but")
            _ (println (- (count allklass) (count mostklass))
                       "we expect would cause problems in loading or comparison.")
            diffs (load-classes-and-compare-results mostklass)
            count-by-err-phase (frequencies (map :err-phase diffs))
            _ (println "Number of classes categorized by the phase in which an")
            _ (println "error occurred while loading and comparing (nil=no error):")
            _ (pp/pprint count-by-err-phase)
            diffs-by-err-phase (group-by :err-phase diffs)
            errs (dissoc diffs-by-err-phase nil)
            _ (println "Wrote error info belwo after heading '# errors'.")
            no-errs (get diffs-by-err-phase nil)
            by-diff-results (group-by #(= :same (:diffs %)) no-errs)
            no-differences (get by-diff-results true)
            differences (get by-diff-results false)]
        
        (println (count no-differences)
                 "classes with no difference in their field data.")
        (println "Wrote details about differences for" (count differences)
                 "classes below after heading '# differences'.")

        (println)
        (println "############################################################")
        (println "# errors")
        (println "############################################################")
        (when (not= 0 (count errs))
          (pp/pprint errs))

        (println)
        (println "############################################################")
        (println "# differences")
        (println "############################################################")
        (when (not= 0 (count differences))
          (pp/pprint differences))))))


(comment

(do
(require '[cljol.reflection-test-helpers :as t])
(in-ns 'cljol.reflection-test-helpers)
(use 'clojure.repl)
(use 'clojure.pprint)
)
(report)

(do
(def allklass (all-class-infos))
(def mostklass allklass)
(def mostklass (non-problem-class-infos allklass))
(count allklass)
)

(count mostklass)
(def diffs0 (load-classes-and-compare-results mostklass))

(count diffs0)
(def e1 *e)
(pprint (Throwable->map e1))

(frequencies (map #(type (:err %)) diffs0))
(def err1 (first (filter #(type (:err %)) diffs0)))
(keys err1)
(pprint (dissoc err1 :err))
(pprint (Throwable->map (:err err1)))
(map #(juxt (key %) (type (val %))) err1)

(def no-err (filter #(nil? (:err-phase %)) diffs0))
(count no-err)
(count (filter #(= :same (:diffs %)) no-err))
(pprint (first no-err))

(frequencies (map (juxt :err-phase :err) diffs0))


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

)
