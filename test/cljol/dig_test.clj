(ns cljol.dig-test
  (:require [clojure.test :refer :all]
            [ubergraph.core :as uber]
            [cljol.dig9 :refer :all]))

(def ref-array (object-array 5))
(def prim-array (int-array 4 [2 4 6 8]))
(def two-dee-array (to-array-2d [[1 2 3] [4 5 6] [7 8 9]]))

(deftest a-test
  (are [x] (let [ex (reachable-objmaps [x] {})]
             (and (nil? (validate-obj-graph ex))
                  (not (any-objects-overlap? ex))
                  (not (any-object-moved? ex))))
       #{}
       #{5 7 12 13}
       #{nil}
       {5 7, 12 13}
       {5 false, nil 13}
       ref-array
       prim-array
       two-dee-array
       {{5 false, nil 13} "first-kv",
        "second-kv" {8 9, 10 11}}
       ))


(deftest ubergraph-successors-test
  ;; The current implementation of cljol.graph/edge-vectors assumes
  ;; that ubergraph.core/successors returns each successor node at
  ;; most once, even if there are multiple parallel edges to it.  Make
  ;; a test to check this.
  (let [g (uber/multidigraph [1 2]
                             [1 2]
                             [1 3]
                             [1 4]
                             [4 2]
                             [4 2])]
    (is (uber/successors g 1))
    (is (= #{2 3 4} (set (uber/successors g 1))))
    (is (apply distinct? (uber/successors g 1)))
    (is (= false (apply distinct? (map uber/dest (uber/out-edges g 1)))))
    (is (uber/predecessors g 2))
    (is (= #{1 4} (set (uber/predecessors g 2))))
    (is (apply distinct? (uber/predecessors g 2)))
    (is (= false (apply distinct? (map uber/src (uber/in-edges g 2)))))))
