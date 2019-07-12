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


(def v1 (class 5))
(def v1 (vec (range 4)))
(def g nil)
(System/gc)
(def g (d/sum [v1] opts))
(def g (d/sum [#'v1] opts))
(d/view-graph g)

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
