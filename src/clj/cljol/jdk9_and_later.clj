(ns cljol.jdk9-and-later
  (:import (java.lang.reflect Field InaccessibleObjectException)))


(defn obj-field-value [obj ^Field fld inaccessible-field-val-sentinel]
  (try
    (. fld setAccessible true)
    (.get fld obj)
    (catch InaccessibleObjectException e
      inaccessible-field-val-sentinel)))
