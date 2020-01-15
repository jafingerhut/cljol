(ns cljol.graph
  (:require [clojure.pprint :as pp]
            [ubergraph.core :as uber]
            [cljol.ubergraph-extras :as ubere]
            [cljol.performance :as perf :refer [my-time print-perf-stats]]))

(set! *warn-on-reflection* true)

;; Code here is intended to be generic to anyone that uses ubergraph
;; and/or perhaps also loom for representing and manipulating graph
;; data structures.


(defn leaf-node? [g n]
  (zero? (uber/out-degree g n)))


(defn leaf-nodes [g]
  (filter #(leaf-node? g %) (uber/nodes g)))


(defn remove-all-attrs-except
  [g attr-keys-to-keep]
  (reduce (fn [g n] (uber/set-attrs g n (select-keys (uber/attrs g n)
                                                     attr-keys-to-keep)))
          g (concat (uber/nodes g) (uber/edges g))))


(defn remove-all-attrs
  [g]
  (remove-all-attrs-except g []))


;; The function bounded-reachable-node-stats2 achieves the goal
;; described in the next paragraph.

;; Optimize this for the probable common case in Clojure where a graph
;; of nodes is acyclic, and the corresponding undirected graph is a
;; tree.  This includes a graph like the one shown below, that is not
;; a directed tree because node C has in-degree 2, not 1.  Even that
;; graph should be possible to calculate total reachable node sizes in
;; linear time without having to calculate sets of reachable nodes
;; explicitly, stored in memory.  Even if the scc-graph of the
;; original graph has the structure shown below, we do not need to
;; calculate reachable node sets.

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
  (let [reachable-node-map (ubere/reachable-nodes g)]
    (into {}
          (for [[node-set reachable-node-set] reachable-node-map
                :let [sum (reduce + (map #(node-size-fn g %)
                                         reachable-node-set))]
                node node-set]
            [node {:total-size sum
                   :num-reachable-nodes (count reachable-node-set)}]))))


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
  [g n node-count-fn node-size-fn node-count-min-limit total-size-min-limit
   return-nodes-reached?]
  (loop [remaining-reachable-nodes (ubere/pre-traverse g n)
         num-dfs-traversed-nodes 0
         num-reachable-nodes 0
         total-size 0
         nodes-reached (transient #{})]
    (let [s (seq remaining-reachable-nodes)]
      (if (or (and (> num-reachable-nodes node-count-min-limit)
                   (> total-size total-size-min-limit))
              (not s))
        [(merge
          {:num-reachable-nodes num-reachable-nodes
           :total-size total-size}
          (if return-nodes-reached?
            {:nodes-reached (persistent! nodes-reached)}))
         (inc num-dfs-traversed-nodes)]

        ;; else
        (let [n2 (first s)]
          (recur (rest s)
                 (inc num-dfs-traversed-nodes)
                 (+ num-reachable-nodes (long (node-count-fn n2)))
                 (+ total-size (long (node-size-fn n2)))
                 (if return-nodes-reached? (conj! nodes-reached n2))))))))


(defn all-predecessors-in-set? [g nodes-to-check-coll node-set]
  (every? (fn [n]
            (every? #(contains? node-set %)
                    (uber/predecessors g n)))
          nodes-to-check-coll))


(defn node-status [g n node-set-reached-from-g reachable-set-complete?]
  (cond
    (not reachable-set-complete?)
    ;; In this case, we did not finish the DFS traversal of nodes
    ;; reachable from n.  Node n might be an owner, but we have not
    ;; done enough work to find out.  Thus we only have partial
    ;; statistics for its reachable nodes.
    :unknown
    
    ;; For the rest of the cases below, we know that we did finish the
    ;; DFS traversal, and we do have full accurate stiatics for all of
    ;; n's reachable nodes.

    ;; If we check that all nodes reached, other than
    ;; n itself, have all of their predecessor nodes within the set of
    ;; nodes traversed, then n is an owner.
    (all-predecessors-in-set? g (disj node-set-reached-from-g n)
                              node-set-reached-from-g)
    :owner
    
    ;; Otherwise, we know for sure that node n is not an owner,
    ;; because there is an edge into one of its reachable nodes that
    ;; can be reached via a path not passing through n at all.
    :else :not-owner))


(defn complete-statistics? [status]
  ;; As indicated in function node-status above, these are all node
  ;; status values for which we know we have complete statistics on
  ;; all reachable nodes.
  (contains? #{:owner :not-owner} status))


;; Note 3

;; custom-successors-fn is the same as the normal successors in the
;; scc-graph, except that if a node is categorized as an owner, we
;; stop the DFS at that point as if it were a leaf node, i.e. as if it
;; had no edges out.  This can make the DFS significantly faster for
;; owners, for whom we know all of the stats already, without having
;; to re-traverse the nodes that can only be reached through them.

(defn bounded-reachable-node-stats2
  "Similar in goal to total-reachable-node-size, but goes to some
  lengths to run faster, while in some cases producing resulting
  statistics that are only partial counts of number of reachable
  nodes, and total reachable node size.

  Returns a graph g2 that is the same as the one given, except that
  the nodes have some additional attributes added to them, or if the
  nodes already had those attributes, the values of those attributes
  are replaced with new values as described here.

  Node attributes given a value by this function in the returned
  graph: :scc-num-nodes :partial-statistics :num-reachable-nodes :total-size

  :scc-num-nodes

  Number of nodes that are in the same strongly connected component as
  the node with this attribute.  All of those are definitely reachable
  from each other, so the :num-reachable-nodes attributes will always
  be at least this large, and the :total-size attribute will always be
  at least equal to the total size of all of the nodes in the same
  strongly connected component.

  :complete-statistics

  false means that :num-reachable-nodes and :total-size represent the
  values for some nodes reachable from this one, but not for all such
  reachable nodes.  This can happen depending upon the structure of
  the graph, if the algorithm deems it would take excessive time to
  calculate an exact value.  true means that :num-reachable-nodes
  and :total-size represent exact totals for all reachable nodes.

  :num-reachable-nodes

  TBD

  :total-size

  TBD"
  [g node-size-fn opts]
  (let [debug-level (get opts :bounded-reachable-node-stats2-debuglevel 0)
        {scc-data :ret :as scc-perf} (my-time (ubere/scc-graph g))
        {:keys [scc-graph node->scc-set components]} scc-data
        _ (when (>= debug-level 1)
            (print "The scc-graph has" (uber/count-nodes scc-graph) "nodes and"
                   (uber/count-edges scc-graph) "edges, took: ")
            (print-perf-stats scc-perf))
        num-reachable-nodes-in-scc (into {}
                                         (for [sccg-node (uber/nodes scc-graph)]
                                           [sccg-node (count sccg-node)]))
        total-size-in-scc (into {}
                                (for [sccg-node (uber/nodes scc-graph)]
                                  [sccg-node
                                   (reduce + (map #(node-size-fn g %)
                                                  sccg-node))]))
        sccg-start-nodes (set (filter (fn [sccg-node]
                                        (some #(uber/attr g % :starting-object?)
                                              sccg-node))
                                      (uber/nodes scc-graph)))
        max-nodes-to-traverse-in-one-dfs (get opts :bounded2-max-nodes-to-traverse-in-one-dfs 50)
        time1 (. System (nanoTime))
        sccg-node-info
        (loop [remaining-sccg-nodes (rseq components)
               sccg-node-info {}
               comp-count 0]
          (if-let [rc (seq remaining-sccg-nodes)]
            (let [owner? #(= :owner (get-in sccg-node-info [% :status]))
                  sccg-node (first rc)
                  start-node? (contains? sccg-start-nodes sccg-node)
                  max-nodes (if start-node?
                              Double/POSITIVE_INFINITY
                              max-nodes-to-traverse-in-one-dfs)
                  ;; See Note 3
                  custom-successors-fn (fn [node]
                                         (if (owner? node)
                                           ()
                                           (uber/successors scc-graph node)))
                  [final-stats num-nodes-traversed]
                  (loop [dfs-nodes (ubere/pre-traverse* custom-successors-fn
                                                        sccg-node)
                         num-dfs-steps 0
                         nodes-reached (transient #{})
                         num-reachable-nodes 0
                         total-size 0]
                    (if-let [s (and (< num-dfs-steps max-nodes)
                                    (seq dfs-nodes))]
                      (let [n (first s)
                            n-owner? (owner? n)
                            stats (if n-owner? (get-in sccg-node-info
                                                       [n :statistics]))
                            new-num-reachable-nodes
                            (if n-owner?
                              (:num-reachable-nodes stats)
                              (num-reachable-nodes-in-scc n))
                            new-total-size (if n-owner?
                                             (:total-size stats)
                                             (total-size-in-scc n))]
                        (recur (rest s)
                               (inc num-dfs-steps)
                               (conj! nodes-reached n)
                               (+ num-reachable-nodes (long new-num-reachable-nodes))
                               (+ total-size (long new-total-size))))
                      ;; else
                      [{:nodes-reached (persistent! nodes-reached)
                        :statistics {:num-reachable-nodes num-reachable-nodes
                                     :total-size total-size}}
                       num-dfs-steps]))

                  ;; If this condition is true, then we definitely
                  ;; completed the DFS traversal.  If the two numbers
                  ;; compared are equal, maybe we traversed all nodes,
                  ;; but we do not have enough information to tell, so
                  ;; assume no.
                  dfs-completed? (< num-nodes-traversed max-nodes)
                  new-status (node-status scc-graph sccg-node
                                          (:nodes-reached final-stats)
                                          dfs-completed?)
                  new-node-info {:status new-status
                                 :statistics (:statistics final-stats)}]
              (recur (rest rc) (assoc sccg-node-info sccg-node new-node-info)
                     (inc comp-count)))
            
            ;; else
            sccg-node-info))]
    (when (>= debug-level 1)
      (println "frequencies of occurrences of nodes with different :status attribute:")
      (pp/pprint (frequencies (map :status (vals sccg-node-info))))
      (println))
    (reduce (fn [g sccg-node]
              (let [{:keys [status statistics]} (sccg-node-info sccg-node)
                    stat-attrs
                    (assoc statistics
                           :scc-num-nodes (num-reachable-nodes-in-scc sccg-node)
                           :debug-status status
                           :complete-statistics (complete-statistics? status))]
                (reduce (fn [g g-node]
                          (uber/add-attrs g g-node stat-attrs))
                        g sccg-node)))
            g (uber/nodes scc-graph))))



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
