(ns cljol.ubergraph-test
  (:import (java.io File))
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [criterium.core :as crit]
            [medley.core :as med]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]
            [ubergraph.invariants :as uberi]
            [cljol.performance :as perf]
            [criterium.core :as crit]
            [cljol.ubergraph-extras :as ubere]))


(def criterium-opts {:warmup-jit-period (* 10 crit/s-to-ns),
                     :samples 30
                     :target-execution-time (* 1 crit/s-to-ns)})

(def gbig
  (delay
   (let [g (ubere/read-ubergraph-as-edges
            "resources/dimultigraph-129k-nodes-272k-edges.edn")]
     g)))

(def gbigcondensation
  (delay
   (let [g (:scc-graph (ubere/scc-graph2 @gbig))]
     g)))

(defn satisfies-invariants [g]
  (let [{ret :ret :as p} (perf/time (uberi/check-invariants g))]
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


(defmacro benchmark-more-stats
  "Run criterium.core/benchmark on the given expression `expr`, but
  wrap each execution of the expression in a call of the `time`
  macro, and wrap the entire call to benchmark in a `time` macro as
  well.  The goal is to be able to calculate the total time, number of
  GCs, and whatever else `time` records, for all executions of
  `expr`, and for `benchmark` as well, then calculate the difference
  between those, to see how much time is spent in `benchmark` code
  itself, outside of evaluations of `expr`.

  TBD: Document the return value.
  "
  [expr options]
  `(let [{[benchmark-ret# times#] :ret :as total-perf#}
         (time (let [times# (atom [])
                     benchmark-ret# (crit/benchmark
                                     (perf/time-record-results ~expr times#)
                                     ~options)]
                 [benchmark-ret# @times#]))]
     {:benchmark-stats benchmark-ret#
      :total-benchmark-perf (dissoc total-perf# :ret)
      :expression-perfs (mapv #(dissoc % :ret) times#)
      :total-expression-perf (reduce perf/add-times times#)}))


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
                              [2 3 {:label "e2->3"}])]

    (doseq [g [g1 g2 g3 g4 g5 @gbig @gbigcondensation]]
      (satisfies-invariants g))

    ;; graphs where cycles should be detected, and no topological
    ;; ordering returned:
    (doseq [g [g3 g4 @gbig]]
      (is (= nil (ualg/topsort g)))
      (is (= true (:has-cycle? (ubere/topsort2 g)))))

    ;; acyclic graphs, so topological orderings should be returned:
    (doseq [g [g1 g2 g5]]
      (let [ret (ualg/topsort g)]
        (is (= {:pass true} (correct-topo-order g ret))))
      (let [ret (ubere/topsort2 g)]
        (is (= false (:has-cycle? ret)))
        (is (= {:pass true} (correct-topo-order g (:topological-order ret))))))

    (println "
------------------------------------------------------------
Performance comparison of ubergraph.alg/topsort
versus cljol.ubergraph-extras/topsort2
------------------------------------------------------------")
    (let [g @gbigcondensation
          {topsort-ret :ret :as ptopsort} (perf/time (ualg/topsort g))
          _ (do
              (println "Using ubergraph.alg/topsort, found topo order in:")
              (perf/print-perf-stats ptopsort))
          {topsort2-ret :ret :as ptopsort2} (perf/time (ubere/topsort2 g))
          _ (do
              (println "Using cljol.ubergraph-extras/topsort2, found topo order in:")
              (perf/print-perf-stats ptopsort2))]
      (println "------------------------------------------------------------")
      (is (= {:pass true} (correct-topo-order g topsort-ret)))
      (is (= false (:has-cycle? topsort2-ret)))
      (is (= {:pass true}
             (correct-topo-order g (:topological-order topsort2-ret)))))))

(deftest ^:perf-focus pre-traverse-tests
  (let [_ (println "
------------------------------------------------------------
Forcing criterium to estimate overhead now, before later benchmarking
runs.
------------------------------------------------------------")
        _ (perf/print-perf-stats (perf/time (crit/estimated-overhead!)))
        _ (println "
------------------------------------------------------------
Performance comparison between:
loom: ubergraph.alg/pre-traverse (same as loom.alg/pre-traverse)
versus:
extras: cljol.ubergraph-extras/pre-traverse
------------------------------------------------------------")
        {g :ret :as g-pt} (perf/time @gbig)
        _ (do
            (println "Time to deref @gbig:")
            (perf/print-perf-stats g-pt))
        ;; I found through experimentation that using this start-node
        ;; causes the graph traversal to visit every node.
        start-node 29000736752

        {loom-pt-ret :ret :as ploom-pt}
        (perf/time (ualg/pre-traverse g start-node))

        {loom-pt-all-ret :ret :as ploom-pt-all}
        (perf/time (doall loom-pt-ret))

        n (count loom-pt-all-ret)

        _ (do
            (println)
            (println "loom, fn returned in:")
            (perf/print-perf-stats ploom-pt)
            (println "    (class ret-val)=" (class loom-pt-ret))
            (println "full sequence with" n "nodes consumed in:")
            (perf/print-perf-stats ploom-pt-all))
        
        {extras-pt-ret :ret :as pextras-pt}
        (perf/time (ubere/pre-traverse g start-node))

        {extras-pt-all-ret :ret :as pextras-pt-all}
        (perf/time (doall extras-pt-ret))

        _ (do
            (println)
            (println "extras, fn returned in:")
            (perf/print-perf-stats pextras-pt)
            (println "    (class ret-val)=" (class extras-pt-ret))
            (println "full sequence with" (count extras-pt-all-ret)
                     "nodes consumed in:")
            (perf/print-perf-stats pextras-pt-all))

        _ (do
            (println)
            (println "Using criterium to do more careful measurements...")
            (println))

        _ (println "loom: full sequence with" n "nodes:")
        {loom-bench-stats :benchmark-stats
         loom-perf :total-benchmark-perf
         loom-times :expression-perfs
         loom-tot-times :total-expression-perf}
        (benchmark-more-stats (doall (ualg/pre-traverse g start-node))
                              criterium-opts)
    
        _ (println "extras: full sequence with" n "nodes:")
        {extras-bench-stats :benchmark-stats
         extras-perf :total-benchmark-perf
         extras-times :expression-perfs
         extras-tot-times :total-expression-perf}
        (benchmark-more-stats (doall (ubere/pre-traverse g start-node))
                              criterium-opts)
        ]

    (println "loom: full sequence with" n "nodes consumed in:")
    (crit/report-result loom-bench-stats :verbose)
    (println "    total time for crit/benchmark run:")
    (perf/print-perf-stats loom-perf)
    (println "    total of" (count loom-times)
             "separate time calls for each expression execution:")
    (perf/print-perf-stats loom-tot-times)
    (println "    difference of previous two:")
    (perf/print-perf-stats (perf/subtract-times loom-perf loom-tot-times))
    
    (println)
    (println "extras: full sequence with" n "nodes consumed in:")
    (crit/report-result extras-bench-stats :verbose)
    (println "    total time for crit/benchmark run:")
    (perf/print-perf-stats extras-perf)
    (println "    total of" (count extras-times)
             "separate time calls for each expression execution:")
    (perf/print-perf-stats extras-tot-times)
    (println "    difference of previous two:")
    (perf/print-perf-stats (perf/subtract-times extras-perf extras-tot-times))

    (println "
------------------------------------------------------------")
    ))


(defn uedge-info
  "Given an Ubergraph edge, return a vector of some of the information
  that Ubergraph records about it.  This is only intended to be useful
  in writing some tests of Ubergraph functions that return edges, to
  verify that they have these details versus something else."
  [e]
  [(uber/src e)
   (uber/dest e)
   :directed? (uber/directed-edge? e)
   :mirror-edge? (uber/mirror-edge? e)])


;; Here is a link to a commit on a development branch of a fork of the
;; Ubergraph library that has some proposed extended doc strings for
;; functions, written while answering several questions I had about
;; the behavior of several of the functions in cases involving both
;; directed and undirected edges, parallel edges, and self loop edges:
;; https://github.com/jafingerhut/ubergraph/commit/b7ace35334e5fc6d44458bdf420dd1e7fd70ff4a

;; TBD: How can I gain high confidence that a function that takes
;; Ubergraph as input, e.g. these functions in namespace
;; cljol.ubergraph-extras, work as desired (whatever that means for
;; each one -- it should be documented) for graphs with multiple
;; parallel edges, mixes of directed and undirected edges, and/or self
;; loop edges?

;; pre-traverse*
;; pre-traverse
;; induced-subgraph
;; dense-integer-node-labels - done, documented in comments.
;; edge-vectors - done, documented in comments.
;; scc-tarjan - done except for self loop edges, documented in comments
;; scc-graph
;; scc-graph2 - is there much of an advantage to keeping both this and
;;     scc-graph?
;; dag-reachable-nodes
;; reachable-nodes
;; topsort2
;; remove-loops-and-parallel-edges
;; dag-transitive-reduction-slow
;; TBD: dag-transitive-reduction
;; check-same-reachability-slow


;; Tests some basic behavior of ubergraph.core functions
(deftest ubergraph-tests
  ;; g1 is a directed graph with a self loop edge on node :n1, and has
  ;; 3 parallel edges from :n2 to :n3.  Every edge is directed.
  (let [g1 (uber/multidigraph [:n1] [:n2] [:n3]
                              [:n1 :n1 {:label "e1->1"}]
                              [:n1 :n2 {:label "e1->2"}]
                              [:n1 :n3 {:label "e1->3"}]
                              [:n2 :n1 {:label "e2->1"}]
                              [:n2 :n3 {:label "e2->3 #1"}]
                              [:n2 :n3 {:label "e2->3 #2"}]
                              [:n2 :n3 {:label "e2->3 #3"}])]
    (is (= true (every? uber/directed-edge? (uber/edges g1))))
    ;; includes all 3 parallel edges from :n2 to :n3
    (is (= 3 (count (uber/find-edges g1 :n2 :n3))))
    (is (= 4 (uber/out-degree g1 :n2)))
    (is (= 4 (uber/in-degree g1 :n3)))
    ;; no edges from :n3 to :n2
    (is (= 0 (count (uber/find-edges g1 :n3 :n2))))
    ;; :n3 only appears once in the sequence returned by successors,
    ;; even though there are 3 parallel edges from :n2 to :n3
    (is (= [:n1 :n3] (sort (uber/successors g1 :n2))))
    ;; :n2 only appears once in the sequence returned by predecessors,
    ;; even though there are 3 parallel edges from :n2 to :n3
    (is (= [:n1 :n2] (sort (uber/predecessors g1 :n3))))
    ;; includes self loop edge from :n1 to :n1
    (is (= 3 (uber/out-degree g1 :n1)))

    ;; out-edges and out-degree both include the self loop edge
    ;; from :n1 to :n1 only 1 time, plus the other out-edges once
    ;; each, thus 3 total.
    (let [edges (uber/out-edges g1 :n1)
          degree (uber/out-degree g1 :n1)]
      (is (= [[:n1 :n1 :directed? true :mirror-edge? false]
              [:n1 :n2 :directed? true :mirror-edge? false]
              [:n1 :n3 :directed? true :mirror-edge? false]]
             (sort (map uedge-info edges))))
      (is (= 3 degree))
      (is (= (count edges) degree)))

    ;; Similarly, in-edges and in-degree both include the self loop
    ;; edge from :n1 to :n2 only 1 time, plus the other edge once,
    ;; thus 2 total.
    (let [edges (uber/in-edges g1 :n1)
          degree (uber/in-degree g1 :n1)]
      (is (= [[:n1 :n1 :directed? true :mirror-edge? false]
              [:n2 :n1 :directed? true :mirror-edge? false]]
             (sort (map uedge-info edges))))
      (is (= 2 degree))
      (is (= (count edges) degree))))

  ;; g2 is an undirected graph with a self loop edge on node :n1, and
  ;; has 3 parallel edges from :n2 to :n3.  Every edge is undirected.
  (let [g2 (uber/multigraph [:n1]
                            [:n2]
                            [:n3]
                            [:n1 :n1 {:label "e1<->1"}]
                            [:n1 :n2 {:label "e1<->2 #1"}]
                            [:n1 :n3 {:label "e1<->3"}]
                            [:n2 :n1 {:label "e2<->1 #2"}]
                            [:n2 :n3 {:label "e2<->3 #1"}]
                            [:n2 :n3 {:label "e2<->3 #2"}]
                            [:n2 :n3 {:label "e2<->3 #3"}])]
    (is (= true (every? uber/undirected-edge? (uber/edges g2))))
    ;; includes all 3 parallel edges from :n2 to :n3
    (is (= 3 (count (uber/find-edges g2 :n2 :n3))))
    ;; includes 2 edges between :n1 and :n2
    (is (= 5 (uber/out-degree g2 :n2)))
    (is (= 4 (uber/in-degree g2 :n3)))
    ;; 3 undirected edges between :n3 and :n2 also found when looking from :n3
    (is (= 3 (count (uber/find-edges g2 :n3 :n2))))
    ;; :n3 only appears once in the sequence returned by successors,
    ;; even though there are 3 parallel edges from :n2 to :n3
    (is (= [:n1 :n3] (sort (uber/successors g2 :n2))))
    ;; :n2 only appears once in the sequence returned by predecessors,
    ;; even though there are 3 parallel edges from :n2 to :n3
    (is (= [:n1 :n2] (sort (uber/predecessors g2 :n3))))

    ;; out-edges and out-degree both include the self loop edge from :n1
    ;; to :n1 2 times, plus the other 3 edges once each, thus 5 total.
    (let [edges (uber/out-edges g2 :n1)
          degree (uber/out-degree g2 :n1)]
      (is (= [[:n1 :n1 :directed? false :mirror-edge? false]
              [:n1 :n1 :directed? false :mirror-edge? true]
              [:n1 :n2 :directed? false :mirror-edge? false]
              [:n1 :n2 :directed? false :mirror-edge? true]
              [:n1 :n3 :directed? false :mirror-edge? false]]
             (sort (map uedge-info edges))))
      (is (= 5 degree))
      (is (= (count edges) degree)))

    ;; Similarly, in-edges and in-degree both include the self loop edge
    ;; from :n1 to :n2 2 times, plus the other 3 edges once each, thus 5
    ;; total.
    (let [edges (uber/in-edges g2 :n1)
          degree (uber/in-degree g2 :n1)]
      (is (= [[:n1 :n1 :directed? false :mirror-edge? false]
              [:n1 :n1 :directed? false :mirror-edge? true]
              [:n2 :n1 :directed? false :mirror-edge? false]
              [:n2 :n1 :directed? false :mirror-edge? true]
              [:n3 :n1 :directed? false :mirror-edge? true]]
             (sort (map uedge-info edges))))
      (is (= 5 degree))
      (is (= (count edges) degree))))

  (let [g3 (-> (uber/multidigraph [:n1] [:n2] [:n3]
                                  [:n1 :n1 {:label "e1->1"}]
                                  [:n1 :n2 {:label "e1->2"}]
                                  [:n1 :n3 {:label "e1->3"}]
                                  [:n2 :n3 {:label "e2->3 #1"}]
                                  [:n2 :n3 {:label "e2->3 #2"}])
               (uber/add-undirected-edges* [[:n1 :n1 {:label "e1<->1"}]
                                            [:n2 :n3 {:label "e2<->3"}]
                                            [:n3 :n2 {:label "e3<->2"}]]))]

    (is (= [[:n1 :n1 :directed? false :mirror-edge? false]
            [:n1 :n1 :directed? false :mirror-edge? true]
            [:n1 :n1 :directed? true :mirror-edge? false]
            [:n1 :n2 :directed? true :mirror-edge? false]
            [:n1 :n3 :directed? true :mirror-edge? false]
            [:n2 :n3 :directed? false :mirror-edge? false]
            [:n2 :n3 :directed? false :mirror-edge? true]
            [:n2 :n3 :directed? true :mirror-edge? false]
            [:n2 :n3 :directed? true :mirror-edge? false]
            [:n3 :n2 :directed? false :mirror-edge? false]
            [:n3 :n2 :directed? false :mirror-edge? true]]
           (sort (map uedge-info (uber/edges g3)))))
    ;; every undirected edge is returned twice by uber/edges, once
    ;; mirrored, once not.
    (is (= 6 (count (filter uber/undirected-edge? (uber/edges g3)))))
    ;; every directed edge is returned once by uber/edges
    (is (= 5 (count (filter uber/directed-edge? (uber/edges g3)))))

    ;; includes both directed edges from :n2 to :n3, plus one
    ;; direction of each of the two undirected edges between :n2
    ;; and :n3
    (is (= 4 (count (uber/find-edges g3 :n2 :n3))))
    (is (= [[:n2 :n3 :directed? false :mirror-edge? false]
            [:n2 :n3 :directed? false :mirror-edge? true]
            [:n2 :n3 :directed? true :mirror-edge? false]
            [:n2 :n3 :directed? true :mirror-edge? false]]
           (sort (map uedge-info (uber/find-edges g3 :n2 :n3)))))

    ;; includes both directed edges from :n2 to :n3, plus one
    ;; direction of each of the two undirected edges between :n2
    ;; and :n3
    (is (= 4 (uber/out-degree g3 :n2)))
    ;; includes the 4 edges out of :n2, plus the directed edge
    ;; from :n1 to :n3
    (is (= 5 (uber/in-degree g3 :n3)))
    ;; 2 undirected edges between :n3 and :n2 also found when looking
    ;; from :n3
    (is (= 2 (count (uber/find-edges g3 :n3 :n2))))
    ;; :n1 has all three nodes as successors, including itself because
    ;; of its self loop edges.
    (is (= [:n1 :n2 :n3] (sort (uber/successors g3 :n1))))
    ;; :n1 has only itself as a predecessor
    (is (= [:n1] (sort (uber/predecessors g3 :n1))))
    ;; :n3 only appears once in sequence returned by successors, even
    ;; though there are 4 parallel edges from :n2 to :n3
    (is (= [:n3] (sort (uber/successors g3 :n2))))
    ;; :n2 only appears once in sequence returned by predecessors,
    ;; even though there are 4 parallel edges from :n2 to :n3
    (is (= [:n1 :n2] (sort (uber/predecessors g3 :n3))))

    ;; out-edges and out-degree both include the undirected self loop
    ;; edge from :n1 to :n1 2 times, the directed self loop edge
    ;; from :n1 to :n1 1 time, plus the other 2 edges once each, thus
    ;; 5 total.
    (let [edges (uber/out-edges g3 :n1)
          degree (uber/out-degree g3 :n1)]
      (is (= [[:n1 :n1 :directed? false :mirror-edge? false]
              [:n1 :n1 :directed? false :mirror-edge? true]
              [:n1 :n1 :directed? true :mirror-edge? false]
              [:n1 :n2 :directed? true :mirror-edge? false]
              [:n1 :n3 :directed? true :mirror-edge? false]]
             (sort (map uedge-info edges))))
      (is (= 5 degree))
      (is (= (count edges) degree)))

    ;; Similarly, in-edges and in-degree both include the undirected
    ;; self loop edge from :n1 to :n1 2 times, and the directed self
    ;; loop edge from :n1 to :n1 1 time, thus 3 total.
    (let [edges (uber/in-edges g3 :n1)
          degree (uber/in-degree g3 :n1)]
      (is (= [[:n1 :n1 :directed? false :mirror-edge? false]
              [:n1 :n1 :directed? false :mirror-edge? true]
              [:n1 :n1 :directed? true :mirror-edge? false]]
             (sort (map uedge-info (uber/in-edges g3 :n1)))))
      (is (= 3 degree))
      (is (= (count edges) degree)))))


(comment

(require '[cljol.ubergraph-test :as ut])
(ut/pre-traverse-tests)

(require '[medley.core :as med]
         '[loom.graph :as lg]
         '[ubergraph.core :as uber]
         '[ubergraph.alg :as ualg]
         '[cljol.ubergraph-extras :as ubere]
         '[cljol.performance :as perf]
         '[cljol.dig9 :as d])


;; This Java API I found on this page:
;; https://stackoverflow.com/questions/35842/how-can-a-java-program-get-its-own-process-id
;; It was apparently introduced with JDK 9, and does not exist in JDK 8

(def my-pid (. (java.lang.ProcessHandle/current) pid))
(def my-pid 85421)
my-pid
;; Next expression below ...
;; + works on AdoptOpenJDK 8
;; + throws exception with ZuluOpenJDK 11, something related to Java modules
(def pcgcum (org.gridkit.jvmtool.PerfCounterGcCpuUsageMonitor. my-pid))

(class pcgcum)
(. pcgcum isAvailable)
;; true
(. pcgcum getYoungGcCpu)
(. pcgcum getOldGcCpu)

(require '[cljol.ubergraph-extras :as ubere] :reload)

(pprint (into (sorted-map) (ns-publics 'ubergraph.core)))
(pprint (into (sorted-map) (ns-publics 'cljol.dig9)))

(d/view [g2])
(doc d/view-graph)
(doc d/write-drawing-file)
(doc d/view)
(d/write-drawing-file [g2] "g2.pdf" :pdf)
(class g2)
(pprint (supers (class g2)))
(class (:node-map g2))
(pprint (:node-map g2))
(doc uber/out-degree)
(doc uber/successors)

(defn might-be-transitive-reduction? [g-orig g-tr]
  (if (not= (set (uber/nodes g-orig)) (set (uber/nodes g-tr)))
    {:pass false
     :error :different-node-sets
     :description "g-orig and g-tr have different sets of nodes"}
    (let [ret (ubere/check-same-reachability-slow g-orig g-tr)]
      (if (:pass ret)
        ret
        (assoc ret
               :error :different-reachability
               :description "g-orig and g-tr have different reachability")))))

(defn tr-slow-plus-check [g]
  (let [gtr (ubere/dag-transitive-reduction-slow g)
        ret (might-be-transitive-reduction?  g gtr)]
    {:transitive-reduction gtr
     :is-transitive-reduction? ret}))

(do
;; g1 is a DAG
(def g1 (uber/multidigraph [1 {:label "x"}]
                           [2 {:label "y"}]
                           [1 2 {:label "foo"}]))
;(pprint (uber/edges g1))
;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g1)))
;(def x (tr-slow-plus-check g1))
;(:is-transitive-reduction? x)
;; g2 is a DAG
(def g2 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [2 3 {:label "e2->3"}]))
;(pprint (uber/edges g2))
;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g2)))
;(def x (tr-slow-plus-check g2))
;(:is-transitive-reduction? x)
;; g3 contains a self loop edge on node 1
(def g3 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 1 {:label "e1->1"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [2 3 {:label "e2->3"}]))
;(def x (tr-slow-plus-check g3))
;(:is-transitive-reduction? x)
;(pprint (uber/edges g3))
;(pprint (uber/edges (remove-loops-and-parallel-edges g3)))
;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g3)))
;(uber/find-edges g3 1 1)
;(pprint (uber/edges g3))
;(pprint (uber/edges (apply uber/remove-edges g3 (uber/find-edges g3 1 1))))
;(uber/out-degree g3 1)
;(uber/successors g3 1)
;(uber/predecessors g3 1)
;; g4 contains a cycle of 3 edges, but no shorter cycle
(def g4 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [3 1 {:label "e3->1"}]
                           [2 3 {:label "e2->3"}]))
;(def x (tr-slow-plus-check g4))
;(:is-transitive-reduction? x)
;(pprint (uber/edges g4))
;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g4)))
;; g5 is a DAG with parallel edges between some vertices
(def g5 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [3 {:label "n3"}]
                           [1 2 {:label "e1->2"}]
                           [1 3 {:label "e1->3"}]
                           [1 2 {:label "e1->2b"}]
                           [1 2 {:label "e1->2c"}]
                           [2 3 {:label "e2->3"}]))
;(def x (tr-slow-plus-check g5))
;(:is-transitive-reduction? x)
;(pprint (uber/find-edges g5 1 2))
;(pprint (uber/edges g5))
;(pprint (uber/edges (remove-loops-and-parallel-edges g5)))
;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g5)))
;(uber/out-degree g5 1)
;(uber/out-edges g5 1)
;(uber/successors g5 1)
;(class (uber/predecessors g5 2))
(def gbig (ubere/read-ubergraph-as-edges "resources/dimultigraph-129k-nodes-272k-edges.edn"))

(require '[ubergraph.core :as uber]
         '[cljol.ubergraph-extras :as ubere])

(def g7 (uber/multidigraph
         ;; nodes
         [:node-a {:label "node a"}]  :node-b  :node-d  :node-c
         ;; edges
         [:node-a :node-b]  [:node-a :node-d]
         [:node-b :node-d]  [:node-b :node-c]))
(uber/nodes g7)
(pprint (uber/edges g7))
(pprint (ubere/dense-integer-node-labels g7))
(def x (ubere/dense-integer-node-labels g7))
(ualg/connected-components g7)
x
(:node->int x)
(vec (:int->node x))

(def x (ubere/edge-vectors g7))
x
(pprint x)

)
(ualg/connected-components g1)
(ualg/connected-components g2)
(ualg/connected-components g3)
(ualg/connected-components g4)
(ualg/connected-components g5)
(def gbigcondensation (:scc-graph (ubere/scc-graph2 gbig)))
(def cc (ualg/connected-components gbigcondensation))

(println "tarjan starts here")
(def gbig-tarj
  (let [{g :ret :as p} (perf/time (ubere/scc-tarjan gbig))]
    (perf/print-perf-stats p)
    g))

(require '[criterium.core :as crit])
(crit/bench (let [{g :ret :as p} (perf/time (ubere/scc-tarjan gbig))]
              (perf/print-perf-stats p)
              (count (:components g))))

;; original scc-tarjan code, returning vectors from edge-vectors
;;379.6 msec, 0 gc-count, 0 gc-time-msec
;;489.2 msec, 1 gc-count, 108 gc-time-msec
;;384.3 msec, 0 gc-count, 0 gc-time-msec
;;382.9 msec, 0 gc-count, 0 gc-time-msec
;;375.6 msec, 0 gc-count, 0 gc-time-msec
;;475.3 msec, 1 gc-count, 85 gc-time-msec
;;390.2 msec, 0 gc-count, 0 gc-time-msec
;;370.8 msec, 0 gc-count, 0 gc-time-msec
;;382.5 msec, 0 gc-count, 0 gc-time-msec
;;377.1 msec, 0 gc-count, 0 gc-time-msec

;;Evaluation count : 180 in 60 samples of 3 calls.
;;             Execution time mean : 378.726700 ms
;;    Execution time std-deviation : 4.286314 ms
;;   Execution time lower quantile : 371.909448 ms ( 2.5%)
;;   Execution time upper quantile : 387.037589 ms (97.5%)
;;                   Overhead used : 1.400701 ns

;; modified scc-tarjan code, returning Java arrays from edge-arrays, but they are Object arrays even for the ones that could be int-arrays, because I forgot to make them int-arrays
;;653.9 msec, 1 gc-count, 58 gc-time-msec
;;407.6 msec, 0 gc-count, 0 gc-time-msec
;;467.9 msec, 1 gc-count, 73 gc-time-msec
;;387.4 msec, 0 gc-count, 0 gc-time-msec
;;449.6 msec, 1 gc-count, 67 gc-time-msec
;;384.2 msec, 0 gc-count, 0 gc-time-msec
;;378.9 msec, 0 gc-count, 0 gc-time-msec
;;431.4 msec, 1 gc-count, 63 gc-time-msec
;;386.6 msec, 0 gc-count, 0 gc-time-msec
;;446.7 msec, 1 gc-count, 66 gc-time-msec

;; modified scc-tarjan code, returning Java array of int-arrays
;;636.4 msec, 1 gc-count, 57 gc-time-msec
;;391.5 msec, 0 gc-count, 0 gc-time-msec
;;451.1 msec, 1 gc-count, 61 gc-time-msec
;;450.1 msec, 1 gc-count, 61 gc-time-msec
;;370.6 msec, 0 gc-count, 0 gc-time-msec
;;429.0 msec, 1 gc-count, 54 gc-time-msec
;;379.3 msec, 0 gc-count, 0 gc-time-msec
;;423.7 msec, 1 gc-count, 53 gc-time-msec
;;364.0 msec, 0 gc-count, 0 gc-time-msec
;;379.8 msec, 0 gc-count, 0 gc-time-msec

(crit/bench (let [{g :ret :as p} (perf/time (ubere/scc-tarjan-arrays gbig))]
              (perf/print-perf-stats p)
              (count (:components g))))
;;Evaluation count : 180 in 60 samples of 3 calls.
;;             Execution time mean : 384.006132 ms
;;    Execution time std-deviation : 67.031495 ms
;;   Execution time lower quantile : 359.910066 ms ( 2.5%)
;;   Execution time upper quantile : 607.619278 ms (97.5%)
;;                   Overhead used : 1.468928 ns
;;
;;Found 6 outliers in 60 samples (10.0000 %)
;;	low-severe	 3 (5.0000 %)
;;	low-mild	 3 (5.0000 %)
;; Variance from outliers : 87.6665 % Variance is severely inflated by outliers


;; ualg/connected-components returns a single weakly connected
;; component for graph gbig, but it contains duplicates of some nodes.
;; Why?
;; Try to find a much smaller graph that has this property.
;; I would like to see if there is a straightforward modification to
;; ualg/connected-components that guarantees returning distinct nodes
;; for each component.
(def cc (ualg/connected-components gbig))
(count cc)
(count (first cc))
(count (set (first cc)))

(def g9 (uber/multidigraph :a :b :c :d
                           [:a :b] [:b :a]
                           [:a :c] [:c :a]))
(ualg/connected-components g9)
;; result contains duplicates of :b and :c in first component
(def g9b (uber/multidigraph :a :b :c :d
                            [:a :b] [:b :a]))
(ualg/connected-components g9b)
;; result contains duplicates of :b in first component

;; TBD: Is a good way to handle it simply calling set on each
;; component before/after returning from connected-components?

;; TBD: Can a duplicate ever occur more than twice in the result?

;; TBD: How much do these duplicates affect the run time of
;; bf-traversal?


;; ubergraph.alg/connected-components is another name for
;; loom.alg/connected-components

;; loom.alg/connected-components calls loom.alg-generic/bf-traverse
;; with optional keys :f and :seen

;; From adding a little bit of tracing code inside
;; loom.alg/connected-components, I see that it is possible for
;; loom.alg-generic/bf-traverse to return a sequence of nodes
;; containing duplicates, which causes loom.alg/connected-components
;; to add those duplicates to a single one of the vectors representing
;; the nodes in a connected component, that it then returns.

;; loom.alg/connected-components calls loom.alg-generic/bf-traverse
;; with args :f vector and :seen predmap={} in the example I looked at
;; no :when key, so inside loom.alg-generic/bf-traverse, nbr-pred is
;; the function returned by (constantly true)


(count (uber/edges gbigcondensation))
(def gbigc-tr
  (let [{g :ret :as p} (perf/time (ubere/dag-transitive-reduction-slow
                                      gbigcondensation))]
    (perf/print-perf-stats p)
    g))
(count (uber/edges gbigc-tr))
(/ 143664.0 178576)

;(pprint (uber/edges (ubere/dag-transitive-reduction-slow g4)))

;(count (uber/nodes gbig))
;(count (uber/nodes gbigcondensation))
;(uber/in-degree g5 2)
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

(require '[loom.graph :as lg]
         '[ubergraph.core :as uber]
         '[ubergraph.alg :as ualg])

(doc uber/add-edges)
(doc uber/nodes)
(doc uber/edges)
(doc uber/has-node?)
(doc uber/has-edge?)
(doc uber/out-degree)
(doc uber/out-edges)
(doc uber/in-degree)
(doc uber/in-edges)
(doc uber/src)
(doc uber/dest)
(doc uber/successors)
(doc uber/predecessors)

(doc uber/find-edges)
(doc uber/find-edge)
(doc uber/other-direction)

)
