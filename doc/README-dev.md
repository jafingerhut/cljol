# Compressed strings in Java 9 and later

In Java 8 and earlier, the default was for strings to be represented
in memory as arrays of Java `char`, which are 2 bytes each.  If most
of the strings you represent are within the ASCII subset, then this is
twice as much memory as they need, but it enables strings to be
handled consistently throughout the Java library whether they use a
larger subset of the full Unicode character set, or not.

Java 9 introduced [Compressed
strings](https://www.codenuclear.com/compact-strings-java-9), where if
a string contains only characters whose code points fit within a
single 8-bit byte, then it is stored in memory using only 1 byte per
character.

`cljol` can make this easy to see, by using it to analyze a string
like `"food"` containing only characters within the ASCII subset, and
a very similar string like `"fo\u1234d"` that contains a character
that requires more than 8 bits to represent.


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
all objects in that set.

Topological sort of a DAG can be done in linear time, and each node
would only be visited one time in that iteration, but the union
operations are not constant time.  In general they are about linear
(i.e. O(N log N) with Clojure's set implementations) in the size of
the sets involved, so it seems like the entire operation could be
about O(N^2) in the worst case.

I cannot think of a way right now to calculate total-size for all
nodes faster than this.

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
edge to any node in set B.

The scc-graph is a DAG, and then we can use the method described above
on that DAG.
