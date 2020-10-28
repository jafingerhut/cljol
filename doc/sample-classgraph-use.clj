;; Short sample use of classgraph library from Clojure

;; https://github.com/classgraph/classgraph

;; I do not know why, but when I tried this with AdoptOpenJDK 8 and
;; Zulu JDK 11 on Ubuntu 18.04 Linux, the class java.lang.Object was
;; not included in the thousands of classes returned, so it must not
;; be a list of all classes, but only those that can be found by the
;; way I call the method.  Reading more of the classgraph library
;; documentation will probably make it clear why, and what might be
;; done to get a more complete list.


;; Sample command to invoke using Clojure CLI tools from the shell:

;; clojure -Sdeps '{:deps {io.github.classgraph/classgraph {:mvn/version "4.8.90"}}}'

(import '(io.github.classgraph ClassGraph ClassInfo))
(require '[clojure.reflect :as refl])

(defn all-class-infos []
  (let [scan-result (.. (ClassGraph.) enableAllInfo scan)]
    (into {} (.getAllClassesAsMap scan-result))))

;; Classes found by the classgraph library are not necessarily loaded
;; at the time they are found.  The loadClass method tries attempts to
;; load a class, if it is not already.

(defn try-load-class [class-info]
  (try
    {:err nil :klass (. class-info loadClass)}
    (catch Exception e
      {:err e})))

(defn get-class [class-infos class-name-string]
  (let [class-info (get class-infos class-name-string)
        {:keys [err klass]} (try-load-class class-info)]
    (if err
      nil
      klass)))

(def x (all-class-infos))

(count x)

;; Print some info about a couple of classes, chosen arbitrarily
(pprint (nth (seq x) 0))
(pprint (nth (seq x) 100))

(refl/type-reflect (get-class x "clojure.core$when_first"))
(refl/type-reflect (get-class x "clojure.lang.PersistentVector"))
(refl/type-reflect (get-class x "java.lang.Object"))

;; Show all class names as strings, sorted
(->> (keys x)
     sort
     pprint)
