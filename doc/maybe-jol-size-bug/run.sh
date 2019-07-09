#! /bin/bash

set -ex

uname -a
java -version

M2="${HOME}/.m2/repository"
JOL_CORE_0_9_JAR="${M2}/org/openjdk/jol/jol-core/0.9/jol-core-0.9.jar"

javac -classpath "${JOL_CORE_0_9_JAR}" MaybeBug.java
#echo "${PWD}"
java -classpath "${PWD}:${JOL_CORE_0_9_JAR}" MaybeBug
#sudo java -classpath "${PWD}:${JOL_CORE_0_9_JAR}" MaybeBug
