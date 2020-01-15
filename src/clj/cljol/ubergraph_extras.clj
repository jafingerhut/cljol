(ns cljol.ubergraph-extras
  (:require [clojure.set :as set]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]))



;; Unlike the function loom.alg-generic/pre-traverse in the Loom
;; library as of version 1.0.1, the function below never puts a node
;; onto the stack unless we know it has not been seen (aka visited) in
;; the DFS traversal before.  Thus every node will be on the stack at
;; most once.  Also what is on the stack is not only the node, but a
;; pair [node remaining-successors], where remaining-successors is a
;; sequence of the successors of node such that the edge (node ->
;; successor) has not been considered yet.  This avoids realizing any
;; more of the sequences returned by 'successors' than is necessary.

(defn pre-traverse*
  "Traverses a graph depth-first preorder from start, successors being
  a function that returns direct successors for the node. Returns a
  lazy seq of nodes."
  [successors start & {:keys [seen] :or {seen #{}}}]
  (letfn [(step [stack seen]
            (when-let [[node remaining-successors] (peek stack)]
              (if (seen node)
                ;; We have seen this node before, but we may not
                ;; have seen all of its successors yet.  Continue
                ;; checking successors where we left off.
                (if-let [s (seq (drop-while seen remaining-successors))]
                  ;; Then at least one neighbor of node has not been
                  ;; seen yet.  Remember where we are in the
                  ;; remaining-successors sequence for node, and push
                  ;; the unseen node onto the stack.
                  (recur (conj (pop stack)
                               [node (rest s)]
                               [(first s) (successors (first s))])
                         seen)
                  ;; else all neighbors of node have already been
                  ;; seen.  Backtrack on the stack.
                  (recur (pop stack) seen))
                ;; else we have not seen this node before
                (lazy-seq (cons node
                                (step stack (conj seen node)))))))]
    (step [[start (successors start)]] seen)))


(defn pre-traverse [g n]
  (pre-traverse* #(uber/successors g %) n))


(defn induced-subgraph-build-from-empty
  [g node-set]
  ;; TBD: I haven't tested this yet, but I believe that for graphs
  ;; that contain undirected edges, out-edges will return an edge e in
  ;; the collection for both of its endpoint nodes, but for one of
  ;; those the edges (mirror-edge? e) will return true, and the other
  ;; false.
  (as-> (uber/ubergraph (uber/allow-parallel-edges? g)
                        (uber/undirected-graph? g))
      new-g
    (reduce (fn add-node [new-g n]
              (uber/add-nodes-with-attrs new-g [n (uber/attrs g n)]))
            new-g node-set)
    (reduce (fn add-edges-of-node [new-g n]
              (uber/add-edges*
               new-g
               (for [e (uber/out-edges g n)
                     :when (and (contains? node-set (uber/dest e))
                                (not (uber/mirror-edge? e)))]
                 [(uber/src e) (uber/dest e)
                  (uber/attrs g e)])))
            new-g node-set)))


(defn induced-subgraph-by-removing-nodes
  [g node-set]
  (let [nodes-to-remove (remove #(contains? node-set %) (uber/nodes g))]
    (uber/remove-nodes* g nodes-to-remove)))


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
  (let [node-set (set (filter #(uber/has-node? g %) nodes))
        old-node-count (uber/count-nodes g)
        new-node-count (count node-set)]
    (if (< new-node-count (/ old-node-count 2))
      ;; Then guess that it will be faster to start with an empty
      ;; graph and build up from there.  It is a guess, because it
      ;; really depends upon the number of edges in the resulting
      ;; graph, too.
      (induced-subgraph-build-from-empty g node-set)
      ;; else guess that it will be faster to remove the nodes in g
      ;; that are not in 'nodes'.
      (induced-subgraph-by-removing-nodes g node-set))))


(defn dense-integer-node-labels
  "Returns a map.

  Keys in returned map: :node->int :int->node

  :node->int

  The associated value is a map with the nodes of g as keys, and
  distinct integers in the range [0, n-1] where n is the number of
  nodes.

  :int->node

  The associated value is a Java object array indexed from [0, n-1],
  and is the reverse mapping of the :node->int map."
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


;; Note 2:

;; This code is written assuming that uber/successors returns each
;; successor node at most once, which at least for ubergraph version
;; 0.5.3 is true.

(defn edge-vectors
  "Given an ubergraph g, return a map.

  Keys in returned map: :edges plus those returned by the function
  dense-integer-node-labels

  :edges

  The associated value is a vector of vectors of integers.  Using the
  integer node labels assigned in the :node->int map, suppose node n
  in the graph g has the integer label A in the map.  Then (edge A) is
  a vector, one per successor node of node n in g.  The vector
  contains all of the integer labels of those successor nodes of n.
  There are no duplicates in this vector, even if g has multiple
  parallel edges between two nodes in the graph."
  [g]
  (let [n (uber/count-nodes g)
        {:keys [node->int int->node] :as m} (dense-integer-node-labels g)]
    (assoc m :edges
           (mapv (fn [node-int]
                   (mapv #(node->int %)
                         ;; Note 2
                         (uber/successors g (aget ^objects int->node
                                                  node-int))))
                 (range n)))))


(defprotocol DoubleStack
  (^boolean isEmptyFront [this])
  (^int topFront [this])
  (^int popFront [this])
  (^void pushFront [this item])

  (^boolean isEmptyBack [this])
  (^int topBack [this])
  (^int popBack [this])
  (^void pushBack [this item]))


(deftype DoubleStackImpl [^ints items ^long n
                          ^:unsynchronized-mutable fp  ;; front stack pointer
                          ^:unsynchronized-mutable bp  ;; back stack pointer
                          ]
  cljol.ubergraph_extras.DoubleStack
  (isEmptyFront [this]
    (zero? fp))
  (topFront [this]
    (aget items (dec fp)))
  (popFront [this]
    (set! fp (dec fp))
    (aget items fp))
  (pushFront [this item]
    (aset items fp (int item))
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
    (aset items bp (int item))))


(defn double-stack [n]
  (DoubleStackImpl. (int-array n) n 0 n))


(comment
(def ds (double-stack 5))
(isEmptyBack ds)
(isEmptyFront ds)
)


(defn scc-tarjan
  "Calculate the strongly connected components of a graph using
  Pearce's algorithm, which is a variant of Tarjan's algorithm that
  uses a little bit less extra memory.  Both run in linear time in the
  size of the graph, meaning the sum of the number of nodes plus
  number of edges.

  This implementation is limited to Integer/MAX_VALUE = (2^31 - 1)
  nodes in the graph, because part of its implementation uses signed
  Java int's to label the nodes, and signed comparisons to compare
  node numbers to each other.  This limit could easily be increased by
  replacing int with long in those parts of the implementation.

  Keys in returned map: :components :rindex :root plus those returned
  by function edge-vectors.

  :components

  The associated value is a vector, where each vector element is a set
  of nodes of the graph g.  Each set represents all of the nodes in
  one strongly connected component of g.  The vector is ordered such
  that they are in a topological ordering of the components,
  i.e. there might be edges from set i to set j if i < j, but there
  are guaranteed to be no edges from set j to set i.

  :rindex

  A Java array of ints, used as part of the implementation.  I believe
  that the contents of this array can be used to solve other graph
  problems efficiently, e.g. perhaps biconnected components and a few
  other applications for depth-first search mentioned here:

  https://en.wikipedia.org/wiki/Depth-first_search#Applications

  TBD: It would be nice to implement several of these other graph
  algorithms listed at that reference.

  :root

  A Java array of booleans, used as part of the implementation.
  Like :rindex, it may be useful for calculating other information
  about the graph.

  References for this implementation:

  https://en.wikipedia.org/wiki/Tarjan%27s_strongly_connected_components_algorithm

  This code is fairly directly translated from Java into Clojure of
  the variant of Tarjan's algorithm by David J. Pearce, with Java
  reference source code published in this repository:
  https://github.com/DavePearce/StronglyConnectedComponents

  That repository has links to a research paper and blog article
  describing the algorithm, and contains implementations of 4 variants
  of the algorithm with the following class names:

  PeaFindScc1.Recursive
  PeaFindScc1.Imperative
  PeaFindScc2.Recursive
  PeaFindScc2.Imperative

  The two with Recursive in their names can cause the call stack to
  grow up to the number of nodes in the graph, which is not a good fit
  for large graphs and default maximum JVM stack sizes.

  The two with Imperative in their names use iteration, and no
  recursion, maintaining an explicit stack data structure on the heap.
  Thus they are a better fit for large graphs and default maximum JVM
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
        {:keys [^objects int->node edges] :as m} (edge-vectors g)]
    (with-local-vars [;; from constructor Base()
                      index 1
                      c (dec n)]
      (let [;; from constructor Base()
            ^ints rindex (int-array n)

            ;; from constructor Imperative()
            vS (double-stack n)
            iS (double-stack n)
            root (boolean-array n)

            ;; method Imperative.beginVisiting(int v)
            beginVisiting (fn [v]
                            ;; First time this node encountered
                            (pushFront vS v)
                            (pushFront iS 0)
                            (aset root v true)
                            (aset rindex v (int @index))
                            (var-set index (inc @index)))

            ;; method Imperative.finishVisiting(int v)
            finishVisiting (fn [v]
                             ;; Take this vertex off the call stack
                             (popFront vS)
                             (popFront iS)
                             ;; Update component information
                             (if (aget root v)
                               (do
                                 (var-set index (dec @index))
                                 (while (and (not (isEmptyBack vS))
                                             (<= (aget rindex v)
                                                 (aget rindex (topBack vS))))
                                   (let [w (popBack vS)]
                                     (aset rindex w (int @c))
                                     (var-set index (dec @index))))
                                 (aset rindex v (int @c))
                                 (var-set c (dec @c)))
                               ;; else
                               (pushBack vS v)))

            ;; method Imperative.beginEdge(int v, int k)
            beginEdge (fn [v k]
                        (let [g-edges (edges v)
                              w (g-edges k)]
                          (if (zero? (aget rindex w))
                            (do
                              (popFront iS)
                              (pushFront iS (inc k))
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
                        (let [v (topFront vS)
                              i (atom (topFront iS))
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
                    (while (not (isEmptyFront vS))
                      (visitLoop)))

            ;; from Imperative.visit() method
            topvisit
            (fn []
              (doseq [i (range n)]
                (if (zero? (aget rindex i))
                  (visit i)))
              ;; now, post process to produce component sets
              (let [num-components (- n 1 @c)
                    delta (inc @c)
                    comps (let [x (object-array num-components)]
                            (dotimes [i num-components]
                              (aset x i (transient #{})))
                            x)
                    components (loop [i 0]
                                 (if (< i n)
                                   ;; See Note 1
                                   (let [cindex (- (aget rindex i) delta)]
                                     (aset comps cindex
                                           (conj! (aget comps cindex)
                                                  (aget int->node i)))
                                     (recur (inc i)))
                                   ;; else
                                   (mapv persistent! comps)))]
                ;; While we could also return vS and iS, I believe
                ;; they always have empty front and back stacks when
                ;; the algorithm is complete, so there is no
                ;; information to be gained by the caller if we did
                ;; return them.
                (assoc m
                       :components components
                       :rindex rindex
                       :root root)))]
        (topvisit)))))

;; Note 1:

;; The original Java implementation used (- n 1 (aget rindex i)) as
;; the value of cindex here.  That caused the components to be stored
;; in reverse topological order in the scc-graph, from index 0 on up
;; in the components array.

;; To cause them to be created in topological order, not reversed, we
;; can instead use cindex2 equal to (- num-components 1 cindex), which
;; with a little bit of algebra, written in infix notation, is:

;;   cindex2
;; = num-components - 1 - cindex
;; = (n - 1 - @c) - 1 - (n - 1 - (aget rindex i))
;; = (aget rindex i) - (@c + 1)


(defn scc-graph
  "Given a graph g, return a map containing several keys, one of which
  represents the strongly connected components of the graph, and the
  others calculated while determining the strongly connected
  components.

  For each strongly connected component in g, the set of nodes in that
  component become one node in a graph we call the scc-graph.  The
  scc-graph has an edge from node A (which is a set of nodes in g) to
  another node B (which is another, disjoint, set of nodes in g), if
  there is an edge in g from any node in set A to any node in set B.
  The scc-graph has only one such edge from node A to any other node
  B, no matter how many edges are in the original graph.  Also, the
  returned graph never has a 'self loop' edge from a node A back to
  itself, even if the original graph has an edge from a node in set A
  to another (or the same) node in set A.

  Keys in returned map: :scc-graph :node->scc-set plus those returned
  by the function scc-tarjan.

  :scc-graph

  The associated value is the derived graph described above.

  :node->scc-set

  This associated value is a map where the keys are the nodes of g,
  and the value associated with node n is the set of nodes that are in
  the same strongly connected component with n.  This set always
  contains at least node n, and may contain others."
  [g]
  (let [{:keys [components] :as m} (scc-tarjan g)
        g-node->scc-node (into {}
                               (for [scc-node components
                                     g-node scc-node]
                                 [g-node scc-node]))
        sccg-edges (->> (uber/edges g)
                        (map (fn [g-edge]
                               [(g-node->scc-node (uber/src g-edge))
                                (g-node->scc-node (uber/dest g-edge))]))
                        distinct
                        (remove (fn [[src dest]] (= src dest))))
        sccg (-> (uber/multidigraph)
                 (uber/add-nodes* components)
                 (uber/add-edges* sccg-edges))]
    (assoc m
           :scc-graph sccg
           :node->scc-set g-node->scc-node)))


(defn scc-graph2
  "Given a graph g, return a map containing several keys, one of which
  represents the strongly connected components of the graph, and the
  others calculated while determining the strongly connected
  components.

  For each strongly connected component in g, the set of nodes in that
  component become one node in a graph we call the scc-graph.  The
  scc-graph has an edge from node A (which is a set of nodes in g) to
  another node B (which is another, disjoint, set of nodes in g), if
  there is an edge in g from any node in set A to any node in set B.
  The scc-graph has only one such edge from node A to any other node
  B, no matter how many edges are in the original graph.  Also, the
  returned graph never has a 'self loop' edge from a node A back to
  itself, even if the original graph has an edge from a node in set A
  to another (or the same) node in set A.

  Keys in returned map: :scc-graph :node->scc-set plus those returned
  by the function scc-tarjan.

  :scc-graph

  The associated value is the derived graph described above.  Its
  nodes are actually integers in the range 0 up to the number of nodes
  minus 1.

  :node->scc-set

  This associated value is a map where the keys are the nodes of g,
  and the value associated with node n is the set of nodes that are in
  the same strongly connected component with n.  This set always
  contains at least node n, and may contain others.

  :scc-node-num->scc-set

  A map.  Its keys are integers from 0 up to the number of nodes in
  scc-graph, minus 1.  These are the nodes of scc-graph.  Associated
  value is the scc-set, the set of nodes values of g that are all in
  the same strongly connected component."
  [g]
  (let [{:keys [components] :as m} (scc-tarjan g)
        scc-node-num->scc-set (into {}
                                    (for [i (range (count components))]
                                      [i (components i)]))
        g-node->scc-node-num (into {}
                                   (for [i (range (count components))
                                         g-node (components i)]
                                     [g-node i]))
        g-node->scc-node (into {}
                               (for [scc-node components
                                     g-node scc-node]
                                 [g-node scc-node]))
        sccg-edges (->> (uber/edges g)
                        (map (fn [g-edge]
                               [(g-node->scc-node-num (uber/src g-edge))
                                (g-node->scc-node-num (uber/dest g-edge))]))
                        (remove (fn [[src dest]] (= src dest))))
        sccg (-> (uber/digraph)
                 (uber/add-nodes* (range (count components)))
                 (uber/add-edges* sccg-edges))]
    (assoc m
           :scc-graph sccg
           :scc-node-num->scc-set scc-node-num->scc-set
           :node->scc-set g-node->scc-node)))


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
  (let [{:keys [scc-graph]} (scc-graph g)
        reachable-scc-sets (dag-reachable-nodes scc-graph)]
    (into {}
          (for [[scc-set reachable-sccs] reachable-scc-sets
                :let [reachable-nodes (apply set/union reachable-sccs)]]
            [scc-set reachable-nodes]))))
