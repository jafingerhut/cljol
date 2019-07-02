(ns cljol.ubergraph-test
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [ubergraph.core :as uber]))


(defn sh-out [& args]
  (let [{:keys [exit out err]} (apply sh/sh args)]
    (println "exit status" exit)
    (println "out" out)
    (println "err" err)))

(defn gen [g basename]
  (println "Writing file" (str basename ".dot") "...")
  (uber/viz-graph g {:auto-label false
                     :save {:filename (str basename ".dot")
                            :format :dot}})
  (println "Gen file" (str basename ".pdf") "...")
  (sh-out "dot" "-Tpdf" (str basename ".dot")
          "-o" (str basename ".pdf"))
  (println "Writing file" (str basename "-auto.dot") "...")
  (uber/viz-graph g {:auto-label true
                     :save {:filename (str basename "-auto.dot")
                            :format :dot}})
  (println "Gen file" (str basename "-auto.pdf") "...")
  (sh-out "dot" "-Tpdf" (str basename "-auto.dot")
          "-o" (str basename "-auto.pdf"))
  )


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
