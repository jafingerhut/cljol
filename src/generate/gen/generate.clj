(ns gen.generate
  (:gen-class)
  (:import (java.io File))
  (:require [cljol.dig9 :as d]
            [cljol.version-info :as ver]
            [clojure.string :as str]
            [clojure.data.int-map :as im]))


(defn fname [s opts]
  (str (:output-dir opts) File/separator
       s "-" (get @ver/version-data :stack-desc) ".dot"))


;; Copied from clojure.math.combinatorics namespace

(defn unchunk
  "Given a sequence that may have chunks, return a sequence that is 1-at-a-time
lazy with no chunks. Chunks are good for efficiency when the data items are
small, but when being processed via map, for example, a reference is kept to
every function result in the chunk until the entire chunk has been processed,
which increases the amount of memory in use that cannot be garbage
collected."
  [s]
  (lazy-seq
   (when (seq s)
     (cons (first s) (unchunk (rest s))))))


(defn gen [obj name opts]
  (println "Generating" name "...")
  (d/write-dot-file [obj] (fname name opts) opts))


(defn parse-args [args]
  (when (not= 1 (count args))
    (binding [*out* *err*]
      (println (format "usage: %s <directory-to-write-dot-files>" *file*))
      (System/exit 1)))
  {:output-dir (nth args 0)})


(def opts-show-field-values
  {:node-label-functions
   [;;d/address-hex
    ;;d/address-decimal
    d/size-bytes
    d/total-size-bytes
    d/scc-size
    d/class-description
    d/field-values
    ;;d/path-to-object
    d/javaobj->str
    ;;d/non-realizing-javaobj->str
    ]})

;; Avoid calling clojure.core/str or any similar function on a lazy
;; sequence if you do not want it to be realized.
(def opts-dont-realize-values
  {:node-label-functions
   [;;d/address-hex
    ;;d/address-decimal
    d/size-bytes
    d/total-size-bytes
    d/scc-size
    d/class-description
    d/field-values
    ;;d/path-to-object
    ;;d/javaobj->str
    d/non-realizing-javaobj->str]})

(defn -main [& args]
  (let [cmdline-opts (parse-args args)
        opts-default cmdline-opts
        opts-show-field-values (merge cmdline-opts opts-show-field-values)
        opts-dont-realize-values (merge cmdline-opts opts-dont-realize-values)
        opts opts-show-field-values]

    (let [map1 (let [x :a y :b] {x y y x})]
      (gen map1 "map1" opts))

    (gen {"a" 1 "foobar" 3.5} "map2" opts)

    (let [vec10 (vec (range 10))
          unboxed-vec10 (apply vector-of :long (range 10))
          arr10 (long-array (range 10))]
      (gen vec10 "vec10" opts)
      (gen unboxed-vec10 "unboxed-vec10" opts)
      (gen arr10 "arr10" opts))

    ;; Interesting!  Self-loop for optimal memory efficiency!
    (let [opts opts-dont-realize-values
          repeat-42 (repeat 42)
          repeat-10-a (repeat 10 "a")]
      (gen repeat-42 "unlimited-repeat-unrealized" opts)
      (println "(take 1 repeat-42)" (take 1 repeat-42))
      (gen repeat-42 "unlimited-repeat-realized1" opts)
      (println "(take 50 repeat-42)" (take 50 repeat-42))
      (gen repeat-42 "unlimited-repeat-realized50" opts)

      (gen repeat-10-a "repeat-10-unrealized" opts)
      (println "(take 1 repeat-10-a)" (take 1 repeat-10-a))
      (gen repeat-10-a "repeat-10-realized1" opts)
      (println "(take 4 repeat-10-a)" (take 4 repeat-10-a))
      (gen repeat-10-a "repeat-10-realized4" opts))

    (let [opts opts-dont-realize-values
          unchunked-range (unchunk (range 50))]

      (gen unchunked-range "unchunked-range-unrealized" opts)
      (println "(take 1 unchunked-range)" (take 1 unchunked-range))
      (gen unchunked-range "unchunked-range-realized1" opts)
      (println "(take 5 unchunked-range)" (take 5 unchunked-range))
      (gen unchunked-range "unchunked-range-realized5" opts))

    (let [opts opts-dont-realize-values
          fib-fn (fn fib-fn [a b]
                   (lazy-seq (cons a (fib-fn b (+ a b)))))
          fib-seq (fib-fn 0 1)]
      (gen fib-seq "lazy-fibonacci-unrealized" opts)
      (println "(take 1 fib-seq)" (take 1 fib-seq))
      (gen fib-seq "lazy-fibonacci-realized1" opts)
      (println "(take 2 fib-seq)" (take 2 fib-seq))
      (gen fib-seq "lazy-fibonacci-realized2" opts)
      (println "(take 3 fib-seq)" (take 3 fib-seq))
      (gen fib-seq "lazy-fibonacci-realized3" opts)
      
      (gen [fib-seq (nthrest fib-seq 1) (nthrest fib-seq 2)
            (nthrest fib-seq 3) (nthrest fib-seq 4)]
           "lazy-fibonacci-vector-of-nthrest" opts))

    (let [opts opts-show-field-values]
      (gen ["food has only 8-bit characters"
            "f\u1234od has non-8-bit characters!"]
           "strings-8-bit-and-not" opts))

    (let [opts opts-show-field-values]
      (gen (list (/ 3 2) 12345678901234567890N true)
           "various-types1" opts)
      (gen (object-array [true 12345678901234567890N "hammock time!"])
           "various-types2" opts))

    (let [opts opts-show-field-values]
      (gen ["food has only 8-bit characters"
            "f\u1234od has non-8-bit characters!"]
           "strings-8-bit-and-not" opts))

    (let [opts (merge opts-show-field-values
                      {:max-value-len 200})]
      (doseq [[name min-char-code max-char-code]
              [
               ["s00-00"   0  1]
               ["s00"   0  32]
               ["s01"  32  64]
               ["s02"  64  96]
               ["s03"  96 128]
               ["s04" 128 160]
               ["s05" 160 192]
               ["s06" 192 224]
               ["s07" 224 256]
               ["s08" 256 271]
               ["s09" (- 65536 32) 65536]
               ["s09-00" (- 65536 32) (- 65536 31)]
               ["s09-01" (- 65536 31) (- 65536 30)]
               ["s09-02" (- 65536 30) (- 65536 29)]
               ["s09-03" (- 65536 29) (- 65536 28)]
               ["s09-04" (- 65536 28) (- 65536 27)]

               ["s09-29" (- 65536 3) (- 65536 2)]
               ["s09-30" (- 65536 2) (- 65536 1)]
               ["s09-31" (- 65536 1) (- 65536 0)]

               ["s10" (- 0xd800 16) (+ 0xd800 16)]
               ["s11" (- 0xd800 16) (+ 0xd800 16)]
               ["s12" (- 0xe000 16) (+ 0xe000 16)]
               ]]
        (let [s (apply str (map char (range min-char-code max-char-code)))]
          (gen s name opts)
          (gen (seq s) (str "c" name) opts))))

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

(require 'gen.generate)
(require '[cljol.dig9 :as d]
         '[clojure.data.int-map :as im]
         '[ubergraph.core :as uber])
(in-ns 'gen.generate)

)

(defn pairs-2i-to-inc [n]
  (for [i (range 0 (* 2 n) 2)]
    [(* 2 i) (inc (* 2 i))]))
(pairs-2i-to-inc 5)

(def map12 (into {} (pairs-2i-to-inc 12)))
(def intmap12 (into (im/int-map) (pairs-2i-to-inc 12)))
(d/view [map12])
(d/view [intmap12])

(def map1e4 (into {} (pairs-2i-to-inc 1e4)))
(def intmap1e4 (into (im/int-map) (pairs-2i-to-inc 1e4)))
(println "map1e4")
(def g (d/sum [map1e4]))
(println "intmap1e4")
(def g (d/sum [intmap1e4]))

(def e1 *e)
(use 'clojure.repl)
(pst e1 100)

(def set1e4 (set (range 1e4)))
(def intset1e4 (set (into (im/int-set) (range 1e4))))
(def denseintset1e4 (set (into (im/dense-int-set) (range 1e4))))
(println "set1e4")
(def g (d/sum [set1e4]))
;; 14,795 objects, 464,088 bytes
(println "intset1e4")
(def g (d/sum [intset1e4]))
;; 821 objects, 33,016 bytes
(println "denseintset1e4")
(def g (d/sum [denseintset1e4]))
;; 25 objects, 2,056 bytes
(d/view-graph g)


;; Examine the data structures of ubergraph library using itself

(def gr1 (uber/multidigraph [1 {:label "n1"}]
                            [2 {:label "n2"}]
                            [1 2 {:label "edge12"}]))
(uber/pprint gr1)
(d/view [gr1])


(def opts opts-dont-realize-values)
(def repeat-42 (repeat 42))
(def o1 (d/consistent-reachable-objmaps [repeat-42]))
(def u1 (d/object-graph->ubergraph o1 opts))
(def g1 (d/add-viz-attributes u1 opts))
(def r1 (d/graph-of-reachable-objects [repeat-42] opts))
(d/view [repeat-42] opts)
(println (take 10 repeat-42))

(defn lazy-fib-sequence* [a b]
  (lazy-seq (let [sum (+ a b)]
              (cons sum (lazy-fib-sequence* b sum)))))

(defn lazy-fib-sequence [a b]
  (cons a (cons b (lazy-seq (lazy-fib-sequence* a b)))))

(def fib-seq (lazy-fib-sequence 1 1))


(defn fib-fn [a b]
  (lazy-seq (cons a (fib-fn b (+ a b)))))

(def fib-seq (fib-fn 0 1))

(def opts opts-dont-realize-values)
(d/view [fib-seq] opts)
(println (take 2 fib-seq))
(println (take 3 fib-seq))
(println (take 4 fib-seq))
(println (take 7 fib-seq))

(d/view [fib-seq (nthrest fib-seq 1) (nthrest fib-seq 2)
         (nthrest fib-seq 3) (nthrest fib-seq 4)] opts)

(def lazy4 (seq (vec (range 100))))
(d/view [lazy4] opts-dont-realize-values)
(take 1 lazy4)
(take 4 lazy4)


;; It might be nice to figure out how seq on a map is implemented some
;; day.  Not extremely obvious to me yet from the figures here, but
;; not surprising as I have never looked at the implementation.

    (let [opts opts-dont-realize-values
          m {1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20}
          s (seq m)]
      (gen s "seq-on-map1-unrealized" opts)
      (doall (take 1 s))
      (gen s "seq-on-map1-realized1" opts)
      (doall (take 3 s))
      (gen s "seq-on-map1-realized3" opts)
      (println "(vec s)" (vec s)))

)
