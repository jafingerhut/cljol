(ns cljol.jdk8-and-earlier
  (:import (java.lang.reflect Field)))

(set! *warn-on-reflection* true)


(defn obj-field-value [obj ^Field fld _inaccessible-field-val-sentinel]
  (. fld setAccessible true)
  (.get fld obj))
