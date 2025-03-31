#! /bin/bash

#opts=1
#opts=2
#opts=3
opts=4
#opts=5
#opts=6
#opts=7

CLJOL_DEP='{:deps {cljol/cljol {:git/url "https://github.com/jafingerhut/cljol" :sha "dc17a8e02f5abf7aacf6c1962c627fe7b19993d0"}}}'
EVAL_EXPRS1="(require '[cljol.dig9 :as d]) (def my-map {\"a\" 1 \"foobar\" 3.5}) (d/write-dot-file [my-map] \"my-map.dot\") (System/exit 0)"
EVAL_EXPRS2="(require '[cljol.dig9 :as d]) (def my-map {\"a\" 1 \"foobar\" 3.5 \"b\" (java.util.TreeSet. [])}) (d/write-dot-file [my-map] \"my-map.dot\") (System/exit 0)"
ADD_OPENS_JAVA_LANG="-J--add-opens -Jjava.base/java.lang=ALL-UNNAMED"

for ver in 11 16 17 18 19 20 21 22 23 24
do
    echo ""
    source $HOME/jdks/setup-jdk-${ver}.bash
    java -version | head -n 1
    case ${opts} in
	1)
	    set -x
	    clojure -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
	2)
	    set -x
	    clojure -J-Djdk.attach.allowAttachSelf -J-Djol.tryWithSudo=true -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
	3)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -J-Djdk.attach.allowAttachSelf -J-Djol.tryWithSudo=true -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
	4)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
	5)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS2}"
	    set +x
	    ;;
	6)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -J--add-opens -Jjava.base/java.util=ALL-UNNAMED "${EVAL_EXPRS2}"
	    set +x
	    ;;
	7)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -J-Djdk.attach.allowAttachSelf -J-Djol.tryWithSudo=true -J-XX:+EnableDynamicAgentLoading -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
    esac
    set +x
    mv my-map.dot my-map-jdk-${ver}.dot
done

