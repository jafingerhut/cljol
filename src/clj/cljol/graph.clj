(ns cljol.graph
  (:require [clojure.set :as set]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]))


(set! *warn-on-reflection* true)

;; Code here is intended to be generic to anyone that uses ubergraph
;; and/or perhaps also loom for representing and manipulating graph
;; data structures.


(defn leaf-node? [g n]
  (zero? (uber/out-degree g n)))


(defn leaf-nodes [g]
  (filter #(leaf-node? g %) (uber/nodes g)))


(defn remove-all-attrs
  [g]
  (reduce (fn [g n] (uber/set-attrs g n {}))
          g (concat (uber/nodes g) (uber/edges g))))


(defn induced-subgraph
  "Given a graph g, and a collection of nodes in that graph, return
  another graph containing only those nodes, and the edges of g that
  are between those nodes.  If there are multiple parallel edges in g
  between two nodes in 'nodes', all of them will be in the returned
  graph.

  The nodes and edges of the returned graph will have all attributes
  from g.  The returned graph will have the same allow-parallel? and
  undirected? properties as the original.

  If 'nodes' contains any values that are not nodes of g, they are
  ignored.  The returned graph will not contain those values as
  nodes."
  [g nodes]
  (let [nodes (set (filter #(uber/has-node? g %) nodes))
        old-node-count (uber/count-nodes g)
        new-node-count (count nodes)]
    (if (< new-node-count (/ old-node-count 2))
      ;; Then guess that it will be faster to start with an empty
      ;; graph and build up from there.  It is a guess, because it
      ;; really depends upon the number of edges in the resulting
      ;; graph, too.

      ;; TBD: I haven't tested this yet, but I believe that for graphs
      ;; that contain undirected edges, out-edges will return an edge
      ;; e in the collection for both of its endpoint nodes, but for
      ;; one of those the edges (mirror-edge? e) will return true, and
      ;; the other false.
      (as-> (uber/ubergraph (uber/allow-parallel-edges? g)
                            (uber/undirected-graph? g))
          new-g
        (reduce (fn add-node [new-g n]
                  (uber/add-nodes-with-attrs new-g [n (uber/attrs g n)]))
                new-g nodes)
        (reduce (fn add-edges-of-node [new-g n]
                  (uber/add-edges*
                   new-g
                   (for [e (uber/out-edges g n)
                         :when (and (contains? nodes (uber/dest e))
                                    (not (uber/mirror-edge? e)))]
                     [(uber/src e) (uber/dest e)
                      (uber/attrs g e)])))
                new-g nodes))
      ;; else guess that it will be faster to remove the nodes in g
      ;; that are not in 'nodes'.
      (let [nodes-to-remove (remove #(contains? nodes %) (uber/nodes g))]
        (uber/remove-nodes* g nodes-to-remove)))))


(defn dense-integer-node-labels
  "Return a map with keys :node->int and :int->node

  The value associated with key :node->int is a map with the nodes of g
  as keys, and distinct integers in the range [0, n-1] where n is the
  number of nodes.

  The value associated with key :int->node is a Java object array
  indexed from [0, n-1], and is the reverse mapping of the :node->int
  map."
  [g]
  (let [int->node (object-array (uber/count-nodes g))]
    (loop [i 0
           remaining-nodes (uber/nodes g)
           node->int (transient {})]
      (if-let [s (seq remaining-nodes)]
        (let [n (first s)]
          (aset int->node i n)
          (recur (inc i) (rest remaining-nodes)
                 (assoc! node->int n i)))
        ;; else
        {:node->int (persistent! node->int)
         :int->node int->node}))))


(defn edge-vectors
  "Given an ubergraph g, return a map with three keys:

  :node->int :int->node - These are the same as described as returned
  from the function dense-integer-node-labels.  See its documentation.

  :edges - edges is a vector of vectors of integers.  Using the
  integer node labels assigned in the :node->int map, suppose node n
  in the graph g has the integer label n-int in the map.  Then (edge
  n-int) is a vector, one per successor node of node n in g.  The
  vector contains all of the integer labels of those successor nodes
  of n.  There are no duplicates in this vector, even if g has
  multiple parallel edges between two nodes in the graph."
  [g]
  (let [n (uber/count-nodes g)
        {:keys [node->int int->node] :as m} (dense-integer-node-labels g)]
    (assoc m :edges
           (mapv (fn [node-int]
                   (mapv #(node->int %)
                         (uber/successors g (aget ^objects int->node node-int))))
                 (range n)))))


(definterface DoubleStack
  (^boolean isEmptyFront [])
  (^int topFront [])
  (^int popFront [])
  (^void pushFront [^int item])

  (^boolean isEmptyBack [])
  (^int topBack [])
  (^int popBack [])
  (^void pushBack [^int item]))


(deftype DoubleStackImpl [^ints items ^long n
                          ^:unsynchronized-mutable fp  ;; front stack pointer
                          ^:unsynchronized-mutable bp  ;; back stack pointer
                          ]
  DoubleStack
  (isEmptyFront [this]
    (zero? fp))
  (topFront [this]
    (aget items (dec fp)))
  (popFront [this]
    (set! fp (dec fp))
    (aget items fp))
  (pushFront [this item]
    (aset items fp item)
    (set! fp (inc fp)))

  (isEmptyBack [this]
    (== bp n))
  (topBack [this]
    (aget items bp))
  (popBack [this]
    (let [p bp]
      (set! bp (inc bp))
      (aget items p)))
  (pushBack [this item]
    (set! bp (dec bp))
    (aset items bp item)))


(defn double-stack [n]
  (DoubleStackImpl. (int-array n) n 0 n))


(comment
(def ds (double-stack 5))
(.isEmptyBack ds)
(.isEmptyFront ds)
)


(defn scc-tarjan
  "Calculate the strongly connected components of a graph using
  Pearce's algorithm, which is a variant of Tarjan's algorithm that
  uses a little bit less extra memory.  Both run in linear time in the
  size of the graph, meaning the sum of the number of nodes plus
  number of edges.

  This implementation is almost certainly limited to Intger/MAX_VALUE
  nodes in the greph, because part of its implementation uses signed
  Java int's to label the nodes, and signed comparisons to compare
  node numbers to each other.  This limit could easily be increased by
  replacing int with long in those parts of the implementation.

  This function returns a map with all of the keys that the function
  edge-vectors returns, plus the following:

  :components - the associated value is a vector of sets of nodes from the graph g.  Each set represents all of the nodes in one strongly connected component of g.

  TBD: I believe that the order of these sets represents either a
  topological order of these components in the scc-graph, or a reverse
  topological order.  Test and document which it is, if either.

  :rindex - A Java array of ints, used as part of the implementation.
  I believe that the contents of this array can be used to solve other
  graph problems efficiently, e.g. perhaps biconnected components and
  a few other applications for depth-first search mentioned here:

  https://en.wikipedia.org/wiki/Depth-first_search#Applications

  TBD: It would be nice to implement several of these other graph
  algorithms listed at that reference.

  References for this implementation:

  https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm

  This code is patterned after the variant of the algorithm by David
  J. Pearce with Java reference source code published in this
  repository:
  https://github.com/DavePearce/StronglyConnectedComponents

   That repository also has links to a research paper and blog article
  describing the algorithm.  That repository contains implementations
  of 4 variants of the algorithm:

  PeaFindScc1.Recursive
  PeaFindScc1.Imperative
  PeaFindScc2.Recursive
  PeaFindScc2.Imperative

  The two with Recursive in their names can cause the call stack to
  grow up to the number of nodes in the graph, which is not a good fit
  for large graphs and default maximum JVM stack sizes.

  The two with Imperative in their names use iteration, and no
  recursion, maintaining an explicit stack data structure on the heap.
  Thus they are a better fit for large graphs and deafult maximum JVM
  stack sizes.

  The PeaFindScc1 versions allocate a bit more additional memory over
  and above the graph data structure than the PeaFindScc2 versions.  I
  believe the PeaFindScc1 versions were written as a reference in
  order to compare the results of the PeaFindScc2 implementations
  against them.

  This implementation is a translation of the PeaFindScc2.Imperative
  code into Clojure, maintaining the property that it is not
  recursive, and attempting to use only as much additional memory as
  the Java implementation would allocate."
  [g]
  (let [n (uber/count-nodes g)
        _ (assert (< n Integer/MAX_VALUE))
        {:keys [node->int ^objects int->node edges] :as m} (edge-vectors g)]
    (with-local-vars [;; from constructor Base()
                      index 1
                      c (dec n)]
      (let [;; from constructor Base()
            ^ints rindex (int-array n)

            ;; from constructor Imperative()
            ^DoubleStack vS (double-stack n)
            ^DoubleStack iS (double-stack n)
            root (boolean-array n)

            ;; method Imperative.beginVisiting(int v)
            beginVisiting (fn [v]
                            ;; First time this node encountered
                            (.pushFront vS v)
                            (.pushFront iS 0)
                            (aset root v true)
                            (aset rindex v (int @index))
                            (var-set index (inc @index)))

            ;; method Imperative.finishVisiting(int v)
            finishVisiting (fn [v]
                             ;; Take this vertex off the call stack
                             (.popFront vS)
                             (.popFront iS)
                             ;; Update component information
                             (if (aget root v)
                               (do
                                 (var-set index (dec @index))
                                 (while (and (not (.isEmptyBack vS))
                                             (<= (aget rindex v)
                                                 (aget rindex (.topBack vS))))
                                   (let [w (.popBack vS)]
                                     (aset rindex w (int @c))
                                     (var-set index (dec @index))))
                                 (aset rindex v (int @c))
                                 (var-set c (dec @c)))
                               ;; else
                               (.pushBack vS v)))

            ;; method Imperative.beginEdge(int v, int k)
            beginEdge (fn [v k]
                        (let [g-edges (edges v)
                              w (g-edges k)]
                          (if (zero? (aget rindex w))
                            (do
                              (.popFront iS)
                              (.pushFront iS (inc k))
                              (beginVisiting w)
                              true)
                            ;; else
                            false)))

            ;; method Imperative.finishEdge(int v, int k)
            finishEdge (fn [v k]
                        (let [g-edges (edges v)
                              w (g-edges k)]
                          (if (< (aget rindex w) (aget rindex v))
                            (do
                              (aset rindex v (aget rindex w))
                              (aset root v false)))))

            ;; method Imperative.visitLoop()
            visitLoop (fn []
                        (let [v (.topFront vS)
                              i (atom (.topFront iS))
                              g-edges (edges v)
                              num-edges (count g-edges)]
                          ;; Continue traversing out-edges until none left.
                          (let [return-early
                                (loop []
                                  (if (<= @i num-edges)
                                    (do
                                      ;; Continuation
                                      (if (> @i 0)
                                        ;; Update status for previously
                                        ;; traversed out-edge
                                        (finishEdge v (dec @i)))
                                      (if (and (< @i num-edges)
                                               (beginEdge v @i))
                                        true  ;; return early
                                        (do
                                          (swap! i inc)
                                          (recur))))
                                    ;; else
                                    false))]  ;; no early return occurred
                            (if (not return-early)
                              ;; Finished traversing out edges, update
                              ;; component info
                              (finishVisiting v)))))

            ;; method Imperative.visit(int v)
            visit (fn [v]
                    (beginVisiting v)
                    (while (not (.isEmptyFront vS))
                      (visitLoop)))

            ;; from Imperative.visit() method
            topvisit
            (fn []
              (doseq [i (range n)]
                (if (zero? (aget rindex i))
                  (visit i)))
              ;; now, post process to produce component sets
              (let [num-components (- n 1 @c)
                    comps (let [x (object-array num-components)]
                            (dotimes [i num-components]
                              (aset x i (transient #{})))
                            x)
                    components (loop [i 0]
                                 (if (< i n)
                                   (let [cindex (- n 1 (aget rindex i))]
                                     (aset comps cindex
                                           (conj! (aget comps cindex)
                                                  (aget int->node i)))
                                     (recur (inc i)))
                                   ;; else
                                   (mapv persistent! comps)))]
                (assoc m
                       :components components
                       :rindex rindex)))]
        (topvisit)))))


(defn scc-graph
  "Given a graph g, return a map containing two values.

  Calculate the sets of strongly connected components in g.  Those
  sets of g nodes become the values of nodes in the scc-graph.  The
  scc-graph has an edge from node A (which is a set of nodes in g) to
  another node B (which is another, disjoint, set of nodes in g), if
  there is an edge in g from any node in set A to any node in set B.
  The scc-graph has only one such edge from node A to any other node
  B, no matter how many edges are in the original graph.  Also, the
  returned graph never has a 'self loop' edge from a node A back to
  itself, even if the original graph has an edge from a node in set A
  to another (or the same) node in set A.

  This derived graph is returned as the value associated with
  key :scc-graph in the return value.

  The key :node->scc-set in the returned map has an associated value
  that is a map where the keys are the nodes of g, and the value
  associated with node n is the set of nodes that are in the same
  strongly connected component with n.  This set always contains at
  least node n, and may contain others."
  [g]
  (let [sc-components (map set (ualg/scc g))
        g-node->scc-node (into {}
                               (for [scc-node sc-components
                                     g-node scc-node]
                                 [g-node scc-node]))
        sccg (-> (uber/multidigraph)
                 (uber/add-nodes* sc-components))
        sccg (reduce (fn maybe-add-sccg-edge [sccg g-edge]
                       (let [g-src (uber/src g-edge)
                             g-dest (uber/dest g-edge)
                             sccg-src (g-node->scc-node g-src)
                             sccg-dest (g-node->scc-node g-dest)]
                         (if (or (= sccg-src sccg-dest)
                                 (uber/has-edge? sccg sccg-src sccg-dest))
                           ;; Then do not bother adding a self loop,
                           ;; nor a parallel edge.  For the purposes
                           ;; of this program, that would be a
                           ;; redundant edge.
                           sccg
                           (uber/add-edges sccg [sccg-src sccg-dest]))))
                     sccg (uber/edges g))]
    {:scc-graph sccg
     :node->scc-set g-node->scc-node}))


(defn dag-reachable-nodes
  "Return a map with the nodes of the given ubergraph as keys, with
  the value associated with node n being a set of nodes reachable from
  n via a path in the graph.  n is counted as reachable from itself.
  Throws an exception if the graph contains a cycle (TBD: any
  undirected edge counts as a cycle for this function?).  Use function
  reachable-nodes instead if you want the answer for a graph that may
  contain cycles."
  [dag]
  (let [topsort (ualg/topsort dag)
        ;; ualg/topsort returns nil if the graph has a cycle.
        _ (assert (not (nil? topsort)))
        ;; rnm = "reachable node map", a map with nodes as keys, and
        ;; collections of nodes reachable from that node (including
        ;; itself) as associated values.
        rnm (reduce (fn [rnm cur-node]
                      (let [coll-of-node-sets
                            (for [edge (uber/out-edges dag cur-node)]
                              (rnm (uber/dest edge)))]
                        (assoc rnm cur-node
                               (conj (apply set/union coll-of-node-sets)
                                     cur-node))))
                    {} (reverse topsort))]
    rnm))


(defn reachable-nodes
  "Given a graph g, return a map where the keys are sets of nodes in g.
  Each node will be in exactly one key of the map.  The value
  associated with a set of nodes S, is a set of nodes T.  T is exactly
  those nodes that can be reached from a node in S via a path in the
  graph g.  T is always a superset of S, since nodes are considered to
  be able to be reachable from themselves (through a path of 0 edges)."
  [g]
  (let [{:keys [scc-graph node->scc-set]} (scc-graph g)
        reachable-scc-sets (dag-reachable-nodes scc-graph)]
    (into {}
          (for [[scc-set reachable-sccs] reachable-scc-sets
                :let [reachable-nodes (apply set/union reachable-sccs)]]
            [scc-set reachable-nodes]))))


;; TBD: Consider optimizing this for the probable common case in
;; Clojure where a graph of nodes is acyclic, and the corresponding
;; undirected graph is a tree.  This includes a graph like the one
;; shown below, that is not a directed tree because node C has
;; in-degree 2, not 1.  Even that graph should be possible to
;; calculate total reachable node sizes in linear time without
;; having to calculate sets of reachable nodes explicitly, stored in
;; memory.  Even if the scc-graph of the original graph has the
;; structure shown below, we do not need to calculate reachable node
;; sets.

;;   A--          --> D
;;      \        /
;;       --> C --
;;       -->   --
;;      /        \
;;   B--          --> E

(defn total-reachable-node-size
  "Given a directed graph g where every node has a 'size' attribute,
  return a map where the keys are the nodes, and the associated values
  are a map like this:

  {:num-reachable-nodes 5 :total-size 114}

  For example, if starting from node 1 in the graph, the node 3, 6, 7,
  and 10 can be reached via one or more edges, then
  the :num-reachable-nodes of node 1 is 5 (the 4 other nodes, plus
  itself), and the total reachable node size of node 1 will be the sum
  of the sizes of those nodes, plus its own size.

  If given a graph with undirected edges, I believe that it will be
  treated as if it were the directed graph where every undirected edge
  is treated as 2 directed edges, one in each direction (TBD)."
  [g node-size-fn]
  (let [reachable-node-map (reachable-nodes g)]
    (into {}
          (for [[node-set reachable-node-set] reachable-node-map
                :let [sum (reduce + (map #(node-size-fn g %)
                                         reachable-node-set))]
                node node-set]
            [node {:total-size sum
                   :num-reachable-nodes (count reachable-node-set)}]))))


(defn find-first-or-last
  "Find and return the first item of coll such that (pred item) returns
  logical true.  If there is no such item, but there is at least one
  item in coll, return the last item.  If coll is empty, return the
  not-found value."
  [pred coll not-found]
  (letfn [(step [s]
            (let [f (first s)]
              (if (pred f)
                f
                (if-let [n (next s)]
                  (recur n)
                  f))))]
    (if-let [s (seq coll)]
      (step s)
      not-found)))

(comment
(= 6 (find-first-or-last #(>= % 5) [2 4 6 8] :not-found))
(= 8 (find-first-or-last #(>= % 10) [2 4 6 8] :not-found))
(= :not-found (find-first-or-last #(>= % 10) [] :not-found))
)


(defn bounded-reachable-node-stats
  "Do a depth-first search of graph g starting at node n, stopping at
  the first one of these conditions to become true:

  (a) There are no more nodes in the depth-first traversal, or

  (b) Among nodes traversed so far, the total number is greater than
  node-count-min-limit and the total size is greater than
  total-size-min-limit, where the size of one node is determined by
  the function (node-size-fn g node).

  Return a map with two keys: :num-reachable-nodes :total-size

  If condition (b) is not true of the values returned, then they
  represent the total among all nodes reachable from node n in the
  graph."
  [g n node-size-fn node-count-min-limit total-size-min-limit]
  (let [init {:num-reachable-nodes 0
              :total-size 0}]
    (find-first-or-last
     (fn [{:keys [num-reachable-nodes total-size]}]
       (and (> num-reachable-nodes node-count-min-limit)
            (> total-size total-size-min-limit)))
     (reductions (fn add-one-node [acc n]
                   (let [{:keys [num-reachable-nodes total-size]} acc]
                     {:num-reachable-nodes (inc num-reachable-nodes)
                      :total-size (+ total-size (node-size-fn g n))}))
                 init
                 (ualg/pre-traverse g n))
     init)))


(comment

;; Does an ubergraph keep the attributes of a node after it has been
;; removed using remove-node?  That is, if you remove a node then add
;; it back, will the node have the same attributes it did before?

(do
(require '[cljol.graph :as g]
          [ubergraph.core :as uber])
(in-ns 'cljol.graph)
)

(def g1 (uber/multidigraph [1 {:label "n1"}]
                           [2 {:label "n2"}]
                           [1 2 {:label "edge12"}]))
(uber/pprint g1)
(def g2 (uber/remove-nodes g1 2))
(uber/pprint g2)
(def g3 (uber/add-nodes g2 2))
(uber/pprint g3)

(def g4 (uber/add-nodes-with-attrs g2 [2 {:attr7 8}]))
(uber/pprint g4)

(def g5 (induced-subgraph g1 [2]))
(uber/pprint g5)

(def g6 (-> g1
            (uber/add-nodes-with-attrs
             [3 {:label "n3"}]
             [4 {:label "n4"}]
             [5 {:label "n5"}]
             [6 {:label "n6"}]
             [7 {:label "n7"}])
            (uber/add-edges
             [2 3 {:label "edge23"}]
             [3 4 {:label "edge34"}])))
(uber/pprint g6)
(def g7 (induced-subgraph g6 [2 4 3]))
(uber/pprint g7)



(def g1 (uber/graph [1 {:label "n1"}]
                    [2 {:label "n2"}]
                    [1 2 {:label "edge12"}]))
(uber/pprint g1)
(def g2 (uber/remove-edges g1 [1 2]))
(uber/pprint g2)
(def g3 (uber/add-edges g2 [1 2]))
(uber/pprint g3)

)
