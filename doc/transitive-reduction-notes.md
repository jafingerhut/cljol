# Notes on transitive reductions, and one algorithm to calculate them

This article belongs better in some place more directly related to
graph algorithms, perhaps the Ubergraph repository.  I am creating it
here for now, just to have a place to put it.


# Definitions


## Graph terminology

Wikipedia descriptions of:

+ [Directed graph](https://en.wikipedia.org/wiki/Directed_graph)
+ [path](https://en.wikipedia.org/wiki/Path_(graph_theory)) in a graph
+ [cycle](https://en.wikipedia.org/wiki/Cycle_graph#Directed_cycle_graph)
  in a graph
+ [Directed acyclic
  graph](https://en.wikipedia.org/wiki/Directed_acyclic_graph)


## Reachability

There are many problems where directed graphs are used to represent
whether one thing can be reached from another, e.g.

+ class diagrams in Java and other object-oriented languages where an
  edge `(u,v)` represents that class `u` extends class `v`,
+ control flow in a computer program
+ street corners in a city with many one-way-streets

In such a situation, if we wish to answer queries of the form "is it
possible to reach `v` from `u` via a path of one or more edges", we
can do so by performing a search in the graph starting at `u` for node
`v`, e.g. via breadth-first search, depth-first search, shortest path
calculations, etc.

Two kinds of optimizations present themselves in this situation:

+ We want to create a different graph that has the same reachability,
  but lets us answer "can `v` be reached from `u`?" questions as
  quickly as possible, without searching.  In this case creating a
  second graph that has an edge `(u,v)` whenever the original graph
  has a path from `u` to `v` is helpful.  This second graph is called
  the _transitive closure_ of the original.
+ We want to create a different graph that still requires searching,
  but in some sense is as small as we can make it, yet still preserves
  the property that if there is a path from `u` to `v` in the original
  graph, then the second graph has one, too.  There are several
  variations of this idea described below called _irreducible kernel_,
  _minimum equivalent graph_, and _transitive reduction_.

Given a directed graph `G=(V,E)`, we say that the graph `H=(V,F)`
_preserves reachability with `G`_ if, for every pair of vertices `u`, `v`
in `V`, there is a directed path from `u` to `v` in `H` if and only if
there is a directed path from `u` to `v` in `G`.


## Transitive closure

The _transitive closure_ of a directed graph `G=(V,E)` is the unique
graph `tc(G)=(V,E*)` with the same set of vertices as `G`, and an edge
`(u,v)` in `E*` if and only if there is a directed path from `u` to `v
`in `G`.

A graph `H` preserves reachability with `G` if and only if
`tc(H)=tc(G)`.


## Irreducible kernel

An _irreducible kernel_ of a directed graph `G=(V,E)` is any graph
`H=(V,F)` where all of the following are true:

+ It has the same set of vertices as `G`.
+ Its edges `F` are a subset of edges `E`.
+ `H` preserves reachability with `G`.
+ Removing any edge in `F` will result in a graph that does not
  preserve reachability with `G`.  That is, `H`'s edges `F` are a
  minimal subset of `E` that preserves reachability with `G`.

Note: `F` might be, but need not be, a minimum size subset of `E` with
these properties.  There might be multiple irreducible kernels of `G`
with different number of edges from each other.  See section ["Graphs
with cycles"](#graphs-with-cycles) for an example.


## Minimum equivalent graph

A _minimum equivalent graph_ of a directed graph `G=(V,E)` is an
irreducible kernel of `G` that has the minimum number of edges among
all irreducible kernels of `G`.


## Transitive reduction

A _transitive reduction_ of a directed graph `G=(V,E)` is any graph
`H=(V,F)` where all of the following are true:

+ It has the same set of vertices as `G`.
+ Its edges `F` are allowed to include edges that are not in `E`.
+ `H` preserves reachability with `G`.
+ Among all graphs satisfying these properties, `H` has a minimum
  number of edges possible.


## Self loops

This is a fairly minor detail, but seems worth mentioning explicitly
somewhere.  A self loop is an edge from a vertex to itself, e.g. an
edge `(u,u)` in a graph with a vertex `u`.

According the definitions above, an irreducible kernel, a minimum
equivalent graph, and a transitive reduction of a graph will never
contain any self loop edges.  This is because we consider every node
`u` to be reachable from itself without needing a self loop in the
graph, nor any edges at all.  Thus a minimal or minimum size graph
that preserves reachability does not need them, even if the given
directed graph G has one or more self loops.

It seems reasonable to me that one can consider the transitive closure
of a graph to either:

+ contain self loops for every vertex in the graph
+ contain no self loops

It is not clear to me yet whether there are applications where it is
important to choose one of those alternatives over the other, although
there certainly might be proofs involving transitive closures that
assume one of them to be the case.  In any case, it seems like at most
a minor detail for those being very precise in checking the wording of
some proofs.


## Multigraphs, or parallel edges

For most of this, I will consider a directed graph not to contain any
parallel edges, i.e. for any pair of vertices `u` and `v` in a graph,
either there is exactly one edge `(u,v)`, or there is no such edge.

This is an important restriction for some of the discussion below
about a directed acyclic graph having a unique transitive reduction,
for example.  If an input graph can have parallel edges, and one
considers these parallel edges to be different from each other, then
those statements about some graphs being unique need to be amended,
but only slightly.


# Directed acyclic graphs (DAG)

Aho, Garey, and Ullman's 1972 paper [2] is quite short, and is packed
with a some very useful proofs about transitive reductions and
transitive closures.

In particular, Theorem 1, Lemma 1, Lemma 2, and their proofs, show
that the following statements in Section 2 of the paper are true
("arc" is a synonym for "edge"):

    Theorem 1 shows that the intuitive definition of transitive
    reduction actually yields a unique graph for any finite acyclic
    directed graph.  Furthermore, the transitive reduction of any
    such graph G can be obtained by successively examining the arcs
    of G, in any order, and deleting those arcs which
    are "redundant," where an arc \alpha = (u, v) is redundant if
    the graph contains a directed path from u to v which does not
    include \alpha.

These proofs show that for any DAG `G`, there is a graph `H` that is
all of these things:

+ `H` is the unique irreducible kernel of DAG `G`
+ `H` is the unique minimum equivalent graph of DAG `G`
+ `H` is the unique transitive reduction of DAG `G`

So while those three things can be different for a graph `G` with
cycles (see below for examples), they are all the same for a DAG.


## Computing the transitive reduction of a DAG

TBD: Give description and proof of algorithm to find the transitive
reduction of a DAG in `O(N*e)` time, where `e` is the number of edges
in the transitive reduction (which is guaranteed to be at most the
number of edges in the input graph), and for some input graphs can be
much smaller.


# Graphs with cycles

The concepts of irreducible kernel, minimum equivalent graph, and
transitive reduction can be different from each other for graphs with
cycles.

Abbreviations:

+ IK - irreducible kernel
+ MEG - minimum equivalent graph
+ TR - transitive reduction

```
        +-----+                   +-----+                   +-----+
      --|  1  |--               --|  1  |                 --|  1  |--
     /  +-----+  \             /  +-----+                /  +-----+  \
    /    ^   ^    \           /        ^                /    ^   ^    \
    |    |   |    |           |        |                |    |   |    |
    |   /     \   |           |         \               |   /     \   |
    V  /       \  V           V          \              V  /       \  V
+-----+         +-----+   +-----+         +-----+   +-----+         +-----+
|  2  |-------->|  3  |   |  2  |-------->|  3  |   |  2  |         |  3  |
+-----+         +-----+   +-----+         +-----+   +-----+         +-----+

        Graph G1                IK #1 of G1              IK #2 of G1
                              Also a MEG and             Neither a MEG
                                a TR of G1               nor a TR of G1
```

Graph `G1` has at least the two different irreducible kernels shown,
where one has 4 edges and the other has 3.  The one with 3 edges is
also a minimum equivalent graph and a transitive reduction of `G1`,
but the one with 4 edges is neither, because it has more than the
minimum possible number of edges.

```
        +-----+                   +-----+                   +-----+
      --|  1  |--               --|  1  |                   |  1  |--
     /  +-----+  \             /  +-----+                   +-----+  \
    /    ^   ^    \           /        ^                     ^        \
    |    |   |    |           |        |                     |        |
    |   /     \   |           |         \                   /         |
    V  /       \  V           V          \                 /          V
+-----+         +-----+   +-----+         +-----+   +-----+         +-----+
|  2  |         |  3  |   |  2  |-------->|  3  |   |  2  |<--------|  3  |
+-----+         +-----+   +-----+         +-----+   +-----+         +-----+

        Graph G2               TR #1 of G1                TR #2 of G1
     Also the only
     IK and MEG of G2
```

Graph `G2` has only one irreducible kernel and one minimum equivalent
graph.  They include all of the edges of `G2`, because removing any of
them would not preserve reachability.

The two transitive reductions shown in the figure have only 3 edges,
strictly less than the 4 edges in the irreducible kernel or minimum
equivalent graph.  The transitive reduction has the freedom to
introduce edges that are not in the original graph, and can thus have
fewer edges than the minimum equivalent graph.

We can extend graph `G2` to a graph `G(N)` that has nodes 4, 5, 6,
etc. up to `N`, with edges `(1,i)` and `(i,1)` for every node `i > 1`.
`G(N)` has only itself as its irreducible kernel and minimum
equivalent graph, with `2*N-2` edges.  Any cycle that includes all `N`
vertices once each, in any order, is a transitive reduction of `G(N)`.
They all have `N` edges.  This demonstrates that the minimum
equivalent graph and transitive reduction can have almost twice as
many edges as a transitive reduction.  I do not know if there are
graphs where the irreducible kernel or minimum equivalent graph have
more than twice as many edges as a transitive reduction.

So there are graphs with cycles that have more than one irreducible
kernel, more than one minimum equivalent graph, and more than one
transitive reduction.

Some graphs have irreducible kernels that have more edges than the
minimum equivalent graph.  Some graphs with cycles have both the
irreducible kernels and minimum equivalent graphs with more edges than
a transitive reduction.  Some cyclic graphs have transitive reductions
containing edges not in the original graph.


## Computing the transitive reduction of a graph with cycles

TBD: Describe how to take any algorithm for finding the transitive
reduction for a DAG, and extend it to find the transitive reduction
for an arbitrary directed graph.  Originally described by Aho, Garey,
and Ullman [2].  They call the acyclic graph constructed from an
arbitrary directed graph the "equivalent acyclic graph".  The
[Wikipedia article on strongly connected
components](https://en.wikipedia.org/wiki/Strongly_connected_component)
calls this a "condensation" of directed graph G.


## Computing the minimum equivalent graph of a graph with cycles

The restriction that a minimum equivalent graph must only have edges
that appear in the original graph not only means that they can have
more edges than a transitive reduction, it turns out that it makes the
problem computationally more difficult.

Finding a minimum equivalent graph is an NP complete problem, as
proved by Sartaj Sahni [3].  Khuller, Raghavachari, and Young prove
that there is a polynomial time algorithm that can guarantee finding a
solution that contains at most about 1.64 times more edges than the
minimum possible, and an algorithm that takes only slightly longer
than linear time that guarantees finding a solution with at most 1.75
times more edges than the minimum possible.


# References

[1] https://en.wikipedia.org/wiki/Transitive_reduction

[2] A. V. Aho, M. R. Garey, and J. D. Ullman, "The transitive
    reduction of a directed graph", SIAM J. Computing, Vol. 1, No. 2,
    June 1972, https://doi.org/10.1137/0201008

[3] Sartaj Sahni, "Computationally related problems", SIAM
    J. Computing, Vol. 3, No. 4, December 1974,
    https://doi.org/10.1137/0203021
    https://www.cise.ufl.edu/~sahni/papers/computationallyRelatedProblems.pdf

[4] S, Khuller, B. Raghavachari, and Neal Young, "Approximating the
    minimum equivalent graph digraph", SIAM J. Computing, Vol. 24,
    No. 4, 1995, also 2002 version on arXiv.org:
    https://arxiv.org/abs/cs/0205040
