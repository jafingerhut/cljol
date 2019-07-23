;; Scratch pad of code for using in REPL sessions while developing cljol

(do
(require '[cljol.dig9 :as d]
         '[cljol.graph :as gr]
         '[loom.alg-generic :as lag]
         '[loom.alg :as lalg]
	 '[ubergraph.core :as uber]
	 '[ubergraph.alg :as ualg])
(def opts
  {:node-label-functions
   [#'d/address-decimal
    #'d/size-bytes
    #'d/total-size-bytes
    #'d/uniquely-reachable-info
    #'d/scc-size
    #'d/class-description
    #'d/field-values
    #'d/non-realizing-javaobj->str]
   :reachable-objmaps-debuglevel 1
   :consistent-reachable-objects-debuglevel 1
   :graph-of-reachable-objects-debuglevel 1
   :bounded-reachable-node-stats-debuglevel 1
   :bounded-reachable-node-stats2-debuglevel 1
;;   :calculate-total-size-node-attribute :complete
   :calculate-total-size-node-attribute :bounded
;;   :calculate-total-size-node-attribute :bounded2
;;   :calculate-total-size-node-attribute nil
   :slow-instance-size-checking? true
;;   :stop-walk-at-references false  ;; default true
;;   :summary-options [:all]
;;   :summary-options [:wcc-details :scc-details :size-breakdown :class-breakdown :node-degree-breakdown :distance-breakdown]
   })
(def v1 (vector 2))
)

(def g (d/sum [v1] opts))
(def g (d/sum (let [v1 (vec (range 4))] [v1 (conj v1 4)]) opts))
(def g (d/sum (let [m1 (zipmap (range 4) (range 5 9))] [m1 (assoc m1 4 9)]) opts))
(def g2 (gr/remove-all-attrs-except g [:my-unique-num-reachable-nodes
                                       :my-unique-total-size
                                       :reachable-only-from]))
(uber/pprint g2)

(require '[clojure.java.io :as io])
(with-open [wrtr (io/writer "dimultigraph-129k-nodes-272k-edges.edn")]
  (binding [*out* wrtr]
    (uber/pprint g2)))

(d/view-graph g)
(d/view-graph g2)
(def g (d/sum [#'v1] opts))
(def g (d/sum [#'v1 (the-ns 'user)] opts))
(def g3 (gr/induced-subgraph g (filter #(<= (uber/attr g % :distance) 6)
                                       (uber/nodes g))))
(d/view-graph g3)

(def close-nodes (set (filter #(<= (uber/attr g % :distance) 6)
                              (uber/nodes g))))
(= (gr/induced-subgraph-build-from-empty g close-nodes)
   (gr/induced-subgraph-by-removing-nodes g close-nodes))

(def v1 (vector-of :long 1 2 3 4))
(def v1 (repeat 5))
(take 5 v1)
(def v1 (let [a1 (atom 5)
              a2 (atom 10)]
          (swap! a1 (constantly a2))
          (swap! a2 (constantly a1))
          [a1 a2]))
(def v1 (let [x :x y :y] {x y y x}))
(def v1 [[:x :y] [:y :x]])
(def v1 (list 2))
(def v1 (class 5))
(def v1 (vec (range 4)))
(def g nil)
(def g2 nil)
(def g3 nil)
(System/gc)
(def g (d/sum [v1] opts))
(def g2 (d/sum [(gr/remove-all-attrs g)] opts))
(def g (d/sum [#'v1] opts))
(d/view-graph g)
(d/view-graph g {:save {:filename "g.pdf" :format :pdf}})
(d/view-graph g2)
(d/view-graph g2 {:save {:filename "g2.pdf" :format :pdf}})

;; Additional memory for each new 2-element vector, not counting the
;; top level vector: 40-byte clojure.lang.PersistentVector, 24-byte
;; java.lang.Object array with 2 elements, 2 24-byte Long objects.  So
;; 40+24+2*24=112 additional bytes per vector.
(def g (d/sum [[1 2] [3 4] [5 6] [7 8]] opts))

;; The keywords :a and :b are shared by all of these maps, i.e. they
;; are identical objects in memory.  The memory distinct to each map
;; is a 32-byte clojure.lang.PersistentArrayMap, a 32-byte
;; java.lang.Object array, and 2 24-byte Long objects.  So
;; 2*32+2*24=112 additional bytes per map.
(def g (d/sum [{:a 1 :b 2} {:a 3 :b 4} {:a 5 :b 6} {:a 7 :b 8}] opts))

(defn remove-node-labels [g]
  (reduce (fn [g n] (uber/remove-attr g n :label))
          g
          (uber/nodes g)))

(def g2 (remove-node-labels (gr/bounded-reachable-node-stats2 g d/object-size-bytes)))
(uber/pprint g2)
(uber/viz-graph g2 {:auto-label true :rankdir :LR})
(def dot-str (uber/viz-graph g2 {:auto-label true :rankdir :LR :save {:filename "g2.pdf" :format :pdf}}))

(def scct (gr/scc-tarjan g))
;; :components components
;; :rindex rindex
;; :vS vS - Testing showed both front and back stacks as empty when
;; scc-tarjan returned, for vS and iS.  I would guess that the
;; scc-tarjan algorithm guarantees these will all be empty when the
;; function completes.  If so, no good reason to return them.
;; :iS iS
;; :root root
(keys scct)
(pprint (map (juxt key #(type (val %))) scct))
(gr/isEmptyFront (:vS scct))
(gr/isEmptyBack (:vS scct))
(gr/isEmptyFront (:iS scct))
(gr/isEmptyBack (:iS scct))
(pprint scct)
(uber/pprint g)

(check-scc-tarjan-topological-order g)

(defn check-scc-tarjan-topological-order [g]
  (let [scct (gr/scc-tarjan g)
        {:keys [components rindex vS iS root node->int int->node edges]} scct
        node->rank (into {}
                         (for [[idx nodes] (map-indexed (fn [idx nodes]
                                                          [idx nodes])
                                                        components)
                               node nodes]
                           [node idx]))
        edges-violating-partial-order
        (filter (fn [e]
                  (let [src-rank (node->rank (uber/src e))
                        dest-rank (node->rank (uber/dest e))]
                    (> src-rank dest-rank)))
                (uber/edges g))]
    {:ok (zero? (count edges-violating-partial-order))
     :violating-edges edges-violating-partial-order}))


(defn gsize [g]
  (println (uber/count-nodes g) "nodes" (uber/count-edges g) "edges"))

(gsize g)
(def sccg (:scc-graph (gr/scc-graph g)))
(def sccg nil)
(gsize sccg)
(uber/pprint sccg)
(d/view-graph sccg)
(def sccg1 (reduce-dag-experiment1 sccg))
(def sccg1 nil)
(gsize sccg1)
(uber/pprint sccg1)
(d/view-graph sccg1)
(def sccg2 (reduce-dag-experiment2 sccg))
(def sccg2 nil)
(gsize sccg2)
(uber/pprint sccg2)

(def sccg3 (reduce-dag-experiment2 sccg1))
(gsize sccg3)

(count (filter #(and (= 1 (count (uber/successors sccg1 %)))
                     (= 1 (count (uber/predecessors sccg1 %))))
               (uber/nodes sccg1)))

;; Example results

;; (gsize g)     126278 nodes 266227 edges
;; (gsize sccg)  104210 nodes 173976 edges
;; (gsize sccg1)  65602 nodes 135368 edges (38608 fewer nodes & edges)
;; (gsize sccg2) 104210 nodes 173976 edges (0 fewer nodes & edges)
;; (count ...)    32599 nodes with in-deg=out-deg=1 in sccg1

(def f (frequencies
        (map (fn [v]
               [(count (uber/predecessors sccg2 v))
                (count (uber/successors sccg2 v))])
             (uber/nodes sccg2))))
(count f)
(defn compare-pairs [[p1a p1b] [p2a p2b]]
  (let [sum1 (+ p1a p1b)
        sum2 (+ p2a p2b)
        cmp1 (compare sum1 sum2)]
    (if (not= 0 cmp1)
      cmp1
      (compare [p1a p1b] [p2a p2b]))))
(pprint (into (sorted-map-by compare-pairs) f))


(def n1 (first (uber/nodes g)))
n1
(keys (uber/attrs g n1))

(def vars (->> (uber/nodes g)
               (map #(uber/attr g % :obj))
               (filter #(instance? clojure.lang.Var %))))
(count vars)
(pprint (sort-by symbol vars))
(def nss (->> (uber/nodes g)
              (map #(uber/attr g % :obj))
              (filter #(instance? clojure.lang.Namespace %))))
(count nss)
(pprint (sort-by str nss))

(def wcc (map set (ualg/connected-components g)))
(sort > (map count wcc))
(def wcc2 (rest (sort-by count > wcc)))
(sort > (map count wcc2))
(def straggler-nodes (apply clojure.set/union wcc2))
(count straggler-nodes)
(def g2 (gr/induced-subgraph g straggler-nodes))
(d/view-graph g2)
(d/graph-summary g2)
(def g2 nil)


(def g3 (gr/induced-subgraph g (filter #(<= (uber/attr g % :distance) 6)
                                        (uber/nodes g))))
(gsize g3)
(d/view-graph g3)
(d/view-graph g3 {:save {:filename "g3.pdf" :format :pdf}})

(uber/pprint g)
(defn inconsistent-distance-nodes [g]
  (for [node (uber/nodes g)
        :let [attrs (uber/attrs g node)
	      sp-dist (:distance attrs)
	      gpl-dist (:gpl-distance attrs)]]
    {:node node :sp-dist sp-dist :gpl-dist gpl-dist}))
(def i1 (inconsistent-distance-nodes g))
(count i1)
(uber/count-nodes g)
(def i2 (group-by (fn [x] (= (:sp-dist x) (:gpl-dist x))) i1))
(count (i2 true))
(count (i2 false))

;; lazy pre-order depth-first search order traversal of nodes
(def root (first (filter #(= v1 (uber/attr g % :obj)) (uber/nodes g))))
root
(def pre (ualg/pre-traverse g root))
(take 5 pre)
pre
(= (set pre) (set (uber/nodes g)))

;; If I do a 'take-while' on a lazy sequence of nodes, with the
;; condition to keep going being:

;; (number of nodes <= N) and (total size of nodes <= S)

;; then if it stops because of the condition failing, that is:

;; (number of nodes > N) or (total size of nodes > S)

;; If I want the stopping condition to be:

;; (number of nodes > N) and (total size of nodes > S)

;; then the continuing condition should be the opposite of that, or
;; the first condition above with 'and' replaced by 'or'.

(def node-count-min-limit 0)
(def total-size-min-limit 0)

(def node-count-min-limit 4)
(def total-size-min-limit 100)
(def node-size-fn d/object-size-bytes)

(require '[cljol.graph :as gr])

root
(gr/bounded-reachable-node-stats
    g root node-size-fn node-count-min-limit total-size-min-limit)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def g (d/sum [(vec (range 1e5))] opts))
(def o1 (d/consistent-reachable-objmaps [#'v1] opts))

(def g (d/sum [#'v1] opts))
(d/view-graph g {:save {:filename "clojure-var.dot" :format :dot}})
(type g)
(doseq [dist [3 4 5 6 7 8]]
  (let [g2 (gr/induced-subgraph g (filter #(let [d (uber/attr g % :distance)]
                                             (and (number? d) (<= d dist)))
                                          (uber/nodes g)))
        fname (str "clojure-var-dist-" dist ".dot")]
    (d/view-graph g2 {:save {:filename fname :format :dot}})))

(type o1)
(def e1 *e)
(class e1)
(class (-> e1 ex-data))
(keys (-> e1 ex-data))
(pprint (Throwable->map e1))
(def r1 (clojure.repl/root-cause e1))
(class (ex-data r1))
(keys (ex-data r1))
(.getMessage r1)
(def e2 (-> (ex-data r1) :errors))
(-> e2 :err)
(keys e2)
(class (-> e2 :data))
(bounded-count 10 (-> e2 :data))
(type (first (-> e2 :data)))
(keys (-> e2 :data))
(pprint (:fields (-> e2 :data)))
d/inaccessible-field-val-sentinel
(identical? d/inaccessible-field-val-sentinel (get (-> e2 :data :fields) "handler"))
(def e3 (-> e2 :err-data))
(type e3)
(map type e3)
(first e3)

;; Trying the following starts printing a large amount of Clojure data
;; as the ex-data of the exception, I believe.  Don't do that.  For
;; all I know, it might even experience an infinite loop in its
;; attempt to print cyclic structures.
(pst e1 100)

(type e1)
(type (ex-data e1))
;; This is small data
(keys (ex-data e1))

;; Maybe big data is in root-casue of exception?
(def r1 (clojure.repl/root-cause e1))

(type r1)
(type (ex-data r1))
(keys (ex-data r1))

(-> (ex-data r1) :errors type)
;; => clojure.lang.PersistentArrayMap
(-> (ex-data r1) :obj-coll type)
;; => clojure.lang.PersistentVector

(def e2 (-> (ex-data r1) :errors))
(-> e2 :err)
;; => :object-moved
(-> e2 :err-data type)
(-> e2 :err-data keys)
;; => (:address :obj :size :path :fields :cur-address)
(def e3 (-> e2 :err-data))

(:address e3)
;; => 27006116544
(d/address-of (:obj e3))
;; => 26168262656
(-> e3 :obj type)
;; => java.lang.ref.SoftReference

;; Try to isolate some cases where JOL 0.9 seems to return incorrect
;; object sizes.

(do
(import '(org.openjdk.jol.info ClassLayout GraphLayout))
(import '(org.openjdk.jol.vm VM))
(defn foo [obj]
  (let [cls (class obj)
	parsed-inst (ClassLayout/parseInstance obj)
        parsed-cls (ClassLayout/parseClass cls)
	vm-size (. (VM/current) sizeOf obj)
        inst-size (. parsed-inst instanceSize)
        cl-size (. parsed-cls instanceSize)]
    (println "toPrintable of parseInstance ret value:")
    (print (.toPrintable parsed-inst))
    (println)
    (println "toPrintable of parseClass ret value:")
    (print (.toPrintable parsed-cls))
    (println)
    (println "cls:" cls)
    (println vm-size "(. (VM/current) sizeOf obj)")
    (println inst-size "(. (ClassLayout/parseInstance obj) instanceSize)")
    (println cl-size "(. (ClassLayout/parseClass cls) instanceSize)")
    (if (= vm-size cl-size)
      (println "same")
      (println "DIFFERENT"))))
)
(foo 5)
(foo "bar")
(foo (class 5))
(foo (object-array 0))
(foo (object-array 1))
(foo (object-array 5))
(foo (object-array 6))
(foo (object-array 7))
(foo (object-array 8))
(foo (object-array 9))
(foo (object-array 50))

(foo (char-array 0))
(foo (char-array 1))
(foo (char-array 50))


;; Based on some Java code recommended here for getting the path to a
;; file name defining a class in a StackTraceElement

;; The method (.getClassName x) called on a StackTraceElement object
;; within a stack trace returns a string representing the class name,
;; if one was available when creating the StackTraceElement.  That
;; string can be passed to the function below to try to get more
;; information about the class named by that string.

(require '[clojure.string :as str])

;; https://stackoverflow.com/questions/26674037/possible-to-get-the-file-path-of-the-class-that-called-a-method-in-java
(defn class-info [classname-str]
  (let [klass (Class/forName classname-str)
        loader (.getClassLoader klass)
        resource-name (str (clojure.string/replace classname-str "." "/")
                           ".class")
        rsrc (.getResource loader resource-name)]
    {:klass klass :loader loader :resource-name resource-name
     :resource rsrc}))

(def classname-str "cljol.graph$scc_graph")
(Class/forName classname-str)
(type (Class/forName classname-str))
(def f (Class/forName classname-str))
;; Weird, why does (Class/forName ...) throw no exception, but (def f ...) does?
f
klass
(def loader (.getClassLoader (Class/forName classname-str)))
loader
(def n1 (str (clojure.string/replace classname-str "." "/") ".class"))
(def n1 (str (clojure.string/replace classname-str "." "/") ".clj"))
(def n1 "cljol/graph.clj")
n1
(.getResource (.getClassLoader (Class/forName classname-str)) n1)
(str rsrc)



(def ci (class-info "clojure.lang.Compiler$InvokeExpr"))
(pprint ci)
(str (:resource ci))

(def e1 *e)
(def e2 (Throwable->map e1))
(pprint e2)
(keys e2)
(type (first (:trace e2)))
(-> e2 :trace first first str)
;; => PersistentVector
(def e3 (assoc e2 :trace
               (map (fn [elem]
                      (let [classname-str (str (first elem))
                            resource-str (str (:resource (class-info classname-str)))]
                        (conj elem resource-str)))
                    (:trace e2))))
(pprint e3)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; try out ubergraph features

(require '[ubergraph.core :as uber]
         '[ubergraph.alg :as ualg])

;; For cljol, I want only directed edges, which represent references
;; from one object to another.

;; I want to explicitly allow multiple parallel edges from node A to
;; B, which represents the situation when the Java object A has
;; multiple references in multiple per-class fields that all refer to
;; Java object B.  I want each of those edges to have independent
;; attributes from each other, e.g. a :field-name attribute, and I
;; want it to be possible to have 0 or more edges from A to B, and
;; independently 0 or more edges from B to A.


(def g1 (uber/multidigraph))
g1
(type g1)
(uber/pprint g1)

(def g2 (-> g1
            (uber/add-nodes-with-attrs [57 {:name "a" :label "size 24 bytes\nlabel a" :shape :rect}]
                                       [32 {:name "b" :label "label b"}]
                                       [42 {:label "the answer"}])
            (uber/add-edges [57 32 {:field-name "x" :label "fld_x"}]
                            [57 32 {:field-name "y" :label "_y"}]
                            [57 32 {:field-name "y" :label "_y"}]
                            [32 57 {:field-name "parent"}]
                            [32 42 {:label "got here"}]
                            )))
(uber/pprint g2)
(uber/viz-graph g2 {:rankdir :LR})

(defn write-dot-file [g fname opts]
  (uber/viz-graph g (merge opts {:save {:filename fname :format :dot}})))
(defn write-pdf-file [g fname opts]
  (do (uber/viz-graph g (merge opts {:save {:filename fname :format :pdf}}))
      ;; Avoid returning the dot file as a string, as viz-graph does,
      ;; just so I don't see it in my REPL.
      nil))
(write-pdf-file g2 "g2.pdf" {:rankdir :LR})


(require '[cljol.dig9 :as d])
(d/view g2)


;; ubergraph's implementation of successors calls distinct, so
;; even if there are parallel edges from node u to v, successors
;; will include v in the returned list only once.

(def g1 (uber/multidigraph [1 1 {:color :red}]
                           [1 1 {:color :blue}]))
(def g1 (gr/remove-all-attrs g))
;(def g1 g)
(uber/pprint g1)
(uber/successors g1 1)
(gr/dense-integer-node-labels g1)
(gr/edge-vectors g1)

;; Using this to reload modified code from this file will I hope
;; preserve line numbers from the file in stack traces.  I know that
;; doing the inf-clojure method of eval'ing a modified function does
;; not.
(load-file "src/clj/cljol/graph.clj")

(def scc (gr/scc-tarjan g1))
(pprint scc)

(def e1 *e)
(pprint (Throwable->map e1))
(require 'clojure.main)
(clojure.main/report-error e1)


(defn reduce-dag-experiment1
  "Experimental code to see how much smaller it makes DAGs that are
  returned by scc-graph.  If it gives significant enough reductions,
  it could enable linear time calculation of reachable-node-stats for
  a larger class of object graphs.

  Assumes that the input graph g contains no cycles."
  [g]
  (let [outdeg (into {} (for [n (uber/nodes g)]
                          [n (uber/out-degree g n)]))
;;        _ (println "outdeg=" outdeg)
        in-1-out-0 (filter #(and (= 1 (uber/in-degree g %))
                                 (= 0 (uber/out-degree g %)))
                           (uber/nodes g))]
    (loop [in-1-out-0 in-1-out-0
           outdeg outdeg
           removed-nodes (transient [])]
;;      (println "in-1-out-0" in-1-out-0)
      (if (first in-1-out-0)  ;; doesn't support nil as a node, but oh well
        (let [w (first in-1-out-0)
              v (first (uber/predecessors g w))
;;              _ (println "removing w=" w " with predecessor v=" v
;;                         " it had outdeg=" (outdeg v))
              outdeg (update outdeg v dec)
              new-node? (and (zero? (outdeg v))
                             (= 1 (uber/in-degree g v)))]
          ;;(println "remove" w "new-node?" new-node? "neighbor" v)
          (recur (if new-node?
                   (cons v (rest in-1-out-0))
                   (rest in-1-out-0))
                 outdeg
                 (conj! removed-nodes w)))
        ;; else
        (uber/remove-nodes* g (persistent! removed-nodes))))))


(defn reduce-dag-experiment2
  "Experimental code to see how much smaller it makes DAGs that are
  returned by scc-graph.  If it gives significant enough reductions,
  it could enable linear time calculation of reachable-node-stats for
  a larger class of object graphs.

  Assumes that the input graph g contains no cycles."
  [g]
  (let [indeg (into {} (for [n (uber/nodes g)]
                         [n (uber/in-degree g n)]))
        in-0-out-1 (filter #(and (= 0 (uber/in-degree g %))
                                 (= 1 (uber/out-degree g %)))
                           (uber/nodes g))]
    (loop [in-0-out-1 in-0-out-1
           indeg indeg
           removed-nodes (transient [])]
      (if (first in-0-out-1)  ;; doesn't support nil as a node, but oh well
        (let [v (first in-0-out-1)
              w (first (uber/successors g v))
              indeg (update indeg w dec)
              new-node? (and (zero? (indeg w))
                             (= 1 (uber/out-degree g w)))]
          (recur (if new-node?
                   (cons w (rest in-0-out-1))
                   (rest in-0-out-1))
                 indeg
                 (conj! removed-nodes v)))
        ;; else
        (uber/remove-nodes* g (persistent! removed-nodes))))))


(defn test-closure [x]
  (loop [y 0]
    (if (< y x)
      (let [foo (fn [z]
                  (println "z=" z "y=" y))]
        (foo x)
        (recur (inc y))))))

(test-closure 4)

;; z= 4 y= 0
;; z= 4 y= 1
;; z= 4 y= 2
;; z= 4 y= 3

(def bounded1-stats
{2 25915,
 3 10628,
 4 10181,
 5 2320,
 6 1854,
 7 1253,
 8 1229,
 9 1935,
 10 1608,
 11 734,
 12 1442,
 13 1360,
 14 699,
 15 671,
 16 644,
 17 387,
 18 947,
 19 654,
 20 289,
 21 402,
 22 570,
 23 253,
 24 391,
 25 344,
 26 452,
 27 202,
 28 201,
 29 127,
 30 111,
 31 126,
 32 127,
 33 64,
 34 144,
 35 111,
 36 94,
 37 100,
 38 99,
 39 138,
 40 108,
 41 76,
 42 94,
 43 61,
 44 64,
 45 58,
 46 95,
 47 53,
 48 59,
 49 62,
 50 48,
 51 35,
 52 59175})

(def bounded4-stats
{2 27109,
 3 22467,
 4 19518,
 5 3112,
 6 4600,
 7 1372,
 8 5941,
 9 3724,
 10 2031,
 11 1819,
 12 1812,
 13 1095,
 14 564,
 15 1012,
 16 963,
 17 634,
 18 977,
 19 702,
 20 542,
 21 394,
 22 317,
 23 336,
 24 645,
 25 320,
 26 317,
 27 214,
 28 507,
 29 264,
 30 290,
 31 153,
 32 165,
 33 144,
 34 134,
 35 118,
 36 148,
 37 135,
 38 155,
 39 129,
 40 120,
 41 87,
 42 90,
 43 107,
 44 82,
 45 73,
 46 90,
 47 68,
 48 78,
 49 44,
 50 83,
 51 55,
 52 3171})

(defn frequencies-stats [freq-map]
  (let [{:keys [min-val max-val sum-vals count-vals]}
        (reduce (fn [{:keys [min-val max-val sum-vals count-vals]} [val cnt]]
                  {:min-val (min min-val val)
                   :max-val (max max-val val)
                   :sum-vals (+ sum-vals (* val cnt))
                   :count-vals (+ count-vals cnt)})
                {:min-val Double/POSITIVE_INFINITY
                 :max-val Double/NEGATIVE_INFINITY
                 :sum-vals 0
                 :count-vals 0}
                freq-map)]
    {:min min-val
     :max max-val
     :sum sum-vals
     :count count-vals
     :avg (/ (* 1.0 sum-vals) count-vals)}))

;; bounded1-stats
;; calculated :bounded total sizes: 20271.225107 msec, 12 gc-count, 232 gc-time-msec
(frequencies-stats bounded1-stats)
;; => {:min 2, :max 52, :sum 3537476, :count 128794, :avg 27.466155255679613}

;; bounded4-stats
;; Calculated num-reachable-nodes and total-size  for scc-graph in: 281970.898172 msec, 187 gc-count, 576 gc-time-msec
;; calculated :bounded4 total sizes: 287164.087059 msec, 190 gc-count, 1650 gc-time-msec
(frequencies-stats bounded4-stats)
;; => {:min 2, :max 52, :sum 845280, :count 109027, :avg 7.752941931815055}


;; This leaves the map whose value is (get-in [:node-map
;; src :out-edges]) with the value of an empty set, if the last edge
;; out of node src is removed.
(update-in [:node-map src :out-edges dest] disj edge)

;; It would be a little bit nicer for later calls to successors and
;; predecessors not to have this case to deal with, if instead the key
;; dest were removed from the map when this happens.
(update-in [:node-map src :out-edges] remove-edge-also-node-if-last-edge dest edge)


(defn remove-edge-also-node-if-last-edge [node->edge-set node edge]
  (let [remaining-edges (disj (node->edge-set node) edge)]
    (if (seq remaining-edges)
      (assoc node->edge-set node remaining-edges)
      (dissoc node->edge-set node))))

(defn remove-edge2
  [g edge]
  ;; Check whether edge exists before deleting
  (let [{:keys [src dest id] :as edge} (edge-description->edge g edge)]
    (if (get-in g [:node-map src :out-edges dest edge])
      (if-let
        [reverse-edge (other-direction g edge)]
        (-> g
          (update-in [:attrs] dissoc id)
          ;;(update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :out-edges]
                     remove-edge-also-node-if-last-edge dest edge)
          ;;(update-in [:node-map src :in-edges dest] disj reverse-edge)
          (update-in [:node-map src :in-edges]
                     remove-edge-also-node-if-last-edge dest reverse-edge)
          (update-in [:node-map src :in-degree] dec)
          (update-in [:node-map src :out-degree] dec)
          ;;(update-in [:node-map dest :out-edges src] disj reverse-edge)
          (update-in [:node-map dest :out-edges]
                     remove-edge-also-node-if-last-edge src reverse-edge)
          ;;(update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-edges]
                     remove-edge-also-node-if-last-edge src edge)
          (update-in [:node-map dest :in-degree] dec)
          (update-in [:node-map dest :out-degree] dec))
        (-> g
          (update-in [:attrs] dissoc id)
          ;;(update-in [:node-map src :out-edges dest] disj edge)
          (update-in [:node-map src :out-edges]
                     remove-edge-also-node-if-last-edge dest edge)
          (update-in [:node-map src :out-degree] dec)
          ;;(update-in [:node-map dest :in-edges src] disj edge)
          (update-in [:node-map dest :in-edges]
                     remove-edge-also-node-if-last-edge src edge)
          (update-in [:node-map dest :in-degree] dec)))
      g)))


;; Demonstrate that Loom/Ubergraph do not support nil or false as node
;; values, at least not for ubergraph.alg/pre-traverse function.

(require '[ubergraph.core :as uber]
	 '[ubergraph.alg :as ualg]
         '[loom.graph :as lg]
         '[loom.alg :as lalg])

;; numbers as nodes, showing the expected kind of correct output for
;; pre-traverse.
(def g1 (uber/digraph [1 5] [5 1]))
(uber/pprint g1)
(take 10 (ualg/pre-traverse g1 1))
;; => (1 5)  good
(take 10 (ualg/pre-traverse g1 5))
;; => (5 1)  good

;; nil as a node can lead to wrong results
(def g1 (uber/digraph [nil 5] [5 nil]))
(uber/pprint g1)
(take 10 (ualg/pre-traverse g1 5))
;; => (5)  not good.  should include both nodes
(take 10 (ualg/pre-traverse g1 nil))
;; => ()  not good.  should include both nodes

;; I suspect that pre-traverse is not the only function that can give
;; wrong results when used as a node value.  I just noticed it there
;; first.

;; false as a node can also lead to wrong results
(def g1 (uber/digraph [false 5] [5 false]))
(uber/pprint g1)
(take 10 (ualg/pre-traverse g1 5))
;; => (5)  not good.  should include both nodes
(take 10 (ualg/pre-traverse g1 false))
;; => ()  not good.  should include both nodes

;; true is ok
(def g1 (uber/digraph [true 5] [5 true]))
(uber/pprint g1)
(take 10 (ualg/pre-traverse g1 5))
;; => (5 true)  good
(take 10 (ualg/pre-traverse g1 true))
;; => (true 5)  good


;; Note that this wrong behavior for nodes with values nil and false
;; is present in Loom, too.  It is not unique to ubergraph.

(def g2 (lg/graph [1 2] [2 1]))
g2
(take 10 (lalg/pre-traverse g2 1))
;; => (1 2)  good
(take 10 (lalg/pre-traverse g2 2))
;; => (2 1)  good

(def g2 (lg/graph [5 nil] [nil 5]))
g2
(take 10 (lalg/pre-traverse g2 5))
;; => (5)  not good.  should include both nodes
(take 10 (lalg/pre-traverse g2 nil))
;; => ()  not good.  should include both nodes

(def g2 (lg/graph [5 false] [false 5]))
g2
(take 10 (lalg/pre-traverse g2 5))
;; => (5)  not good.  should include both nodes
(take 10 (lalg/pre-traverse g2 false))
;; => ()  not good.  should include both nodes

(def g2 (lg/graph [5 true] [true 5]))
g2
(take 10 (lalg/pre-traverse g2 5))
;; => (5 true)  good
(take 10 (lalg/pre-traverse g2 true))
;; => (true 5)  good

(require '[cljol.reflection-test-helpers :as t]
         '[clj-memory-meter.core :as mm])

(class clojure.core__init)
(def x clojure.core__init)
(class x)
(mm/measure x :debug 10 :bytes true :shallow true)
