# Pretty printing of large values

I have tried several Clojure pretty-printing libraries, including
clojure.pprint built into Clojure, but I could not find one that takes
a long vector that does not fit into the width given, yet tries to
take advantage of the width given for showing vector elements.  They
all seem to switch from fitting everything on one line, to showing one
vector element per line.

deps.edn coordinates tried:
```
mvxcvi/puget {:mvn/version "1.1.2"}
zprint {:mvn/version "0.4.16"}
fipp {:mvn/version "0.6.18"}
```

The function cljol.dig9/javaobj->str as of this writing has a default
behavior of using `clojure.core/str` to convert Java object values to
strings, then truncating them to at most 50 characters, adding " ..."
at the end if a string is truncated.  The value of 50 can be
customized using the key `:max-value-len` in opts, with a value equal
to the desired maximum.

One can customize all of the parts of a string used to label nodes in
the drawn graphs, using the `:node-label-functions` key in opts.  Its
associated value is a list of functions to call on the "objmap", a map
containing data about the Java object the node represents.  See
`cljol.dig9/all-builtin-node-labels` for the names of all such
functions built into cljol.

See a section "Customizing Clojure's pprint function" written by Alex
Miller on one way to customize Clojure's pprint behavior.


# Other versions of JOL library

Other versions of jol-core library that are available on Maven
Central.  When first writing this code in 2015, version 0.3.2 was the
latest version available, and what the code in the namespace cljol.dig
was written to use.

In 2019 I copied that to the cljol.dig9 namespace and developed the
changes necessary to make it work with jol-core version 0.9.

https://search.maven.org/search?q=g:org.openjdk.jol%20AND%20a:jol-core&core=gav

```
org.openjdk.jol/jol-core
0.2   2014-Nov-06
0.3   2015-Jan-15
0.3.2 2015-May-15
0.4   2015-Dec-02
0.5   2016-Apr-14
0.6   2016-Sep-21
0.7   2017-Jan-18
0.7.1 2017-Jan-18
0.8   2017-Mar-20
0.9   2017-Sep-22
```

I ran a very quick test with cljol.dig and jol-core version 0.4, and
it seemed to work fine.

I ran a very quick test with cljol.dig and jol-core version 0.5, and
that is the first release which no longer includes the class
org.openjdk.jol.util.VMSupport.


Other people who have written about using jol-core in various ways:

http://igstan.ro/posts/2014-09-23-calculating-an-object-graphs-size-on-the-jvm.html
http://www.mastertheboss.com/jboss-server/jboss-monitoring/monitoring-the-size-of-your-java-objects-with-java-object-layout


# Other possible tools for examining Java objects in a running JVM

Code for determining the size of an object in a JVM, including all
other objects it references, recursively.

Many useful answers found on this StackOverflow question:

http://stackoverflow.com/questions/52353/in-java-what-is-the-best-way-to-determine-the-size-of-an-object

There is a link to a Memory Measurer tool that used to be on Google
code, but as of Aug 2015 I could only find it here on Github:

    https://github.com/msteindorfer/memory-measurer

Another project: http://sourceforge.net/projects/sizeof/


# Development thoughts on graph structures and summary statistics

In particular, a summary statistic I will call total-size, which is
"the total number of bytes of memory occupied by this object, plus all
objects reachable from it through following a path of one or more
references, is N bytes".

For a tree structure of references, total-size is easy to calculate
for all nodes in a single linear time walk of the tree.

What about for a directed acyclic graph?  If an object A references
both B and C, it is not in general correct to calculate the total-size
of A by adding the total-size of B plus the total-size of C plus the
size of A, because there might be an arbitrary overlap in the set of
objects reachable starting at B, and the set of objects reachable
starting at C, i.e. all such objects might be in common, only some, or
none.

It does seem like it would be correct to do a topological sort of the
DAG nodes, then iterate through them in the reverse of that
topological order.  When a node is visited in this order, determine
the _set_ of all objects reachable from that node, which should be
itself plus the union of all objects reachable from each of the nodes
it directly references.  The total-size is then the sum of sizes of
all objects in that set.  The function cljol.graph/dag-reachable-nodes
implements this.

Topological sort of a DAG can be done in linear time (O(N+M) where M
is number of edges), and each node would only be visited one time in
that iteration, but the union operations are not constant time.  In
general they are about linear (i.e. O(N log N) with Clojure's set
implementations) in the size of the sets involved, so it seems like
the entire operation could be about O(N*(N+M)) in the worst case.

TBD: It might be that the worst case of doing this somewhat more
complex algorithm is the same as doing N linear-time depth-first
searches, one starting at each node, and collecting the results?

I cannot think of a way right now to calculate total-size for all
nodes faster than this, in the worst case.  It seems like any
computational compexity results for the transitive closure problem
apply here, because I believe that _is_ the problem being solved.

For a directed graph with cycles, the set of all strongly connected
components can be found in linear time.  Every node within one
strongly connected component can reach all others in the component.
Then the graph can be viewed as a directed acyclic graph _of_
components.

ubergraph imports and provides to users the `ubergraph.alg/scc`
function for calculating strongly connected components.

From those components, we can create another graph that I will call
the scc-graph (for strongly connected component graph).  Each node of
the scc-graph corresponds to a set of nodes from the original graph,
all that are in the same scc.  There is an edge from node A to node B
in the scc-graph if, in the original graph, any node in set A has an
edge to any node in set B.  See the function cljol.graph/scc-graph for
an implementation.

The scc-graph is a DAG, and then we can use the method described above
on that DAG.  The function cljol.graph/reachable-nodes implements this
combination of calculating the scc-graph, then calcalating
reachable-nodes on the resulting DAG, then transforming the results
back into results for the original graph.


# Customizing Clojure's pprint function

From a discussion on the Clojurians Slack channel 2019-Jul-05:

I'm trying to generate some specs and write them to a file.  When I
pretty print them, they look like so:

```
(s/def
 ::addresses
 (s/keys
  :req-un
  [:addresses/addressable-id
   :addresses/addressable-type
   :addresses/city
```

Is there any way I can convince the pretty-printer to get the
`::addresses` to come out on the same line as the `s/def`?

alexmiller: Yes, there are some tricks if you use the "code" modes of
the pretty printer.  Let me find you a link to an example.

This is dark arts :)

```
user=> (require '[clojure.spec.alpha :as s] '[clojure.pprint :as pp])
nil
(def c `(s/def
 :user/addresses
 (s/keys
  :req-un
  [:addresses/addressable-id
   :addresses/addressable-type
   :addresses/city])))
#'user/c

user=> (binding [pp/*code-table* (assoc @#'pp/*code-table* 'clojure.spec.alpha/def #'pp/pprint-hold-first)] (pp/with-pprint-dispatch pp/code-dispatch (pp/pprint c)))
(clojure.spec.alpha/def :user/addresses
 (clojure.spec.alpha/keys
   :req-un
   [:addresses/addressable-id
    :addresses/addressable-type
    :addresses/city]))
```

Note that here c is your code (as data) -- it's important that you
fully namespace the symbols (I'm using back tick to get that).

Then you want to pprint, with specialized dispatch, using the
code-dispatch which already knows how to handle most clojure code, and
extend the code-table to understand how to handle s/def.

You can look at the pprint code to see more about the default code
table and how things like pprint-hold-first are defined (which is,
again, another dark arts subset of this dark art) :)

mpenet: cljfmt can do that too, I think.

alexmiller: Undoubtedly, but this is "in the box" with both clj and
cljs, and this is an extensible system so you can do other customized
things with your own macros or whatever.  Like, you could tweak this
to put the req keys next to the :req-un if you were determined enough
(see pprint-let for something probably similar).


# What goes wrong when I try to get consistent-reachable-objmaps for a Var?

Several things were going wrong with the `cljol` code as it was when I
first tried to create a graph for a Clojure var, e.g. `(def o1 2)`
followed by `(view [#'o1])`.

One, I was having a hard time getting a set of objects returned that
would not find errors when checked using
`cljol.dig9/object-graph-errors`.  I do not know if I did something
that got past that issue in particular, although I did start using the
alias `:priv` in `deps.edn`, which adds these JVM command line options
suggested by some warning messages that appear when using JOL without
them:

```
-Djdk.attach.allowAttachSelf -Djol.tryWithSudo=true
```

Even with those options, at least with Java 11 that I was testing
with, there are objects reachable from a Clojure var that have fields
that cause an exception to be thrown when you try to call the
`setAccessible` method on them.  I added some handling of this
exception within `cljol` code so that it should keep going if that
happens, remembering the field value as a special "inaccessible"
object created by `cljol`.

I also added some debug options that enable printing elapsed execution
times for several of the steps within functions
`consistent-reachable-objects` and `graph-of-reachable-objects`, which
you can enable using the example `opts` map below:

```
(def opts {:node-label-functions [d/size-bytes
                                  d/total-size-bytes
                                  d/class-description
                                  d/field-values]
           :consistent-reachable-objects-debuglevel 1
           :graph-of-reachable-objects-debuglevel 1
           :calculate-total-size-node-attribute false})
```

When using those options on successively larger Clojure vectors, they
showed that `add-total-size-bytes-node-attr` was one of the longest
steps within `graph-of-reachable-objects` as the number of nodes and
edges increased.  That doesn't surprise me, as it is the only step
that isn't linear time.  I added the
`:calculate-total-size-node-attribute` option shown above that
defaults to true, but can be given as false to skip that step.

With all of those changes, I can now often create a graph of objects
from a Clojure var.  It often takes 2 or 3 tries at calling
`reachable-objmaps` inside of `consistent-reachable-objmaps` before it
gets a set of objects that have no errors in them.

Strangely, `sum` shows that there are typically many weakly connected
components, which seems to indicate that some changes in inter-object
references are occurring somewhere during the computation of the
graph.

I believe that at least some of the reason, and perhaps most or all of
it, is that JVM objects with class java.lang.Class have several
references in them that point at data that is only allocated and
initialized when certain kinds of calls are made to determine
information about the class, e.g. when the fields of the class are
examined use Java reflection APIs, which is exactly what parts of JOL
and cljol do in order to determine the fields of an object.  Thus if
objects of type java.lang.Class are included in the graph of objects
walked, some of those references can change due to JOL and/or cljol
code between the time the java.lang.Class was walked by JOL, and the
time the Ubergraph is constructed and had attributes added by cljol.

This effect seems a little bit more common on the first attempt to
create an object graph for a Var after starting the JVM, then 'the
cache gets warm' for such data, and those objects become a bit more
stable in memory.

I originally thought that it might be due to SoftReference objects in
the graph walked, and that may have been part of it, but it cannot be
the entire cause, because of later tests where I changed a local copy
of JOL to never walk references out of SoftReference objects, so they
would never be included in the graph returned by JOL.  There are still
sometimes weakly connected components in the graph returned by d/sum.
java.lang.Class on-demand data creation seems to be the answer, as
described above.

One thing to note is that when doing a walk of all objects reachable
from a var like `#'user/v1` when running in a REPL from the `user`
namespace, is that it reaches the `user` namespace, and from there all
vars you have `def`'ed at the REPL in the `user` namespace.  Also
namespace objects have chains of references that reach to all other
namespaces that contain vars referenced from that namespace, or for
which an alias is created, e.g. via `alias` or `(:require
[name.space.name :as my-alias])` inside of an `ns` form.  So, from a
single Var you can reach in the Java object graph often many
namespaces, and many Vars, usually including all of hundreds of Vars
`clojure.core`.

```
(require '[cljol.dig9 :as d]
	 '[ubergraph.core :as uber])

(def opts {:node-label-functions
           [#'d/address-decimal
            #'d/size-bytes
            #'d/total-size-bytes
            #'d/class-description
            #'d/field-values
            #'d/non-realizing-javaobj->str]
           })

(def v1 (vector 2))
;; The following step took about 1 minute on my 2015 MacBook Pro, with
;; the JVM `java` process going up to almost 2 GBytes.
(def g (d/sum [#'v1] opts))

(def nss (->> (uber/nodes g)
              (map #(uber/attr g % :obj))
              (filter #(instance? clojure.lang.Namespace %))))
(count nss)
;; => 40
(pprint (sort-by str nss))

(def vars (->> (uber/nodes g)
               (map #(uber/attr g % :obj))
               (filter #(instance? clojure.lang.Var %))))
(count vars)
;; => 2050
(pprint (sort-by symbol vars))
;; => note that the output includes #'user/g
```

Because the object graph references `#'user/g`, which references the
entire Ubergraph data structure, if you right now in the REPL do:

```
(def g (d/sum [#'v1] opts))
```

the object graph will be _much_ bigger, because it includes all of the
objects in that Ubergraph.  I have seen over 1 million in some
attempts, which I never left longer than a few minutes before aborting
those attempts.  That scale of number of objects probably takes many
GBytes of memory and I do not know how much processing time to
complete.  Also I am not sure I would be very interested in looking
through the results, anyway.

You can avoid that issue by first assigning all big data structures
the value of `nil` that you do not want to walk.

```
(def g nil)
;; now no more big Ubergraph reachable from g

(def g (d/sum [#'v1] opts))
```


# Information about different types of references in the JVM

Modern JVMs have 4 types of references: strong, soft, weak, and
phantom.

In a Clojure application, unless you go out of your way to create a
specific kind of reference, all of them will be strong references.  If
you use Java libraries, they may internally create other kinds of
references.  Clojure itself contains a few uses of `SoftReference` in
the `clojure.lang.DynamicClassLoader` and `clojure.lang.Keyword`
classes, and one use of a `WeakReference` in `clojure.lang.Keyword`.

For `cljol`, it seems like it would be handy if JOL had an option to
the `GraphLayout/parseInstance` method (or another method that behaved
slightly differently), that did not follow any references out of an
object `x` such that `(instance? java.lang.ref.Reference x)` was true.
I believe that all Java non-strong references are contained only
within such objects.

Some articles that attempt to describe the differences between them.

* [Weak, Soft, and Phantom references: Impact on GC](https://plumbr.io/blog/garbage-collection/weak-soft-and-phantom-references-impact-on-gc)
* [Types of References in Java](https://www.geeksforgeeks.org/types-references-java)
* [What's the difference between SoftReference and WeakReference in Java?](https://stackoverflow.com/questions/299659/whats-the-difference-between-softreference-and-weakreference-in-java)
* [Weak, Soft, and Phantom References in Java (and Why They Matter)](https://dzone.com/articles/weak-soft-and-phantom-references-in-java-and-why-they-matter)
* [Understanding JVM soft references for great good (and building a cache)](https://blog.shiftleft.io/understanding-jvm-soft-references-for-great-good-and-building-a-cache-244a4f7bb85d)


java.lang.ref.SoftReference objects can contain references to
java.lang.ref.ReferenceQueue objects.  According to JDK documentation
I have read, and mentions of the ReferenceQueue class in stats
produced by `d/sum`, ReferenceQueue objects can contain lists of
objects pending garbage collection, including objects with little or
no relation to the objects you started creating a graph for, other
than happening to become eligible for GC near the same time.

By using JOL to walk such an object graph, it creates a strong
reference to them that prevents those objects from being freed, until
those strong references are removed.
