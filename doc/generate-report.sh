#! /bin/bash

clojure -A:classgraph -e "(require,'[cljol.reflection-test-helpers,:as,t]),(t/report)"
