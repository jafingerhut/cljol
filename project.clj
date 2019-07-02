(defproject cljol "0.2.0"
  :description "Analyze Java objects in memory using the Java Object Layout library"
  :url "http://github.com/jafingerhut/cljol"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [org.openjdk.jol/jol-core "0.9"]
                 [ubergraph "0.5.3"]
                 [rhizome "0.2.5"]]
  :profiles {:uberlocal {:dependencies [[ubergraph "0.5.4-andy-mods"]]}}
  :jvm-opts ^:replace [
                       ;;"-XX:+PrintGC"
                       ;;"-XX:+PrintGCDetails"
                       ;;"-Djol.tryWithSudo=true"
                       ]
  )
