;; Scratch pad of code for using in REPL sessions while developing cljol

(do
(require '[cljol.dig9 :as d]
         '[cljol.graph :as gr]
	 '[ubergraph.core :as uber]
	 '[ubergraph.alg :as ualg])
(def opts
  {:node-label-functions
   [#'d/address-decimal
    #'d/size-bytes
    #'d/total-size-bytes
    #'d/class-description
    #'d/field-values
    #'d/non-realizing-javaobj->str]
   :reachable-objmaps-debuglevel 1
   :consistent-reachable-objects-debuglevel 1
   :graph-of-reachable-objects-debuglevel 1
;;   :calculate-total-size-node-attribute :complete
   :calculate-total-size-node-attribute :bounded
;;   :calculate-total-size-node-attribute nil
   :slow-instance-size-checking? true
;;   :stop-walk-at-references false  ;; default true
   })
(def v1 (vector 2))
)

(def v1 (list 2))
(def v1 (class 5))
(def v1 (vec (range 4)))
(def g nil)
(System/gc)
(def g (d/sum [v1] opts))
(def g2 (d/sum [(gr/remove-all-attrs g)] opts))
(def g (d/sum [#'v1] opts))
(d/view-graph g)
(d/view-graph g2)
(d/view-graph g2 {:save {:filename "g2.pdf" :format :pdf}})

(def scct (gr/scc-tarjan g))
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
(def sccg2 (gr/reduce-dag-experiment1 sccg))
(def sccg2 nil)
(gsize sccg2)
(uber/pprint sccg2)

(count (filter #(and (= 1 (count (uber/successors sccg2 %)))
                     (= 1 (count (uber/predecessors sccg2 %))))
               (uber/nodes sccg2)))
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
  (let [in-0-out-1 (filter #(and (= 0 (count (uber/predecessors g %)))
                                  (= 1 (count (uber/successors g %))))
                            (uber/nodes g))
         in-1-out-0 (filter #(and (= 1 (count (uber/predecessors g %)))
                                  (= 0 (count (uber/successors g %))))
                            (uber/nodes g))]
    (loop [in-0-out-1 in-0-out-1
           in-1-out-0 in-1-out-0
           g2 g]
;;      (println "in-0-out-1" in-0-out-1)
;;      (println "in-1-out-0" in-1-out-0)
      (cond
        (next in-0-out-1)  ;; true if at least 1 node in this list
        (let [v (first in-0-out-1)
              _ (assert (= 1 (count (uber/successors g2 v))))
              _ (assert (= 0 (count (uber/predecessors g2 v))))
              w (first (uber/successors g2 v))
              _ (assert (>= (count (uber/predecessors g2 w)) 1))
              new-node? (and (= 1 (count (uber/predecessors g2 w)))
                             (= 1 (count (uber/successors g2 w))))]
;;          (println "remove" v "new-node?" new-node? "neighbor" w)
          (recur (if new-node?
                   (cons w (rest in-0-out-1))
                   (rest in-0-out-1))
                 in-1-out-0
                 (uber/remove-nodes g2 v)))
        
        (next in-1-out-0)  ;; true if at least 1 node in this list
        (let [w (first in-1-out-0)
              _ (assert (= 0 (count (uber/successors g2 w))))
              _ (assert (= 1 (count (uber/predecessors g2 w))))
              v (first (uber/predecessors g2 w))
              _ (assert (>= (count (uber/successors g2 v)) 1))
              new-node? (and (= 1 (count (uber/predecessors g2 v)))
                             (= 1 (count (uber/successors g2 v))))]
;;          (println "remove" w "new-node?" new-node? "neighbor" v)
          (recur in-0-out-1
                 (if new-node?
                   (cons v (rest in-1-out-0))
                   (rest in-1-out-0))
                 (uber/remove-nodes g2 w)))
        
        :else g2))))
