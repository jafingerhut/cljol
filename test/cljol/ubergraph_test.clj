(ns cljol.ubergraph-test
  (:import (java.io File))
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [medley.core :as med]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]
            [ubergraph.invariants :as uberi]
            [cljol.performance :as perf]
            [cljol.ubergraph-extras :as ubere]))


(defn satisfies-invariants [g]
  (let [{ret :ret :as p} (perf/my-time (uberi/check-invariants g))]
    (print "Invariants" (if (:error ret) "VIOLATED" "ok")
           "on graph with" (count (uber/nodes g)) "nodes"
           (count (uber/edges g)) "edges in: ")
    (perf/print-perf-stats p)
    (is (= false (:error ret)))))


(defn sh-out [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (println "exit status" exit)
    (println "out" out)
    (println "err" err)))


(defn base-fname [basename opts]
  (str (:output-dir opts) File/separator basename))


(defn gen [g basename opts]
  (println "Writing file" (str (base-fname basename opts) ".dot") "...")
  (uber/viz-graph g {:auto-label false
                     :save {:filename (str (base-fname basename opts) ".dot")
                            :format :dot}})
  (println "Gen file" (str (base-fname basename opts) ".pdf") "...")
  (sh-out "dot" "-Tpdf" (str (base-fname basename opts) ".dot")
          "-o" (str (base-fname basename opts) ".pdf"))
  (println "Writing file" (str (base-fname basename opts) "-auto.dot") "...")
  (uber/viz-graph g {:auto-label true
                     :save {:filename (str (base-fname basename opts) "-auto.dot")
                            :format :dot}})
  (println "Gen file" (str (base-fname basename opts) "-auto.pdf") "...")
  (sh-out "dot" "-Tpdf" (str (base-fname basename opts) "-auto.dot")
          "-o" (str (base-fname basename opts) "-auto.pdf"))
  )


(deftest graphs-with-labels-bad-for-graphviz-dot
  (let [opts {:output-dir "doc/tryout-images"}
        ;; ensure that the directory exists, creating it if not
        _ (io/make-parents (:output-dir opts) "tmp")
        strings [(str (char 0))
                 (str (char 1))
                 "\\"
                 (str (char 65533))
                 (str (char 65534))
                 (str (char 65535))]]
    (doseq [idx (range (count strings))]
      (let [s (strings idx)
            g (uber/multidigraph [1 {:label s}]
                                 [2 {:label (str (seq s))}]
                                 [1 2 {:label "foo"}])]
        (gen g (str "g" idx) opts)))))


(defn vec->position-map
  "Given a vector v, return a map whose keys are the distinct elements
  of v, and the value associated with element e is the largest index
  in which e appears in v."
  [v]
  (persistent! (reduce-kv (fn [acc idx val]
                            (assoc! acc val idx))
                          (transient {})
                          v)))

(comment

(vec->position-map ["a" "b" "c"])
;; {"a" 0, "b" 1, "c" 2}

(vec->position-map ["a" "b" "c" "a"])
;; {"a" 3, "b" 1, "c" 2}

)

(defn correct-topo-order
  "Simple test to determine whether maybe-topo-order is a topological
  ordering of the vertices of graph.  It will also give a good result
  if `maybe-topo-order` includes only some of the nodes of `graph`,
  but the ones present are in topological order.  Requires O((n+m)*C)
  time where n is the number of vertices, and m the number of edges,
  in graph, and C is the time to perform lookup and/or insert
  operations on Clojure sets, maps, or vectors with size up to
  max{n,m}.

  Returns a map that always contains the key :pass with a boolean
  value, true if no problems were found, false if any problem was
  found.

  If a problem was found, there will be another key :error whose value
  is a keyword describing the kind of problem found, and :description
  whose value is a string containing a brief description of the
  problem.  There may also be other keys, which ones depending upon
  the value associated with the key :error"
  [graph maybe-topo-order]
  (let [n (count (uber/nodes graph))]
    (if (< n (bounded-count (inc n) maybe-topo-order))
      {:pass false
       :error :topo-order-too-long
       :description (format (str "Found more elements in maybe-topo-order"
                                 " than the graph's %d nodes.")
                            n)}
      (let [t (vec maybe-topo-order)
            non-nodes (remove #(uber/has-node? graph %) t)
            node->tidx (vec->position-map t)]
        (cond
          (seq non-nodes)
          {:pass false
           :error :topo-order-contains-non-node
           :description (format (str "Found element %s of maybe-topo-order"
                                     " that is not a node in the graph")
                                (first non-nodes))}

          (< (count node->tidx) (count t))
          {:pass false
           :error :topo-order-contains-duplicates
           :description (format (str "Found element %s of maybe-topo-order"
                                     " that is duplicated")
                                (->> (frequencies t)
                                     (med/filter-vals #(> % 1))
                                     keys
                                     first))}
          
          :else
          (let [bad-edges (filter (fn bad-edge? [e]
                                    (let [s (uber/src e)
                                          d (uber/dest e)]
                                      (and (contains? node->tidx s)
                                           (contains? node->tidx d)
                                           (>= (node->tidx (uber/src e))
                                               (node->tidx (uber/dest e))))))
                                  (uber/edges graph))]
            (if (seq bad-edges)
              (let [bad-edge (first bad-edges)
                    s (uber/src bad-edge)
                    d (uber/dest bad-edge)]
                {:pass false
                 :error :graph-contains-edge-violating-topo-order
                 :description
                 (format (str "Graph contains edge with source node %s"
                              " and destination node %s.  Their indexes in"
                              " maybe-topo-order are %d >= %d, which should"
                              " never be true for a topological order.")
                         s d (node->tidx s) (node->tidx d))})
              {:pass true})))))))

(comment

(def g2 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [2 3 {:label "e2->3"}]))
(correct-topo-order g2 [1])
;; {:pass true}
(correct-topo-order g2 [1 2 3 4])
;; :error :topo-order-too-long
(correct-topo-order g2 [1 2 4])
;; :error :topo-order-contains-non-node
(correct-topo-order g2 [1 2 1])
;; :error :topo-order-contains-duplicates
(correct-topo-order g2 [1 3 2])
;; :error :graph-contains-edge-violating-topo-order
(correct-topo-order g2 [1 3])
;; {:pass true}
(correct-topo-order g2 [1 2 3])
;; {:pass true]
(correct-topo-order g2 [2 1])
;; :error :graph-contains-edge-violating-topo-order

)


(deftest topological-ordering-tests
  (let [g1 (uber/multidigraph [1 {:label "x"}]
                              [2 {:label "y"}]
                              [1 2 {:label "foo"}])
        ;; g2 is a DAG
        g2 (uber/multidigraph [1 {:label "n1"}]
                              [2 {:label "n2"}]
                              [3 {:label "n3"}]
                              [1 2 {:label "e1->2"}]
                              [1 3 {:label "e1->3"}]
                              [2 3 {:label "e2->3"}])
        ;; g3 contains a self loop edge on node 1
        g3 (uber/multidigraph [1 {:label "n1"}]
                              [2 {:label "n2"}]
                              [3 {:label "n3"}]
                              [1 1 {:label "e1->1"}]
                              [1 2 {:label "e1->2"}]
                              [1 3 {:label "e1->3"}]
                              [2 3 {:label "e2->3"}])
        ;; g4 contains a cycle of 3 edges, but no shorter cycle
        g4 (uber/multidigraph [1 {:label "n1"}]
                              [2 {:label "n2"}]
                              [3 {:label "n3"}]
                              [1 2 {:label "e1->2"}]
                              [3 1 {:label "e3->1"}]
                              [2 3 {:label "e2->3"}])
        ;; g5 is a DAG with parallel edges between some vertices
        g5 (uber/multidigraph [1 {:label "n1"}]
                              [2 {:label "n2"}]
                              [3 {:label "n3"}]
                              [1 2 {:label "e1->2"}]
                              [1 3 {:label "e1->3"}]
                              [1 2 {:label "e1->2b"}]
                              [1 2 {:label "e1->2c"}]
                              [2 3 {:label "e2->3"}])
        gbig (ubere/read-ubergraph-as-edges
              "resources/dimultigraph-129k-nodes-272k-edges.edn")
        gbigcondensation (:scc-graph (ubere/scc-graph2 gbig))]

    (doseq [g [g1 g2 g3 g4 g5 gbig gbigcondensation]]
      (satisfies-invariants g))

    ;; graphs where cycles should be detected, and no topological
    ;; ordering returned:
    (doseq [g [g3 g4 gbig]]
      (is (= nil (ualg/topsort g)))
      (is (= true (:has-cycle? (ubere/topsort2 g)))))

    ;; acyclic graphs, so topological orderings should be returned:
    (doseq [g [g1 g2 g5]]
      (let [ret (ualg/topsort g)]
        (is (= {:pass true} (correct-topo-order g ret))))
      (let [ret (ubere/topsort2 g)]
        (is (= false (:has-cycle? ret)))
        (is (= {:pass true} (correct-topo-order g (:topological-order ret))))))

    (let [g gbigcondensation
          {topsort-ret :ret :as ptopsort} (perf/my-time (ualg/topsort g))
          _ (do (print "Using ubergraph.alg/topsort, found topo order in: ")
                (perf/print-perf-stats ptopsort))
          {topsort2-ret :ret :as ptopsort2} (perf/my-time (ubere/topsort2 g))
          _ (do (print "Using cljol.ubergraph-extras/topsort2, found topo order in: ")
                (perf/print-perf-stats ptopsort2))]
      (is (= {:pass true} (correct-topo-order g topsort-ret)))
      (is (= false (:has-cycle? topsort2-ret)))
      (is (= {:pass true}
             (correct-topo-order g (:topological-order topsort2-ret)))))))


(comment

(require '[medley.core :as med]
         '[loom.graph :as lg]
         '[ubergraph.core :as uber]
         '[ubergraph.alg :as ualg]
         '[cljol.ubergraph-extras :as ubere])

(require '[cljol.ubergraph-extras :as ubere] :reload)

(pprint (into (sorted-map) (ns-publics 'ubergraph.core)))

(do
;; g1 is a DAG
(def g1 (uber/multidigraph [1 {:label "x"}]
                           [2 {:label "y"}]
                           [1 2 {:label "foo"}]))
;; g2 is a DAG
(def g2 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [2 3 {:label "e2->3"}]))
;; g3 contains a self loop edge on node 1
(def g3 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 1 {:label "e1->1"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [2 3 {:label "e2->3"}]))
;; g4 contains a cycle of 3 edges, but no shorter cycle
(def g4 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [3 1 {:label "e3->1"}]
                           [2 3 {:label "e2->3"}]))
;; g5 is a DAG with parallel edges between some vertices
(def g5 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [1 2 {:label "e1->2b"}]
                           [1 2 {:label "e1->2c"}]
                           [2 3 {:label "e2->3"}]))
(def gbig (ubere/read-ubergraph-as-edges "resources/dimultigraph-129k-nodes-272k-edges.edn"))
)
(def gbigcondensation (:scc-graph (ubere/scc-graph2 gbig)))
(count (uber/nodes gbig))
(count (uber/nodes gbigcondensation))
(uber/in-degree g5 2)
;; 3  -- seems to be the number of edges, if there are multiple parallel edges, not the number of predecessor nodes as the doc string currently suggests
(pprint (uber/in-edges g5 2))
;; returns 3 parallel edges
(pprint (uber/out-edges g5 1))
;; returns 3 parallel edges
(doc uber/in-degree)

(uber/nodes g1)
(class (uber/nodes g1))
(count (uber/nodes g1))
(uber/has-node? g1 3)
;; false
(uber/has-node? g1 2)
;; true
(pprint (uber/edges g2))
(map (juxt uber/src uber/dest) (uber/edges g2))

(ualg/topsort g1)
;; (1 2)
(class (ualg/topsort g1))
;; clojure.lang.LazySeq
(ualg/topsort g2)
;; (1 2 3)
(ualg/topsort g3)
;; nil   OK,  because self loop edge is a cycle.
(ualg/topsort g4)
;; nil   OK, because there is a cycle of 3 edges.
(ualg/topsort g5)
;; (1 2 3)
(def tbig (ualg/topsort gbig))
(class tbig)
;; nil
(def tbigc (ualg/topsort gbigcondensation))
(class tbigc)
(count tbigc)
(correct-topo-order gbigcondensation tbigc)
;; {:pass true}

(ubere/topsort2 g1)
(ubere/topsort2 g2)
(ubere/topsort2 g3)
(ubere/topsort2 g4)
(ubere/topsort2 g5)
(def tbig2 (ubere/topsort2 gbig))
tbig2
;; {:has-cycle? true}
(def tbigc (ubere/topsort2 gbigcondensation))
(keys tbigc)
(correct-topo-order gbigcondensation (:topological-order tbigc))
;; {:pass true}

)
