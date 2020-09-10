(ns cljol.performance
  (:refer-clojure :exclude [time])
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
        {:keys [gc-collection-count gc-collection-time-msec]} gc-stats
        time-msec (/ time-nsec 1000000.0)]
    (println (if (< time-msec 1) (str time-msec) (format "%.1f" time-msec)) "msec,"
             gc-collection-count "gc-count,"
             gc-collection-time-msec "gc-time-msec")))


(defmacro time [expr]
  `(let [start-nsec# (. System (nanoTime))
         start-gc-stats# (gc-collection-stats)
         ret# ~expr]
     {:time-nsec (- (. System (nanoTime)) start-nsec#)
      :gc-stats (gc-collection-stats-delta start-gc-stats#
                                           (gc-collection-stats))
      :ret ret#}))


(defmacro time-record-results [expr a]
  `(let [ret# (time ~expr)]
     (swap! ~a conj (dissoc ret# :ret))
     ret#))


(defn add-times
  [{time-nsec1 :time-nsec
    {count1 :gc-collection-count
     time-msec1 :gc-collection-time-msec} :gc-stats}
   {time-nsec2 :time-nsec
    {count2 :gc-collection-count
     time-msec2 :gc-collection-time-msec} :gc-stats}]
  {:time-nsec (+ time-nsec1 time-nsec2)
   :gc-stats {:gc-collection-count (+ count1 count2)
              :gc-collection-time-msec (+ time-msec1 time-msec2)}})

(defn subtract-times
  [{time-nsec1 :time-nsec
    {count1 :gc-collection-count
     time-msec1 :gc-collection-time-msec} :gc-stats}
   {time-nsec2 :time-nsec
    {count2 :gc-collection-count
     time-msec2 :gc-collection-time-msec} :gc-stats}]
  {:time-nsec (- time-nsec1 time-nsec2)
   :gc-stats {:gc-collection-count (- count1 count2)
              :gc-collection-time-msec (- time-msec1 time-msec2)}})
