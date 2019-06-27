(ns cljol.dig-test
  (:require [clojure.test :refer :all]
            [cljol.dig9 :refer :all]))

(def ref-array (object-array 5))
(def prim-array (int-array 4 [2 4 6 8]))
(def two-dee-array (to-array-2d [[1 2 3] [4 5 6] [7 8 9]]))

(deftest a-test
  (are [x] (let [ex (reachable-objmaps [x])]
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
