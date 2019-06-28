(ns cljol.generate
  (:gen-class)
  (:require [cljol.dig9 :as d]
            [clojure.string :as str])) 


(def props (into (sorted-map) (System/getProperties)))

(defn clean-os-version [s]
  (str/replace s #"-generic$" ""))

(defn clean-vendor [s]
  (str/replace s #" Corporation$" ""))

(def os-desc (str (props "os.name")
                  "-" (clean-os-version (props "os.version"))))
(def jvm-desc (str "jdk-" (clean-vendor (props "java.vendor"))
                   "-" (props "java.version")))
(def clj-desc (str "clj-" (clojure-version)))
(def stack-desc (str/replace (str os-desc "-" jvm-desc "-" clj-desc)
                             " " "-"))

(defn fname [s]
  (str s "-" stack-desc ".dot"))

(def opts-default {})
(def opts-with-field-values {:label-node-with-field-values? true})

;; Avoid calling clojure.core/str or any similar function on a lazy
;; sequence if you do not want it to be realized.
(def opts-dont-realize-values (merge opts-with-field-values
                                     {:node-label-fn (constantly "")}))

(def opts opts-with-field-values)

(defn gen [obj name & other-args]
  (println "Generating" name "...")
  (apply d/write-dot-file obj (fname name) other-args))


(defn -main [& args]
  (println "Hello world!")

  (let [map1 (let [x :a y :b] {x y y x})]
    (gen map1 "map1" opts))

  ;; Show effects of a lazy sequence being generated on demand,
  ;; without chunking.

  ;; Creators of sequences: repeat, range

  ;; Functions in core.clj that have special code to handle chunked
  ;; sequences in:
  ;; reduce1
  ;; reverse - from reduce1
  ;; A _lot_ of functions in core use reduce1
  
  ;; sequence
  ;; map map-indexed filter remove (inherited from filter) keep keep-indexed
  ;; random-sample - from filter
  ;; doseq
  ;; iterator-seq
  
  (let [
  )
