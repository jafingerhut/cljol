(ns cljol.ubergraph-extras
  (:require [clojure.set :as set]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]
            [cljol.performance :as perf]))

(set! *warn-on-reflection* true)
(set! *unchecked-math* :warn-on-boxed)


(defn read-ubergraph-as-edges
  "Given a `readable` thing, i.e. something that can be passed to
  clojure.java.io/reader and return a reader, such a string containing
  a file name on a local file system, read it and return an ubergraph
  value constructed from it.  The contents of the readable thing
  should be a Clojure vector, where every element is itself a vector
  of 2 elements, each 2-element vector representing an edge in a
  graph, [from to]."
  [readable]
  (let [{edges :ret :as p} (perf/my-time
                            (with-open [rdr (java.io.PushbackReader.
                                             (io/reader readable))]
                              (edn/read rdr)))
        _ (do (print "Read" (count edges) "edges in:")
              (perf/print-perf-stats p))
        {g :ret :as p} (perf/my-time
                         (-> (uber/multidigraph)
                             (uber/add-edges* edges)))]
    (print "Created graph with" (uber/count-nodes g) "nodes,"
           (uber/count-edges g) "edges in:")
    (perf/print-perf-stats p)
    g))


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


;; Note: dense-integer-node-labels behaves as documented for all
;; Ubergraphs, regardless of whether are edges are directed,
;; undirected, parallel, or self loop, because it ignores the edges of
;; g completely.

(defn dense-integer-node-labels
  "Given an ubergraph g, returns a map.

  Keys in returned map: :node->int :int->node

  :node->int

  The associated value is a map with the nodes of g as keys, each node
  associated with a distinct integer in the range [0, n-1] where n is
  the number of nodes.

  :int->node

  The associated value is a (mutable) array of objects indexed
  from [0, n-1], and is the reverse mapping of the :node->int map.

  Runs in effectively O(n) time, where n is the number of nodes in the
  graph.

  This is a very simple function, but it is sometimes useful as a step
  before running a more complex algorithm on a graph.  Having a
  contiguous range of integers from [0, n-1] for each node is useful
  for creating an array of values associated with each node, and
  storing them in a vector or Java array separate from the graph data
  structure itself.  If these extra values are no longer needed when
  the algorithm completes, it can be faster to do it this way, versus
  using Ubergraph node attributes.

  Example:

  user=> (require '[ubergraph.core :as uber]
                  '[cljol.ubergraph-extras :as ubere])
  nil
  user=> (def g7 (uber/multidigraph
                  ;; nodes
                  [:node-a {:label \"node a\"}]  :node-b  :node-d  :node-c
                  ;; edges
                  [:node-a :node-b]  [:node-a :node-d]
                  [:node-b :node-d]  [:node-b :node-c]))
  #'user/g7

  user=> (uber/nodes g7)
  (:node-a :node-b :node-d :node-c)

  user=> (def x (ubere/dense-integer-node-labels g7))
  #'user/x

  ;; Demonstration of default way Java array object is shown in REPL
  user=> x
  {:node->int {:node-a 0, :node-b 1, :node-d 2, :node-c 3},
   :int->node #object[\"[Ljava.lang.Object;\" 0x6707382a \"[Ljava.lang.Object;@6707382a\"]}

  ;; Converting Java array contents into a Clojure vector is one way
  ;; to see its contents in the REPL
  user=> (vec (:int->node x))
  [:node-a :node-b :node-d :node-c]"
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


;; Note: edge-vectors behaves as documented for all Ubergraphs,
;; regardless of whether its edges are directed, undirected, parallel,
;; or self loop, because it only calls dense-integer-node-labels,
;; uber/count-nodes, and uber/successors.  uber/successors behaves in
;; a reasonable way for all such graphs, including returning each
;; successor node exactly once in all cases.

;; Note: It definitely take less memory if each of the vectors of
;; integer in the returned :edges vector was a Clojure primitive
;; vector as returned by (vector-of :int ...).  However, I strongly
;; suspect this would make the implementation slower, since as of
;; Clojure 1.10.1, primitive vectors do not implement transients.  It
;; might be worth changing the implementation of edge-vectors to
;; return those if/when there is such a primitive vector
;; implementation.

(defn edge-vectors
  "Given an ubergraph g, return a map.

  Keys in returned map: :edges plus those returned by the function
  dense-integer-node-labels

  :edges

  The associated value is a vector, which we will call `edges`.  We
  will use the name `n2i` as a name for the map that is the value
  associated with the key :node->int in the return value of function
  dense-integer-node-labels.  Use `n` to denote the number of nodes in
  g.

  For any node u in graph g, (n2i u) is an integer in the range [0,
  n-1], and (edges (n2i u)) is a vector of distinct integers,
  containing the integer (n2i v) for each node v that is a successor
  of node u in the graph.  (n2i v) appears at most once in this
  vector, even if there are multiple parallel edges from u to v in g.

  (edges (n2i u)) will contain (n2i u) if there are any self loop
  edges from node u to itself.

  Runs in effectively O(n+m') time, where n is the number of nodes in
  the graph, and m' is the total size of all vectors in edges.

  Example:

  user=> (require '[ubergraph.core :as uber]
                  '[cljol.ubergraph-extras :as ubere])
  nil
  user=> (def g8 (uber/multidigraph
                  ;; nodes
                  :node-a  :node-b  :node-d  :node-c
                  ;; edges
                  [:node-a :node-b]  [:node-a :node-b]  [:node-a :node-c]
                  [:node-b :node-d]  [:node-b :node-c]))
  #'user/g8

  user=> (uber/nodes g8)
  (:node-a :node-b :node-d :node-c)

  user=> (def x (ubere/edge-vectors g8))
  #'user/x

  user=> (:node->int x)
  {:node-a 0, :node-b 1, :node-d 2, :node-c 3}

  user=> (:edges x)
  [[1 3] [2 3] [] []]

  Note that :node-a has two parallel edges to :node-b in the graph,
  but in vector number 0 that represents the edges out of :node-a, [1
  3], it contains the number 1 for :node-b only once."
  [g]
  (let [n (uber/count-nodes g)
        {:keys [node->int int->node] :as m} (dense-integer-node-labels g)]
    (assoc m :edges
           (mapv (fn [node-int]
                   (mapv #(node->int %)
                         (uber/successors g (aget ^objects int->node
                                                  node-int))))
                 (range n)))))

(defn edge-arrays
  "Given an ubergraph g, return a map.

  Keys in returned map: :edges plus those returned by the function
  dense-integer-node-labels

  :edges

  The associated value is an array , where each element is an array of
  Java ints.  If we call this array `edges`, and we call the map
  associated with the key :node->int `n2i`, then for any node u in
  graph g, (aget edges (n2i u)) is an array of distinct integers, one
  integer (n2i v) for each node v that is a successor of node u in the
  graph.  (n2i v) appears at most once in this vector, even if there
  are multiple parallel edges from u to v in g.

  Runs in O(n+m') time, where n is the number of nodes in the graph,
  and m' is the number of edges after all parallel edges between pairs
  of nodes are replaced with a single edge.

  Example:

  user=> (require '[ubergraph.core :as uber]
                  '[cljol.ubergraph-extras :as ubere])
  nil
  user=> (def g8 (uber/multidigraph
                  ;; nodes
                  :node-a  :node-b  :node-d  :node-c
                  ;; edges
                  [:node-a :node-b]  [:node-a :node-b]  [:node-a :node-c]
                  [:node-b :node-d]  [:node-b :node-c]))
  #'user/g8

  user=> (uber/nodes g8)
  (:node-a :node-b :node-d :node-c)

  ;; TBD: update the rest of the example

  user=> (def x (ubere/edge-vectors g8))
  #'user/x

  user=> (:node->int x)
  {:node-a 0, :node-b 1, :node-d 2, :node-c 3}

  user=> (:edges x)
  [[1 3] [2 3] [] []]

  Note that :node-a has two parallel edges to :node-b in the graph,
  but in vector number 0 that represents the edges out of :node-a, [1
  3], it contains the number 1 for :node-b only once."
  [g]
  (let [n (uber/count-nodes g)
        {:keys [node->int int->node] :as m} (dense-integer-node-labels g)]
    (assoc m :edges
           (object-array
            (map (fn [node-int]
                   (int-array
                    (map #(node->int %)
                         (uber/successors g (aget ^objects int->node
                                                  node-int)))))
                 (range n))))))


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
                          ^:unsynchronized-mutable ^long fp  ;; front stack pointer
                          ^:unsynchronized-mutable ^long bp  ;; back stack pointer
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


;; Performance notes for scc-tarjan:

;; The scc-tarjan implementation does currently allocate a separate
;; vector for each node when calling edge-vectors.

;; I can imagine a data representation of the edges of a graph that
;; would avoid allocating this memory, e.g. a PersistentList of edges
;; for each node, implemented like a list of Common Lisp cons cells in
;; memory, where a single mutable pointer could advance down this
;; linked list allocating no memory, once the mutable pointer was
;; allocated.  However, that is not how Ubergraph represents edges of
;; a node.  Even if it were, I am not sure it would be faster, because
;; of all of the bouncing around cache lines that linked lists often
;; lead to.

;; I could implement a mutable Java-style iterator object for a node
;; that points at the current position in the list of edges of an
;; Ubergraph, to avoid allocating seq objects, but that seems like a
;; significant development effort for only a possible gain in
;; performance.

;; Once the edge-vectors allocates its memory, this algorithm
;; maintains one integer index into each of those vectors, stored in
;; mutable Java arrays, so no new memory needs to be allocated after
;; edge-vectors is finished, and after scc-tarjan allocates its arrays
;; near the beginning.  This seems like a pretty decent point in the
;; design space for performance.

;; I did try out a variation with a function edge-arrays that is
;; similar to edge-vectors, but returns mutable Java arrays of ints,
;; instead of Clojure vectors of integers.  It took at least 98% as
;; much time as this implementation, from the measurements I took, so
;; not as good of an improvement as I was hoping for.

;; Note: scc-tarjan behaves as documented for all Ubergraphs,
;; regardless of whether its edges are directed, undirected, parallel,
;; or self loop (but see below), because it operates upon the
;; representation of edges returned by function edge-vectors, which
;; eliminates all parallel edges, and treats all undirected edges the
;; same as a pair of directed edges, one in each direction between the
;; pair of nodes involved, which is exactly what we want, i.e. those
;; two nodes are reachable from each other, and should be in the same
;; strongly connected component with each other.

;; TBD: The only open question in my mind is whether the core of
;; scc-tarjan cleanly handles self loop edges.  We could modify
;; edge-vectors never to return them, which would eliminate that open
;; question, but if we can determine that scc-tarjan handles them
;; cleanly, then all is well with scc-tarjan.

(defn scc-tarjan
  "Calculate the strongly connected components of a graph using
  Pearce's algorithm, which is a variant of Tarjan's algorithm that
  uses a little bit less memory than Tarjan's algorithm does.  Both
  run in linear time in the size of the graph, i.e. O(n+m) time where
  n is the number of nodes and m is the number of edges.

  This implementation is limited to Integer/MAX_VALUE = (2^31 - 1)
  nodes in the graph, because part of its implementation uses signed
  Java int's to label the nodes, and signed comparisons to compare
  node numbers to each other.  This limit could easily be increased by
  replacing int with long in those parts of the implementation.

  Keys in returned map: :components :rindex :root plus those returned
  by function edge-vectors.

  :components

  The associated value is a vector, where each vector element is a set
  of nodes of the graph g.  These sets are a partition of the nodes of
  g, i.e., every node of g is in exactly one of the sets, and no set
  is empty.  Each set represents all of the nodes in one strongly
  connected component of g.  The vector is ordered such that they are
  in a topological ordering of the components, i.e. there might be
  edges in g from a node in set i to a node in set j if i < j, but
  there are guaranteed to be no edges from a node in set j to a node
  in set i.

  :rindex

  A Java array of ints, used as part of the implementation.  I believe
  that the contents of this array can be used to solve other graph
  problems efficiently, e.g. perhaps biconnected components and a few
  other applications for depth-first search mentioned here:

  https://en.wikipedia.org/wiki/Depth-first_search#Applications

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

  This implementation is a translation of the PeaFindScc2.Imperative
  code into Clojure, maintaining the property that it is not
  recursive, and attempting to use only as much additional memory as
  the Java implementation would allocate.

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
  against them."
  [g]
  (let [n (int (uber/count-nodes g))
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
                            (let [idx (int @index)]
                              (pushFront vS v)
                              (pushFront iS 0)
                              (aset root v true)
                              (aset rindex v idx)
                              (var-set index (inc idx))))

            ;; method Imperative.finishVisiting(int v)
            finishVisiting (fn [v]
                             ;; Take this vertex off the call stack
                             (popFront vS)
                             (popFront iS)
                             ;; Update component information
                             (if (aget root v)
                               (do
                                 (var-set index (dec (int @index)))
                                 (while (and (not (isEmptyBack vS))
                                             (<= (aget rindex v)
                                                 (aget rindex (topBack vS))))
                                   (let [w (popBack vS)]
                                     (aset rindex w (int @c))
                                     (var-set index (dec (int @index)))))
                                 (aset rindex v (int @c))
                                 (var-set c (dec (int @c))))
                               ;; else
                               (pushBack vS v)))

            ;; method Imperative.beginEdge(int v, int k)
            beginEdge (fn [v k]
                        (let [g-edges (edges v)
                              w (g-edges k)]
                          (if (zero? (aget rindex w))
                            (do
                              (popFront iS)
                              (pushFront iS (inc (int k)))
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
                              num-edges (int (count g-edges))]
                          ;; Continue traversing out-edges until none left.
                          (let [return-early
                                (loop []
                                  (if (<= (int @i) num-edges)
                                    (do
                                      ;; Continuation
                                      (if (> (int @i) 0)
                                        ;; Update status for previously
                                        ;; traversed out-edge
                                        (finishEdge v (dec (int @i))))
                                      (if (and (< (int @i) num-edges)
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
              (let [num-components (- n 1 (int @c))
                    delta (inc (int @c))
                    comps (let [x (object-array num-components)]
                            (dotimes [i num-components]
                              (aset x i (transient #{})))
                            x)
                    components (loop [i 0]
                                 (if (< i n)
                                   ;; See Note 1
                                   (let [cindex (- (aget rindex (int i)) delta)]
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


;; topsort2 is unabashedly written in an imperative style with mutable
;; arrays and transients.  This is done in an attempt to achieve the
;; highest performance possible, while being written in Clojure.  I
;; have not checked how close the performance would be if this was
;; written using only immutable data.

;; All mutable values are temporary, being live (i.e. not garbage)
;; only during the execution of topsort2.  Only immutable values are
;; returned.

;; Note 1: The conditional check here is intended to skip updates to
;; sorted-in-edges-temp, if the caller indicates they were not
;; interested in it.  The goal is to maximize performance for the case
;; where the caller does not want this value.

(defn topsort2
  "Given a graph, return a topological ordering of the vertices if
  there are no cycles in the graph, or return a map with value true
  for the key :has-cycle? if there is a cycle.

  https://en.wikipedia.org/wiki/Topological_sorting

  The return value is a map, where the keys depend upon the options
  given, and whether the graph contains a cycle.

  :has-cycle? - boolean

  This key is always present in the returned map.  It is true if a
  cycle was found in the graph, otherwise false.  The cycle found is
  not returned, only the fact that one exists.

  :topological-order - vector of nodes of `graph`

  If no cycle was found, this key is always present, and its value is
  a vector whose elements are the nodes of `graph`, in a topologically
  sorted order, each node appearing exactly once.

  :sorted-in-edges - vector of edge lists

  This key is only present in the returned map if the `opts` map is
  provided with key :sorted-in-edges with a logical true value, and no
  cycle is found in the graph.  When it is present, its associated
  value is a vector.  Let T be the vector that is the value associated
  with the :topological-order key.  Element i of the vector is a list
  of all edges into node (nth T i), where the edges are sorted by
  their source nodes, and those nodes are in reverse topological
  order.

  topsort2 implements Kahn's algorithm.  Pseudocode for Kahn's
  algorithm is given on the Wikipedia page.

  Runs in O((n+m)*C) time, where n is the number of nodes, m is the
  number of edges, and C is the time to do a single lookup or addition
  to a Clojure map, set, or vector of size max(m,n).  C is often
  described as 'effectively constant'.  TBD: Link to details on C.

  Graphs with multiple parallel edges from one node u to another node
  v are supported.

  If a graph has 'self loops', i.e. an edge from a node to itself,
  that is considered a cycle.  Undirected graphs with an edge between
  node u and node v are treated as having directed edges from u to v
  and from v to u, and thus have a cycle."
  ([graph]
   (topsort2 graph {}))
  ([graph opts]
   (let [n (count (uber/nodes graph))
         {^objects i2n :int->node, ^ints n2i :node->int} (dense-integer-node-labels graph)
         sorted-in-edges-temp (if (get opts :sorted-in-edges)
                                (object-array n))
         [^ints in-degree candidates]
         (let [^ints tmp (int-array n)]
           (let [n (long n)]
             (loop [i 0
                    candidates ()]
               (if (< i n)
                 (let [d (int (uber/in-degree graph (aget i2n i)))]
                   (aset tmp i d)
                   (recur (inc i) (if (zero? d)
                                    (cons i candidates)
                                    candidates)))
                 [tmp candidates]))))
         t2i (int-array n)

         [final-t T]
         (loop [t 0
                T (transient [])
                candidates candidates]
           (if-let [candidates (seq candidates)]
             (let [i (int (first candidates))
                   u (aget i2n i)]
               (aset t2i t i)
               (let [next-candidates
                     (loop [out-edges (uber/out-edges graph u)
                            cand (next candidates)]
                       (if-let [out-edges (seq out-edges)]
                         (let [e (first out-edges)
                               v (uber/dest e)
                               j (int (n2i v))
                               new-d (dec (aget in-degree j))]
                           (when sorted-in-edges-temp  ;; Note 1
                             (aset sorted-in-edges-temp
                                   j (cons e (aget sorted-in-edges-temp j))))
                           (aset in-degree j new-d)
                           (recur (next out-edges) (if (zero? new-d)
                                                     (cons j cand)
                                                     cand)))
                         ;; else return updated list of candidates
                         cand))]
                 (recur (inc t) (conj! T u) next-candidates)))
             ;; else
             [t (persistent! T)]))]

     (if (< (int final-t) n)
       ;; there is a cycle in the graph, so no topological ordering
       ;; exists.
       {:has-cycle? true}
       (let [sorted-in-edges
             (when sorted-in-edges-temp
               (loop [t 0
                      sorted-in-edges (transient [])]
                 (if (< t n)
                   (let [i (aget t2i t)]
                     (recur (inc t) (conj! sorted-in-edges
                                           (aget sorted-in-edges-temp i))))
                   (persistent! sorted-in-edges))))
             ret {:has-cycle? false, :topological-order T}]
         (if sorted-in-edges
           (assoc ret :sorted-in-edges sorted-in-edges)
           ret))))))


(defn remove-loops-and-parallel-edges
  "Given an Ubergraph, return one that has the same nodes and edges,
  except all self loop edges will be removed, and for all pairs of
  nodes that have multiple parallel edges between them, all but one of
  them will be removed.  The one that remains will be chosen
  arbitrarily."
  [graph]
  (let [g (reduce (fn remove-self-loop [g node]
                    (apply uber/remove-edges g (uber/find-edges g node node)))
                  graph (uber/nodes graph))]
    (reduce (fn trim-from-node [g src]
              (reduce (fn trim-from-src-to-dest [g dest]
                        (apply uber/remove-edges
                               g (rest (uber/find-edges g src dest))))
                      g (uber/successors g src)))
            g (uber/nodes g))))


;; Note that dag-transitive-reduction-slow does not actually check
;; whether the input graph is a DAG, but it has been written so that
;; it will give the correct result if the input graph is given a DAG.
;; If not, it might return a graph that is not a transitive reduction
;; of the input graph.

(defn dag-transitive-reduction-slow
  [graph]
  (let [g (remove-loops-and-parallel-edges graph)]
    (reduce (fn remove-edge-if-redundant [g edge]
              (let [src (uber/src edge)
                    dest (uber/dest edge)
                    dest? #(= % dest)
                    ;; Pass pre-traverse* a successors function that
                    ;; allows any edge of g to be traversed in the
                    ;; search for a path from src to dest, _except_
                    ;; the one edge directly from src to dest.
                    succ (fn successors-except-by-edge [node]
                           (if (= node src)
                             (remove dest? (uber/successors g node))
                             (uber/successors g node)))
                    reachable-from-src (pre-traverse* succ src)]
                (if (some dest? reachable-from-src)
                  ;; We found a longer way to reach dest from src, so
                  ;; remove the direct edge.
                  (uber/remove-edges g edge)
                  g)))
            g (uber/edges g))))


;; Steps to find transitive reduction of an arbitrary graph:

;; There are multiple choices here, so let us just work out one way to
;; do it that seems reasonably efficient.

;; Start with finding a condensation C of the input graph G.  If G
;; contains cycles, C will have fewer nodes and edges than G, which
;; should make later steps take less time.

;; Find the weakly connected components of C.

;; For each weakly connected component, run Algorithm E from my notes
;; on it, starting with finding a topological ordering of the nodes,
;; and sorting all edges into each node in reverse topological order
;; by source node.  TBD: Can this be done _while_ finding the
;; condensation C?  The topological ordering definitely can, but can
;; the sorting of edges also be done at the same time?

;; In case that sorting of edges is not easy to merge into the
;; condensation code, is it straightforward to do the edge sorting
;; starting with the topological sorting of nodes in C?

#_(defn dag-transitive-reduction
  [graph]
  (let [{:keys [has-cycle? topological-order sorted-in-edges]}
        (topsort2 graph {:sorted-in-edges true})
        T topological-order
        _ (assert (not has-cycle?))
        ;; tbd: create node->int map vertex2t, inverse mapping of T
        ]


    ))

(defn check-same-reachability-slow
  "Given two graphs g1 and g2 that have nodes node-set in common with
  each other, check whether among all ordered pairs of nodes (n1, n2)
  in node-set that there is a path from n1 to n2 in g1 if, and only
  if, there is a path from n1 to n2 in g2.

  If you do not specify a node-set, it is assumed that both graphs
  have the same set of nodes, and reachability will be compared for
  all pairs of nodes.

  Note: This function assumes the two graphs both have all nodes in
  node-set in common with each other.  It might behave in an
  undocumented fashion (e.g. return wrong answers, throw an exception)
  if they do not.

  '-slow' is in the name because by design, this function performs its
  task in as simple a way as possible, taking O(n^3 + m*n^2) time,
  where n is the number of nodes in node-set, and m is the maximum
  number of edges between the two graphs.  It is designed not to be
  fast, but to be 'obviously correct', as an aid in checking the
  results of other faster algorithms, such as those that compute a
  minimum equivalent graph, transitive reduction, or transitive
  closure.

  Return a map that always contains the key :pass with an associated
  boolean value.

  If they have the same reachability, the value associated with the
  key :pass is true, otherwise false.

  If they have different reachability, also include the following keys
  in the map returned, describing one difference in reachability found
  between g1 and g2.

  :from-node - value is one node in node-set

  :to-node - value is one node in node-set

  :g1-reaches - boolean value that is true if a path was found
  from (:from-node ret) to (:to-node ret) in graph g1.

  :g2-reaches - boolean value that is true if a path was found
  from (:from-node ret) to (:to-node ret) in graph g2.

  The boolean values associated with keys :g1-reaches and :g2-reaches
  will be different."
  ([g1 g2]
   (check-same-reachability-slow g1 g2 (uber/nodes g1)))
  ([g1 g2 node-set]
   (let [ret (first
              (for [n1 node-set
                    n2 node-set
                    :when (not= n1 n2)
                    :let [n2? #(= % n2)
                          g-reaches? (fn [g]
                                       (some n2?
                                             (pre-traverse*
                                              #(uber/successors g %) n1)))
                          g1-reaches? (g-reaches? g1)
                          g2-reaches? (g-reaches? g2)]
                    :when (not= g1-reaches? g2-reaches?)]
                {:pass false
                 :from-node n1
                 :to-node n2
                 :g1-reaches g1-reaches?
                 :g2-reaches g2-reaches?}))]
     (or ret {:pass true}))))
