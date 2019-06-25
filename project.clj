(defproject cljol "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
                 ;;[org.clojure/clojure "1.6.0"]
                 [org.clojure/clojure "1.10.1"]
                 ;;[org.openjdk.jol/jol-core "0.3.2"]
                 [org.openjdk.jol/jol-core "0.9"]
                 ;;[org.openjdk.jol/jol-core "0.4-SNAPSHOT"]
                 [rhizome "0.2.5"]
                 ]
  :jvm-opts ^:replace [
                       ;;"-XX:+PrintGC"
                       "-XX:+PrintGCDetails"
                       ]
  )
