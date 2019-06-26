# Introduction

cljol is specific to Clojure on Java.

cljol uses the [Java Object
Layout](https://openjdk.java.net/projects/code-tools/jol) library to
determine the precise size of a Java object, and all of the objects
that it references, either directly, or by following a chain of
references through multiple Java objects.

It can create images of these graphs, either popping up a window using
the `view` function, or writing to a GraphViz dot file using the
`write-dot-file` function.

Perhaps some day it might be used to create nice figures showing how
many of these objects are shared between two Clojure collections.
There is some code here written towards the direction of achieving
that, but it is at best not well tested or bug-ridden, if it even runs
at all.

Note: I see output like that shown below in my REPL, the first time I
run `view` or `write-dot-file`, at least on Ubuntu 18.04 Linux with
OpenJDK 11 and Clojure 1.10.1.  According to its documentation, the
Java Object Layout library is "using the Unsafe, JVMTI, and
Serviceability Agent (SA) heavily to decoder the actual object layout,
footprint, and references.  This makes JOL much more accurate than
other tools relying on heap dumps, specification assumptions, etc."
Some of the calls it is making lead to this.

```
# WARNING: Unable to get Instrumentation. Dynamic Attach failed. You may add this JAR as -javaagent manually, or supply -Djdk.attach.allowAttachSelf
# WARNING: Unable to attach Serviceability Agent. You can try again with escalated privileges. Two options: a) use -Djol.tryWithSudo=true to try with sudo; b) echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
WARNING: An illegal reflective access operation has occurred
WARNING: Illegal reflective access by org.openjdk.jol.util.ObjectUtils (file:/home/jafinger/.m2/repository/org/openjdk/jol/jol-core/0.9/jol-core-0.9.jar) to field java.lang.String.value
WARNING: Please consider reporting this to the maintainers of org.openjdk.jol.util.ObjectUtils
WARNING: Use --illegal-access=warn to enable warnings of further illegal reflective access operations
WARNING: All illegal access operations will be denied in a future release
```

The first 2 lines can be eliminated by using these options when
starting your JVM:

```bash
-Djdk.attach.allowAttachSelf -Djol.tryWithSudo=true
```

For example, when using the `clj` or `clojure` commands:

```bash
$ clj -J-Djdk.attach.allowAttachSelf -J-Djol.tryWithSudo=true
```

The lines starting with "WARNING: An illegal reflective access
operation has occurred" are due to using a JDK version 9 or later.
See [here](https://clojure.org/guides/faq#illegal_access) for more
details.

Tested with:

Ubuntu 18.04.2, OpenJDK 11, Clojure 1.10.1

# Other possible tools

Code for determining the size of an object in a JVM, including all
other objects it references, recursively.

Many useful answers found on this StackOverflow question:

http://stackoverflow.com/questions/52353/in-java-what-is-the-best-way-to-determine-the-size-of-an-object

There is a link to a Memory Measurer tool that used to be on Google
code, but as of Aug 2015 I could only find it here on Github:

    https://github.com/msteindorfer/memory-measurer

Another project: http://sourceforge.net/projects/sizeof/
