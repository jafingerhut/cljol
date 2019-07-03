(ns cljol.graph
  (:require [clojure.set :as set]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]))


;; Code here is intended to be generic to anyone that uses ubergraph
;; and/or perhaps also loom for representing and manipulating graph
;; data structures.


(defn leaf-node? [g n]
  (zero? (uber/out-degree g n)))


(defn leaf-nodes [g]
  (filter #(leaf-node? g %) (uber/nodes g)))


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
