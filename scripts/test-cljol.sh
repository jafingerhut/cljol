#! /bin/bash

JDK_INSTALL_DIR="$HOME/p4-docs/jdks"

opts="$1"

CLJOL_DEP='{:deps {cljol/cljol {:git/url "https://github.com/jafingerhut/cljol" :sha "dc17a8e02f5abf7aacf6c1962c627fe7b19993d0"}}}'
EVAL_EXPRS1="(require '[cljol.dig9 :as d]) (def my-map {\"a\" 1 \"foobar\" 3.5}) (d/write-dot-file [my-map] \"my-map.dot\") (System/exit 0)"
EVAL_EXPRS2="(require '[cljol.dig9 :as d]) (def my-map {\"a\" 1 \"foobar\" 3.5 \"b\" (java.util.TreeSet. [])}) (d/write-dot-file [my-map] \"my-map.dot\") (System/exit 0)"
ADD_OPENS_JAVA_LANG="-J--add-opens -Jjava.base/java.lang=ALL-UNNAMED"
ADD_OPENS_JAVA_UTIL="-J--add-opens -Jjava.base/java.util=ALL-UNNAMED"

for ver in 8 11 16 17 18 19 20 21 22 23 24
do
    echo ""
    if [ -e ${JDK_INSTALL_DIR}/setup-jdk-${ver}.bash ]
    then
	source ${JDK_INSTALL_DIR}/setup-jdk-${ver}.bash
    else
	continue
    fi
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
	    clojure ${ADD_OPENS_JAVA_LANG} ${ADD_OPENS_JAVA_UTIL} -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS2}"
	    set +x
	    ;;
	7)
	    set -x
	    clojure ${ADD_OPENS_JAVA_LANG} -J-Djdk.attach.allowAttachSelf -J-Djol.tryWithSudo=true -J-XX:+EnableDynamicAgentLoading -Sdeps "${CLJOL_DEP}" -M --eval "${EVAL_EXPRS1}"
	    set +x
	    ;;
	*)
	    1>&2 echo "Unknown command line option: ${opts}"
	    exit 1
	    ;;
    esac
    set +x
    mv my-map.dot my-map-jdk-${ver}.dot
done

