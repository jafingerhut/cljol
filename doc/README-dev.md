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
`cljol.dig9/object-graph-errors.  I do not know if I did something
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
graph.  I don't know why this happens, but it might have something to
do with references that are not strong, but either weak, soft, or
phantom.


```
(do
(require '[cljol.dig9 :as d]
         '[cljol.graph :as gr]
	 '[ubergraph.core :as uber])
(def opts
  {:node-label-functions
   [d/size-bytes
    d/total-size-bytes
    d/class-description
    d/field-values
    d/non-realizing-javaobj->str]
   :consistent-reachable-objects-debuglevel 1
   :graph-of-reachable-objects-debuglevel 1
   :calculate-total-size-node-attribute false
;;   :calculate-total-size-node-attribute true
   :slow-instance-size-checking? true
   })
(def v1 (vector 2))
)
(def v1 (class 5))
(def v1 (vec (range 20)))
(System/gc)
(def g (d/sum [v1] opts))
(def g (d/sum [#'v1] opts))
(d/view-graph g)
(def g nil)

(def g3 (gr/induced-subgraph g (filter #(<= (uber/attr g % :distance) 4)
                                        (uber/nodes g))))
(d/view-graph g3)

(uber/pprint g)
(defn inconsistent-distance-nodes [g]
  (for [node (uber/nodes g)
        :let [attrs (uber/attrs g node)
	      sp-dist (:distance attrs)
	      gpl-dist (:gpl-distance attrs)]]
    {:node node :sp-dist sp-dist :gpl-dist gpl-dist}))
(def i1 (inconsistent-distance-nodes g))
(count i1)
(uber/count-nodes g)
(def i2 (group-by (fn [x] (= (:sp-dist x) (:gpl-dist x))) i1))
(count (i2 true))
(count (i2 false))

(def g (d/sum [(vec (range 1e5))] opts))
(def o1 (d/consistent-reachable-objmaps [#'v1] opts))

(def g (d/sum [#'v1] opts))
(d/view-graph g {:save {:filename "clojure-var.dot" :format :dot}})
(type g)
(doseq [dist [3 4 5 6 7 8]]
  (let [g2 (gr/induced-subgraph g (filter #(let [d (uber/attr g % :distance)]
                                             (and (number? d) (<= d dist)))
                                          (uber/nodes g)))
        fname (str "clojure-var-dist-" dist ".dot")]
    (d/view-graph g2 {:save {:filename fname :format :dot}})))

(type o1)
(def e1 *e)
(pprint (Throwable->map e1))
(def r1 (clojure.repl/root-cause e1))
(def e2 (-> (ex-data r1) :errors))
(-> e2 :err)
(keys e2)
(keys (-> e2 :data))
(pprint (:fields (-> e2 :data)))
d/inaccessible-field-val-sentinel
(identical? d/inaccessible-field-val-sentinel (get (-> e2 :data :fields) "handler"))
(def e3 (-> e2 :err-data))

;; Trying the following starts printing a large amount of Clojure data
;; as the ex-data of the exception, I believe.  Don't do that.  For
;; all I know, it might even experience an infinite loop in its
;; attempt to print cyclic structures.
(pst e1 100)

(type e1)
(type (ex-data e1))
;; This is small data
(keys (ex-data e1))

;; Maybe big data is in root-casue of exception?
(def r1 (clojure.repl/root-cause e1))

(type r1)
(type (ex-data r1))
(keys (ex-data r1))

(-> (ex-data r1) :errors type)
;; => clojure.lang.PersistentArrayMap
(-> (ex-data r1) :obj-coll type)
;; => clojure.lang.PersistentVector

(def e2 (-> (ex-data r1) :errors))
(-> e2 :err)
;; => :object-moved
(-> e2 :err-data type)
(-> e2 :err-data keys)
;; => (:address :obj :size :path :fields :cur-address)
(def e3 (-> e2 :err-data))

(:address e3)
;; => 27006116544
(d/address-of (:obj e3))
;; => 26168262656
(-> e3 :obj type)
;; => java.lang.ref.SoftReference

;; Try to isolate some cases where JOL 0.9 seems to return incorrect
;; object sizes.

(do
(import '(org.openjdk.jol.info ClassLayout GraphLayout))
(import '(org.openjdk.jol.vm VM))
(defn foo [obj]
  (let [cls (class obj)
	parsed-inst (ClassLayout/parseInstance obj)
        parsed-cls (ClassLayout/parseClass cls)
	vm-size (. (VM/current) sizeOf obj)
        inst-size (. parsed-inst instanceSize)
        cl-size (. parsed-cls instanceSize)]
    (println "toPrintable of parseInstance ret value:")
    (print (.toPrintable parsed-inst))
    (println)
    (println "toPrintable of parseClass ret value:")
    (print (.toPrintable parsed-cls))
    (println)
    (println "cls:" cls)
    (println vm-size "(. (VM/current) sizeOf obj)")
    (println inst-size "(. (ClassLayout/parseInstance obj) instanceSize)")
    (println cl-size "(. (ClassLayout/parseClass cls) instanceSize)")
    (if (= vm-size cl-size)
      (println "same")
      (println "DIFFERENT"))))
)
(foo 5)
(foo "bar")
(foo (class 5))
(foo (object-array 0))
(foo (object-array 1))
(foo (object-array 5))
(foo (object-array 6))
(foo (object-array 7))
(foo (object-array 8))
(foo (object-array 9))
(foo (object-array 50))

(foo (char-array 0))
(foo (char-array 1))
(foo (char-array 50))

```

The Clojure implementation mentions SoftReference in its
clojure.lang.DynamicClassLoader class, and clojure.lang.Keyword.

This makes me think that perhaps SoftReference objects might move
around frequently enough in memory that my current approach of
calculating an address for every object on one pass, then on a later
pass checking whether they are still at the same address, is too
fragile for this kind of object graph?

Perhaps I should instead consider using a Java IdentityHashMap object
to create and store a map from object identities to UUIDs, and then
use those UUIDs as the values in the ubergraph values that cljol
creates, instead of addresses.



# Information about different types of references in the JVM

Modern JVMs have 4 types of references: strong, soft, weak, and
phantom.

In a Clojure application, unless you go out of your way to create a
specific kind of reference, all of them will be strong references.  If
you use Java libraries, they may internally create other kinds of
references.  Clojure itself contains a few uses of `SoftReference` in
the `clojure.lang.DynamicClassLoader` and `clojure.lang.Keyword`
classes, and one use of a `WeakReference` in `clojure.lang.Keyword`.

Some articles that attempt to describe the differences between them.

* [Weak, Soft, and Phantom references: Impact on GC](https://plumbr.io/blog/garbage-collection/weak-soft-and-phantom-references-impact-on-gc)
* [Types of References in Java](https://www.geeksforgeeks.org/types-references-java)
* [What's the difference between SoftReference and WeakReference in Java?](https://stackoverflow.com/questions/299659/whats-the-difference-between-softreference-and-weakreference-in-java)
* [Weak, Soft, and Phantom References in Java (and Why They Matter)](https://dzone.com/articles/weak-soft-and-phantom-references-in-java-and-why-they-matter)
* [Understanding JVM soft references for great good (and building a cache)](https://blog.shiftleft.io/understanding-jvm-soft-references-for-great-good-and-building-a-cache-244a4f7bb85d)
