(defproject cljol "0.3.0"
  :description "Analyze Java objects in memory using the Java Object Layout library"
  :url "http://github.com/jafingerhut/cljol"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :source-paths ["src/clj"]
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [com.fingerhutpress.cljol_jvm_support/cljol_jvm_support "1.0"]
                 [ubergraph "0.8.2"
                  :exclusions [tailrecursion/cljs-priority-map]]]
  :profiles {:uberlocal {:dependencies [[ubergraph "0.5.4-andy-mods"]]}
             :master {:dependencies [[org.clojure/clojure "1.11.0-master-SNAPSHOT"]]}
            }
  :jvm-opts ^:replace [
                       ;;"-XX:+PrintGC"
                       ;;"-XX:+PrintGCDetails"
                       ;;"-Djol.tryWithSudo=true"
                       ]
  )
