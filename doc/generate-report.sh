#! /bin/bash

# I do not understand why yet, but for this combination of versions:
# report-Mac-OS-X-10.13.6-jdk-AdoptOpenJDK-11.0.3-clj-1.10.1.txt

# I get no size mismatches in 'inst-size-diffs' part of output when I
# do not use the :priv2 alias, but a size mismatch on every class when
# I use the :priv2 alias.

# The :priv2 alias is defined this way when I observed this:
# :priv2 {:jvm-opts ["-Djdk.attach.allowAttachSelf"]}

#clojure -A:classgraph:jamm -e "(require,'[cljol.reflection-test-helpers,:as,t]),(t/report)"
clojure -A:classgraph:jamm:priv -e "(require,'[cljol.reflection-test-helpers,:as,t]),(t/report)"
#clojure -A:classgraph:jamm:priv2 -e "(require,'[cljol.reflection-test-helpers,:as,t]),(t/report)"
