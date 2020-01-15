(ns cljol.ubergraph-test
  (:import (java.io File))
  (:require [clojure.test :refer :all]
            [clojure.java.shell :as sh]
            [clojure.java.io :as io]
            [ubergraph.core :as uber]))


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
