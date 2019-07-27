(ns cljol.version-info
  (:require [clojure.string :as str]))

(set! *warn-on-reflection* true)


(defn clean-os-version [s]
  (str/replace s #"-generic$" ""))


(defn clean-vendor [s]
  (str/replace s #" Corporation$" ""))
  

(defn calc-version-data []
  (let [props (into (sorted-map) (System/getProperties))
        os-desc (str (props "os.name")
                     "-" (clean-os-version (props "os.version")))
        jvm-desc (str "jdk-" (clean-vendor (props "java.vendor"))
                      "-" (props "java.version"))
        clj-desc (str "clj-" (clojure-version))]
    {:os-desc os-desc
     :jvm-desc jvm-desc
     :clj-desc clj-desc
     :stack-desc (str/replace (str os-desc "-" jvm-desc "-" clj-desc)
                              " " "-")}))


(def version-data (delay (calc-version-data)))
