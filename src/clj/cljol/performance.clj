(ns cljol.performance
  (:import (java.lang.management ManagementFactory GarbageCollectorMXBean)))

(set! *warn-on-reflection* true)


(defn gc-collection-stats []
  (let [mxbeans (ManagementFactory/getGarbageCollectorMXBeans)]
    (apply merge-with +
      (for [^GarbageCollectorMXBean gc mxbeans]
        {:gc-collection-count (max 0 (. gc getCollectionCount))
         :gc-collection-time-msec (max 0 (. gc getCollectionTime))}))))


(defn gc-collection-stats-delta [start-stats end-stats]
  (merge-with - end-stats start-stats))


(defn print-perf-stats [perf-stats]
  (let [{:keys [time-nsec gc-stats]} perf-stats
        {:keys [gc-collection-count gc-collection-time-msec]} gc-stats]
    (println (/ time-nsec 1000000.0) "msec,"
             gc-collection-count "gc-count,"
             gc-collection-time-msec "gc-time-msec")))


(defmacro my-time [expr]
  `(let [start-nsec# (. System (nanoTime))
         start-gc-stats# (gc-collection-stats)
         ret# ~expr]
     {:time-nsec (- (. System (nanoTime)) start-nsec#)
      :gc-stats (gc-collection-stats-delta start-gc-stats#
                                           (gc-collection-stats))
      :ret ret#}))
