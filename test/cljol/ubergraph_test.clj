(ns cljol.ubergraph-test
  (:require [clojure.test :refer :all]
            [ubergraph.core :as uber]))


(defn gen [g basename]
  (doseq [format [:dot]]
    (let [fname (str basename "." (name format))]
      (println "Writing file" fname "...")
      (uber/pprint g)
      (uber/viz-graph g {:save {:filename (str basename "." (name format))
                                :format format}}))))


(deftest graphs-with-labels-bad-for-graphviz-dot
  (let [strings [(str (char 0))
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
        (gen g (str "g" idx))))))
