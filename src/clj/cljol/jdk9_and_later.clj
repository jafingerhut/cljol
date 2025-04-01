(ns cljol.jdk9-and-later
  (:import (java.lang.reflect Field InaccessibleObjectException)))

(set! *warn-on-reflection* true)

(def packages-to-consider-opening (atom #{}))

(defn get-exc-message [exc]
  (-> exc Throwable->map :via (nth 0) :message))

(defn get-package-info [msg]
  (let [m (re-find #"accessible: module (.*) does not \"opens (\S+)\" to unnamed" msg)]
    (if m
      {:module-name (m 1), :package-name (m 2)}
      nil)))

(defn obj-field-value [obj ^Field fld inaccessible-field-val-sentinel]
  (try
    (. fld setAccessible true)
    (.get fld obj)
    (catch InaccessibleObjectException e
      (let [msg (get-exc-message e)
            package-info (get-package-info msg)]
        (if package-info
          (let [[old new] (swap-vals! packages-to-consider-opening
                                      conj package-info)]
            (when (not= old new)
              (printf "ERROR: Add these JVM command line options to avoid errors determining field values of objects: --add-opens %s/%s=ALL-UNNAMED\n"
                      (:module-name package-info)
                      (:package-name package-info))))
          (printf "ERROR: Could not find package name in exception message: %s"
                  msg)))
      inaccessible-field-val-sentinel)))
