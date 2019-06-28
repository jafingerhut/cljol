(ns cljol.generate
  (:gen-class)
  (:import (java.io File))
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

(defn fname [s opts]
  (str (:output-dir opts) File/separator
       s "-" stack-desc ".dot"))


(defn gen [obj name opts]
  (println "Generating" name "...")
  (d/write-dot-file obj (fname name opts) opts))


(defn parse-args [args]
  (when (not= 1 (count args))
    (binding [*out* *err*]
      (println (format "usage: %s <directory-to-write-dot-fiels>" *file*))
      (System/exit 1)))
  {:output-dir (nth args 0)})


(defn -main [& args]
  (let [cmdline-opts (parse-args args)
        opts-default cmdline-opts
        opts-with-field-values (merge cmdline-opts
                                      {:label-node-with-field-values? true})

        ;; Avoid calling clojure.core/str or any similar function on a
        ;; lazy sequence if you do not want it to be realized.
        opts-dont-realize-values (merge opts-with-field-values
                                        {:node-label-fn (constantly "")})
        opts opts-with-field-values]

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
  
    ))


(comment

(do
(use 'cljol.generate)
(require '[cljol.dig9 :as d])
)

;; Interesting!  Self-loop for optimal memory efficiency!
(def lazy2 (repeat 42))
(d/view lazy2 opts-dont-realize-values)
(take 1 lazy2)
(take 10 lazy2)

;; Generates a linked list of a Repeat object, each with a count 1
;; less than the one before.
(def lazy3 (repeat 10 "a"))
(d/view lazy3 opts-dont-realize-values)
(take 1 lazy3)
(take 4 lazy3)

(def lazy4 (seq (vec (range 100))))
(d/view lazy4 opts-dont-realize-values)
(take 1 lazy4)
(take 4 lazy4)

)
