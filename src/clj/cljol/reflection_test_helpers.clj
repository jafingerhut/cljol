(ns cljol.reflection-test-helpers
  (:import (java.lang.reflect Field Method Modifier))
  (:import (org.openjdk.jol.info ClassLayout ClassData FieldData))
  (:import (org.openjdk.jol.vm VM))
  (:import (io.github.classgraph ClassGraph ClassInfo))
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.reflect :as ref]
            [clojure.data :as data]
            [cljol.version-info :as ver]
            [clj-memory-meter.core :as mm]
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn superclasses [cls]
  (take-while identity (iterate (memfn ^Class getSuperclass) cls)))


(defn all-fields
  "Return all fields of the class 'cls', and its superclasses"
  [cls]
  (mapcat #(.getDeclaredFields ^Class %)
          (superclasses cls)))


(defn per-inst-ref-field? [^Field fld]
  (and (not (. (. fld getType) isPrimitive))
       (not (Modifier/isStatic (. fld getModifiers)))))


(defn per-instance-reference-fields [cls]
  (filter per-inst-ref-field? (all-fields cls)))


(defn per-inst-field? [^Field fld]
  (not (Modifier/isStatic (. fld getModifiers))))


(defn per-instance-fields [cls]
  (filter per-inst-field? (all-fields cls)))


(defn per-instance-fields-common-data-via-custom-api [klass]
  (->> (per-instance-fields klass)
       (map #'clojure.reflect/field->map)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


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

(defn primitive-class-name? [name-str]
  (contains? #{"boolean" "byte" "short" "char" "int" "float" "long" "double"}
             name-str))


(defn per-instance-fields-common-data-via-java-reflect-api [klass]
  (->> (per-instance-fields-via-java-reflect-api klass)
       (map (fn [m]
              {:field-name (str (:name m))
               :type-str (convert-jra-type-name (str (:type m)))
               ;; This value is really derived fairly indirectly from
               ;; what the clojure.reflect API returns, but I want to
               ;; compare its accuracy against a more direct Java
               ;; method to see if it always gives the same result.
               :is-primitive? (primitive-class-name? (str (:type m)))
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
              (let [^Field ref-field (:ref-field m)]
                {:field-name (:field-name m)
                 :type-str (convert-jra-type-name (:type-class m))
                 :is-primitive? (. (. ref-field getType) isPrimitive)
                 :declaring-class-str (convert-jra-type-name
                                       (:host-class m))})))
       set))


(defn compare-fields-jra-vs-jol [klass]
  (let [d (data/diff
           (per-instance-fields-common-data-via-java-reflect-api klass)
           (per-instance-fields-common-data-via-jol klass))
        [unique-to-jra unique-to-jol _common] d]
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


(defn size-difference-info [obj]
  (let [cls (class obj)
        arr? (d/array? obj)
	parsed-inst (ClassLayout/parseInstance obj)
        parsed-cls (ClassLayout/parseClass cls)
	vm-size (. (VM/current) sizeOf obj)
        inst-size (. parsed-inst instanceSize)
        cl-size (. parsed-cls instanceSize)
        ;; From testing at a REPL, I have seen that
        ;; ClassLayout/parseClass returns the size of a length 0 array
        ;; object, which makes sense because it only gets the class,
        ;; not a particular instance of an array.
        ;; ClassLayout/parseInstance returns the size of the
        ;; particular array object it is given, which includes the
        ;; base size returned by ClassLayout/parseClass, but also the
        ;; size of all array elements.
        array-match? (= vm-size inst-size)
        ;; For non-array objects, all three of these sizes should
        ;; match each other.
        non-array-match? (= vm-size cl-size inst-size)
        size-match? (if arr? array-match? non-array-match?)]
    ;;(println "toPrintable of parseInstance ret value:")
    ;;(print (.toPrintable parsed-inst))
    ;;(println)
    ;;(println "toPrintable of parseClass ret value:")
    ;;(print (.toPrintable parsed-cls))
    ;;(println)
    ;;(println "cls:" cls)
    ;;(println vm-size "(. (VM/current) sizeOf obj)")
    ;;(println inst-size "(. (ClassLayout/parseInstance obj) instanceSize)")
    ;;(println cl-size "(. (ClassLayout/parseClass cls) instanceSize)")
    (if size-match?
      {:difference false :obj obj :class (class obj) :vm-size vm-size}
      {:difference true :obj obj :class (class obj)
       :array? arr?
       :vm-size vm-size
       :classlayout-parseclass-size cl-size
       :classlayout-parseinstance-size inst-size})))


(defn jamm-vs-jol-size-difference-info [obj]
  (let [arr? (d/array? obj)
	parsed-inst (ClassLayout/parseInstance obj)
        inst-size (. parsed-inst instanceSize)
        jamm-size (mm/measure obj :shallow true :bytes true)
        size-match? (= inst-size jamm-size)]
    (if size-match?
      {:difference false :obj obj :class (class obj)}
      {:difference true :obj obj :class (class obj)
       :array? arr?
       :classlayout-parseinstance-size inst-size
       :jamm-size jamm-size})))


(defn avg [vals]
  (/ (reduce + vals) (count vals)))


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
            _ (println "Wrote error info below after heading '# errors'.")
            no-errs (get diffs-by-err-phase nil)
            by-diff-results (group-by #(= :same (:diffs %)) no-errs)
            no-differences (get by-diff-results true)
            differences (get by-diff-results false)

            loaded-klass-infos (remove #(nil? (:klass %)) diffs)
            pif-diffs (for [{:keys [klass]} loaded-klass-infos
                            :let [pif-custom (set (per-instance-fields-common-data-via-custom-api klass))
                                  pif-jra (set (per-instance-fields-via-java-reflect-api klass))]
                            :when (not= pif-custom pif-jra)]
                        {:class-name (str klass)
                         :pif-custom pif-custom
                         :pif-jra pif-jra})
            test-array-objs (for [klass [(object-array 5)
                                         (boolean-array 100)
                                         (char-array 50)
                                         (byte-array 50)
                                         (short-array 50)
                                         (int-array 50)
                                         (long-array 50)
                                         (float-array 50)
                                         (double-array 50)]]
                              {:klass klass})
            inst-size-diffs (for [{:keys [klass]} (concat loaded-klass-infos
                                                          test-array-objs)
                                  :let [results (size-difference-info klass)]
                                  :when (:difference results)]
                              results)
            ;; Disable testing of jamm sizes by default.
            test-jamm? false
            jamm-vs-jol-diffs (when test-jamm?
                                (for [{:keys [klass]} (concat loaded-klass-infos
                                                              test-array-objs)
                                      :let [results
                                            (jamm-vs-jol-size-difference-info
                                             klass)]
                                      :when (:difference results)]
                                  results))
            ]
        
        (println (count no-differences)
                 "classes with no difference in their field data.")
        (println "Wrote details about differences for" (count differences)
                 "classes below after heading '# differences'.")

        (println "Found" (count pif-diffs) "classes with different"
                 "per-instance field lists according to different APIs.")
        (println "Wrote differences below after heading '# pif-diffs'.")

        (println "Found" (count inst-size-diffs) "classes with different"
                 "sizes according to different JOL APIs.")
        (println "Wrote differences below after heading '# inst-size-diffs'.")

        (when test-jamm?
          (println "Found" (count jamm-vs-jol-diffs) "classes with different"
                   "sizes according to jamm vs JOL APIs.")
          (println "Wrote differences below after heading '# jamm-vs-jol-diffs'."))

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
          (pp/pprint differences))

        (println)
        (println "############################################################")
        (println "# pif-diffs")
        (println "############################################################")
        (when (not= 0 (count pif-diffs))
          (pp/pprint pif-diffs))

        (println)
        (println "############################################################")
        (println "# inst-size-diffs")
        (println "############################################################")
        (when (not= 0 (count inst-size-diffs))
          (pp/pprint inst-size-diffs))

        (when test-jamm?
          (println)
          (println "############################################################")
          (println "# jamm-vs-jol-diffs")
          (let [jamm-vs-jol-diffs
                (->> jamm-vs-jol-diffs
                     (map (fn [x]
                            (assoc x :jamm-over-jol-size-ratio
                                   (/ (* 1.0 (:jamm-size x))
                                      (:classlayout-parseinstance-size x)))))
                     (sort-by :jamm-over-jol-size-ratio >))
                ratios (map :jamm-over-jol-size-ratio jamm-vs-jol-diffs)]
            (println "# Statistics about ratio of size according to jamm vs.")
            (println "# size according to JOL parseInstance:")
            (println (format "# min ratio=%.3f" (apply min ratios)))
            (println (format "# max ratio=%.3f" (apply max ratios)))
            (println (format "# avg ratio=%.3f" (avg ratios)))
            (println "# Largest 5 ratios:")
            (pp/pprint (take 5 jamm-vs-jol-diffs))
            (println "# Smallest 3 ratios:")
            (pp/pprint (take-last 3 jamm-vs-jol-diffs)))
          (println "############################################################")
          (when (not= 0 (count jamm-vs-jol-diffs))
            (pp/pprint jamm-vs-jol-diffs)))

        pif-diffs))))


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

(def c1 (Class/forName "clojure.lang.Cons"))
(require '[clojure.reflect :as ref])
(def r1 (ref/type-reflect c1 :ancestors true))
(first (:members r1))
(def f1 (filter #(and (instance? clojure.reflect.Field %)
                      (not (contains? (:flags %) :static)))
                (:members r1)))
(count f1)
(pprint (first f1))
(map type f1)
(map :name f1)
(pprint f1)
(-> f1 first :name type)
(def f2 (first (filter #(= '_hash (:name %)) f1)))
(count f2)
f2
(-> f2 :type type)
(. c1 getField "_hash")
(seq (. c1 getFields))
(require '[cljol.dig9 :as d])
(import '(org.openjdk.jol.info ClassLayout GraphLayout
                               ClassData FieldData))
(def cd (d/ClassData->map (ClassData/parseClass c1)))
(pprint (-> cd :fields))
(def int-field (-> cd :fields (nth 1) :ref-field))
(= (. int-field getType) Integer/TYPE)

(type Integer/TYPE)
(. Integer/TYPE isPrimitive)
(type java.lang.Integer)
(type (int 5))
(. java.lang.Integer isPrimitive)
(. (type (int 5)) isPrimitive)

)
