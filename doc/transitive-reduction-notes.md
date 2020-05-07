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

Definition: Given a graph `G=(V,E)` and `V'` a subset of the vertices
`V`, the _induced subgraph_ of `G` on `V'`, denoted `G[V']`, is
`G[V']=(V',E')`, where `E'` contains all edges of `E` that are between
two vertices in `V'`.

Definition: Suppose `T` is an ordering of `n` values, where the
sequence of individual values is denoted `T[0], T[1], ... T[n-1]`.
Define `T[i,j]` where `0 <= i <= j < n` to be the set of values
`{T[i], ..., T[j]}`.

Definition: If we have two graphs `G1=(V1,E1)` and `G2=(V2,E2)`, let
their union, denoted `G1+G2`, be the graph that is the union `V1+V2`
of their vertex sets, and the union `E1+E2` of their edge sets.


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

For most of this article, I will consider a directed graph not to
contain any parallel edges, i.e. for any pair of vertices `u` and `v`
in a graph, either there is exactly one edge `(u,v)`, or there is no
such edge.

This is an important restriction for some of the discussion below
about a directed acyclic graph having a unique transitive reduction,
for example.  If an input graph can have parallel edges, then those
statements about some graphs being unique need to be amended, but only
slightly.  For example, this graph:

```
                   e3
    --------------------------------
   /                                \
   |                                |
   |                                V
+-----+   e1     +-----+   e2    +-----+
|  1  |--------->|  2  |-------->|  3  |
+-----+          +-----+         +-----+
```
has a unique minimum equivalent graph, containing only the edges `e1`
and `e2`.  The graph below is the same as the one above, except it has
two parallel edges from vertex 1 to 2, `e1` and `e4`.
```
                   e3
    --------------------------------
   /                                \
   |    -----                       |
   |   /  e1 \                      V
+-----+       -->+-----+   e2    +-----+
|  1  |          |  2  |-------->|  3  |
+-----+       -->+-----+         +-----+
       \  e4 /
        -----
```
The second graph has two different minimum equivalent graphs, one
containing only edges `e1` and `e2`, the other containing only edges
`e4` and `e2`.  In general, the only difference between such minimum
equivalent graphs will be which of several parallel edges are chosen.


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

As mentioned in the previous section, a DAG `G` always has the same
transitive reduction, minimum equivalent graph, and irreducible
kernel, and all of them are unique for any DAG.  So any algorithm for
computing one computes them all.

Also according to the proofs mentioned in the previous section,
Algorithm A is correct.
```
Input: DAG G=(V,E)

E2 = E
for edge (u,v) in E2 do
    if there is a path from u to v in G=(V,E2) that does not use edge (u,v) then
        E2 = E2 - {(u,v)}   // remove edge (u,v) from E2
    end if
end for

Output: G2=(V,E2) is the transitive reduction of G

Algorithm A
```

It is straightforward to implement the `if` condition "there is a path
from `u` to `v` ..." in linear time, i.e. `O(V+E2)` time -- where
inside of the "big O" I will use the name of a set to denote its size
-- using any linear time graph traversal algorithm like breadth-first
search or depth-first search.

Since that is repeated once for each edge, the total running time of
algorithm A is `O(E*(V+E))`.  We will give a faster algorithm below,
but Algorithm A does have the advantage of being very simple to
implement, and for small enough input graphs its run time may be
perfectly acceptable.  Algorithm A is also useful to compare its
output against the output of an implementation of a more complex
algorithm, for testing the more complex implementation.

Note that at all times during the execution of Algorithm A, `G=(V,E2)`
has the same reachability as `G=(V,E)`, so it is correct to check for
paths in either `E2` or `E`.  In general, searching for a path in a
smaller set of edges should be faster than in a larger set of edges.


### Step B in refining an algorithm to find the transitive reduction of a DAG

To achieve a faster run time, we will take advantage of the fact that
the input graph is a DAG.  For any DAG, we can calculate a
[topological
ordering](https://en.wikipedia.org/wiki/Topological_sorting) of the
vertices in linear time, i.e. `O(V+E)` time.

A topological ordering is a sequence `T` of the vertices `V` of a DAG
`G=(V,E)` such that for every edge `(u,v)` in `E`, `u` is before `v`
in `T`.  More visually, if you draw the graph `G` with the vertices
ordered left to right in topological order, all edges will be directed
from left to right -- none of them will be directed from right to
left.

One property we will take advantage of is:

+ Property (P1): When determining whether there is a path from `u` to
  `v` in some set of edges `E` (or any subset of `E`), the only
  vertices that can be in such a path must lie between `u` and `v` in
  the topological ordering `T`.

There can be no vertex before `u` in `T` in such a path, because that
would require an edge from a later vertex to an earlier one, and there
are none.  Similarly there can be no vertex after `v` in `T` in such a
path, because there is no way to get from "after `v`" to `v`.

First, let us refine algorithm A a little bit by specifying an order
that we will consider the edges.  Do not worry if it is not yet clear
why we are picking this order -- the primary reason is that there is
an efficient way to determine whether an edge is redundant when we
consider edges in this order, given in more detail later.

```
Input: DAG G=(V,E)

T = topological ordering of V in G
// Now T[0] is the first node of T, T[n-1] is the last

E2 = {}   // empty set of edges
for i in 1 up to n-1 do
    for each edge (T[j], T[i]) in E do
        if _not_ (there is a path from T[j] to T[i] in G=(V,E) that does not use edge (T[j], T[i])) then
            E2 = E2 + {(T[j], T[i])}   // add edge to E2
        end if
    end for

    // Invariant: At this time, G2=(T[0,i],E2) is the transitive
    // reduction of the graph G[T[0,i]].

end for

Output: G2=(V,E2) is the transitive reduction of G

Algorithm B
```

Algorithm B achieves the same result as Algorithm A in a slightly
different way.  It starts with an empty set of edges, and only adds
edges from E to the result if they should be in the output.

Note that while we would like to make checking for paths from `T[j]`
to `T[i]` faster by checking for them in the (often) smaller set of
edges `E2`, that would not work here.

Example:

Suppose there are two edges into `T[7]` in a graph, `(T[1], T[7])` and
`(T[6], T[7])`, and there is at least one path from `T[1]` to `T[7]`
in `E - (T[1], T[7])`, but they all go through edge `(T[6], T[7])`.
Because there is another path in `E` from `T[1]` to `T[7]`, edge
`(T[1], T[7])` should not be in the transitive reduction.

If the algorithm examines edge `(T[1], T[7])` first, it would not find
any path from `T[1]` to `T[7]` in edge set `E2` at that time, because
at that time there are no edges into `T[7]` yet.  Thus the algorithm
would add edge `(T[1], T[7])` to the edge set `E2` when it should not.

If we examined the edges in the opposite order, we would add edge
`(T[6], T[7])` to `E2`, and then when examining edge `(T[1], T[7])`,
find a path from `T[1]` to `T[7]` going through edge `(T[6], T[7])` in
`E2`, and not add edge `(T[1], T[7])` to `E2`.

The basic idea of the next refinement is to guarantee that we examine
the edges into `T[i]` in a particular order, such that we get the
correct answer, even though we only search for paths in the
(sometimes) smaller edge set `E2`.


### Step C in refining an algorithm to find the transitive reduction of a DAG

Algorithm C below enables us to do the checks for paths to `T[i]` in
`G=(V,E2)`, by considering the edges into `T[i]` in a particular
order.  The edges `(T[j], T[i])` are considered from largest `j` down
to smallest, i.e. reverse topological order.

Note that rather than the `if` condition `there is a path from T[j] to
T[i] in G=(V,E2) that does not use edge (T[j], T[i]))`, we can leave
off the `that does not use edge (T[j], T[i])` because we know that set
`E2` does not yet contain edge `(T[j], T[i])` (if there are no
parallel edges -- even if there are parallel edges in `E`, this is
correct because we want to keep only one of them).

```
Input: DAG G=(V,E)

T = topological ordering of V in G
// Now T[0] is the first node of T, T[n-1] is the last

E2 = {}   // empty set of edges
for i in 1 up to n-1 do
    for j in i-1 down to 0 do
        if there is an edge (T[j], T[i]) in E then
            if _not_ (there is a path from T[j] to T[i] in G=(V,E2)) then
                E2 = E2 + {(T[j], T[i])}   // add edge to E2
            end if
        end if

        // Invariant: G2=(T[0,i],E2) is the transitive reduction of
        // the graph G(T[0,i-1]) + G(T[j,i])

    end for

    // Invariant: At this time, G2=(T[0,i],E2) is the transitive
    // reduction of the graph G[T[0,i]].

end for

Output: G2=(V,E2) is the transitive reduction of G

Algorithm C
```

So why is it correct to only look for paths from `T[j]` to `T[i]` in
`G=(V,E2)` here, if it was incorrect in Algorithm B?

Suppose there are `k` edges into `T[i]`.

As a special case, if `k=0`, then the inner loop will not add any
edges into `T[i]` to `E2`, and then go on to the next iteration of the
outer loop.

The first edge of `k >= 1` edges `(T[j], T[i])` considered will have
the largest `j` among all such edges.  It is guaranteed that there are
no other paths from `T[j]` to `T[i]`, neither in `E2` nor in `E`.  If
there were another path, that path's last edge would be into `T[i]`,
and its source vertex be later than `T[j]` in the topological order.
Thus the edge `(T[j], T[i])` is always in the transitive reduction,
and Algorithm C will correctly add it to `E2`.

Before adding edge `(T[j], T[i])` to `E2`, we had `E2` containing the
edges of the transitive reduction of graph `G[T[0,i-1]]`.  After
adding that edge to `E2`, it is now the transitive reduction of the
slightly larger graph `G[T[0,i-1]] + G[T[j,i]]`.

The second edge `(T[j2], T[i])`, among `k >= 2` edges will always be
considered after the first edge `(T[j1], T[i])` into `T[i]` was added
to edge set `E2`.  Because both of the nodes `T[j1]` and `T[j2]` are
in `G(T[0,i-1])`, by the outer loop invariant `E2` already contains a
path from `T[j2]` to `T[j1]` if and only if the input graph did.  Thus
if there is a path from `T[j2]` to `T[i]` in the input graph that does
not go through edge `(T[j2], T[i])`, it will also exist in `E2` now.

Before considering the edge `(T[j2], T[i])` to `E2`, we had `E2`
containing the edges of the transitive reduction of graph
`G[T[0,i-1]] + G[T[j1,i]]`.
After considering that edge, and adding it to `E2` if there was no
other path from `T[j2]` to `T[i]`, `E2` is now the transitive
reduction of the slightly larger graph `G[T[0,i-1]] + G[T[j2,i]]`.

In general, after every step of the inner loop, after considering
whether to add an edge to `E2`, we enlarge the graph `G[T[0,i-1]] +
G[T[j,i]]` that `E2` is the transitive reduction for, until at the end
of the inner loop, `E2` is the transitive reduction for `G[T[0,i-1]] +
G[T[0,i]]`, which is the same as graph `G[T[0,i]]`.

Note: The above is not so much a formal proof, as it is an attempt to
provide the understanding of how the algorithm is proceeding, and what
can be proved at each step.


### Step D in refining an algorithm to find the transitive reduction of a DAG

Now we get to a version of the algorithm that is detailed enough that
we can prove a faster run time for it, versus Algorithm A, and yet
hopefully see that it is just a more detailed version of Algorithm C,
which is a more detailed version of Algorithm B, etc.

```
Input: DAG G=(V,E)

T = topological ordering of V in G
// Now T[0] is the first node of T, T[n-1] is the last
for t in 0 up to n-1 do
    v = T[t]
    vertex2t[v] = t
end for

E2 = {}   // empty set of edges
for i in 1 up to n-1 do
    // mark[j] will be assigned true if we find there is a path from
    // T[j] to T[i] in the input graph G.  Initialize it to false for
    // all nodes T[0] up to T[i-1].
    for j in 0 up to i-1 do
        mark[j] = false
    done

    for j in i-1 down to 0 do
        if there is an edge (T[j], T[i]) in E then
            if not mark[j] then
                E2 = E2 + {(T[j], T[i])}   // add edge to E2
                mark[j] = true
            end if
        end if

        // If T[j] can reach T[i], then any node with an edge into
        // T[j] can also reach T[i], so mark them, too.
        if mark[j] then
            for every edge (u, T[j]) in E2 do
                mark[vertex2t[u]] = true
            end for
        end if
    end for
    // Invariant: At this time, G=(T[0,i],E2) is the transitive
    // reduction of the graph G[T[0,i]].
end for

Output: G2=(V,E2) is the transitive reduction of G

Algorithm D
```

The run time of the first inner loop that initializes `mark` is
`O(V)`.

Assuming for the moment that the condition "there is an edge
`(T[j], T[i])` in `E`" can be performed in constant time (see below),
and that `E2` is maintained as a graph with all of the nodes of `G`,
plus lists of edges adjacent to each vertex, the second inner loop can
be implemented in `O(V+E2)` time.

While calculating the topological order `T`, we can construct new
adjacency lists of all edges into vertex `v`, such that the edges are
in the order that we want to consider them in the second inner loop of
the algorithm.  This enables performing the check "there is an edge
`(T[j], T[i])` in `E`" in constant time, at least in the context of
the inner loop of the algorithm, because we can maintain a "pointer"
to the next edge of the list of vertices into `T[i]`, and check in
`O(1)` time each time through the loop whether `j` is equal to the `j`
of the next edge `(T[j], T[i])`, or not.  See section ["Constructing
sorted lists of edges during topological
sorting"](#constructing-sorted-lists-of-edges-during-topological-sorting)
for details.

Thus the main loop can be performed in `O(V+E2)` time per iteration,
and the number of iterations is `O(V)`, so the total time is
`O(V*(V+E2))`.  It turns out that as long as this algorithm is run
independently on each weakly connected component of the input graph
`G`, the total run time is then `O(V*E2)`.  See ["Gory details on run
time for very sparse
DAGs"](#gory-details-on-run-time-for-very-sparse-dags) for the
details.


### Step E in refining an algorithm to find the transitive reduction of a DAG

The refinements in this section do not improve the running time of
Algorithm D in terms of having a smaller function in the "big O"
notation.  They are simply some improvements that can be made to
Algorithm D to avoid some wasted work in some cases.

Among all edges `(T[j], T[i])` into node `T[i]` considered in the
inner loop starting with the line `for j in i-1 down to 0 do`, let
`j_max` be the largest value of `j` among those edges, and `j_min` be
the smallest.

First, notice that the inner loop does not mark any node as reachable
until it gets to `j=j_max`.  Thus we can start that loop with
`j=j_max` rather than `j=i-1`, with no change in the final result.

Similarly, after we have done the first `if` statement of the loop
iteration with `j=j_min`, and added that edge to `E2` (or not), no
more changes to `E2` will be made in any future iterations of the loop
for `j < j_min`, so we can exit that loop then.  The `mark` array
values will be discarded, so there is no need to calculate them for
any values of `j < j_min`.

The pseudocode below incorporates the improvements mentioned above.
`break` and `continue` are used with the same meaning they have in C,
C++, Java and several other programming languages.

```
Input: DAG G=(V,E)

T = topological ordering of V in G
// Now T[0] is the first node of T, T[n-1] is the last
for t in 0 up to n-1 do
    v = T[t]
    vertex2t[v] = t
end for

E2 = {}   // empty set of edges
for i in 1 up to n-1 do
    if there are no edges into T[i] then
        continue
    end if
    // mark[j] will be assigned true if we find there is a path from
    // T[j] to T[i] in the input graph G.  Initialize it to false for
    // all nodes T[0] up to T[i-1].
    for j in 0 up to i-1 do
        mark[j] = false
    done

    // We can calculate j_max in O(1) time if all edges of E into
    // T[i] have been sorted from largest j to smallest j.
    j_max = maximum j such that (T[j], T[i]) is an edge in E
    for j in j_max down to 0 do
        if there is an edge (T[j], T[i]) in E then
            if not mark[j] then
                E2 = E2 + {(T[j], T[i])}   // add edge to E2
                mark[j] = true
            end if
            if (T[j], T[i]) is the last edge into T[i] then
                break
            end if
        end if

        // If T[j] can reach T[i], then any node with an edge into
        // T[j] can also reach T[i], so mark them, too.
        if mark[j] then
            for every edge (u, T[j]) in E2 do
                mark[vertex2t[u]] = true
            end for
        end if
    end for
    // Invariant: At this time, G=(T[0,i],E2) is the transitive
    // reduction of the graph G[T[0,i]].
end for

Output: G2=(V,E2) is the transitive reduction of G

Algorithm E
```

Here is one more possible speedup that might be achievable in some
cases, but it would require some measurements on a particular target
processor to know how effective it is.

We could, each time through the outer loop, count the number of nodes
`M` that we assign `mark[j]` to true.  If by the time we finish that
iteration of the outer loop, the number of such nodes is a small
fraction of `i`, it might be faster to simply do a traversal of the
graph edges in `E2`, starting at node `T[i]` and following edges in
the reverse direction, and change only nodes we find with
`mark[j]=true` back to false.  For some maximum value of `M` --
perhaps some formula like `0.05*i` -- this should be faster in
practice than assigning `mark[j]` to false for all `j` from 0 up to
`i-1`.


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
proved by Sartaj Sahni [3].  Basically, determining whether the
minimum equivalent graph of a directed graph has at most `|V|` edges
is the same as finding whether the graph has a Hamiltonian cycle,
i.e. a cycle that includes each node exactly once, which is another
known NP complete problem.

Khuller, Raghavachari, and Young prove that there is a polynomial time
algorithm that guarantees finding a solution that contains at most
about 1.64 times more edges than the minimum possible, and an
algorithm that takes only slightly longer than linear time that
guarantees finding a solution with at most 1.75 times more edges than
the minimum possible.


# References

[1] https://en.wikipedia.org/wiki/Transitive_reduction

[2] A. V. Aho, M. R. Garey, and J. D. Ullman, "The transitive
    reduction of a directed graph", SIAM J. Computing, Vol. 1, No. 2,
    June 1972, [https://doi.org/10.1137/0201008]

[3] Sartaj Sahni, "Computationally related problems", SIAM
    J. Computing, Vol. 3, No. 4, December 1974,
    [https://doi.org/10.1137/0203021]
    [https://www.cise.ufl.edu/~sahni/papers/computationallyRelatedProblems.pdf]

[4] S, Khuller, B. Raghavachari, and Neal Young, "Approximating the
    minimum equivalent graph digraph", SIAM J. Computing, Vol. 24,
    No. 4, 1995, also 2002 version on arXiv.org:
    [https://arxiv.org/abs/cs/0205040]



# Constructing sorted lists of edges during topological sorting

In particular, the goal is to take as input a directed graph `G=(V,E)`
represented using adjacency lists of the edges out of (and/or into)
each vertex `v`, and construct a topological order `T[0], ..., T[n-1]`
in `O(V+E)` time.  While doing this, also construct a list of edges
into each vertex `v`, sorted so that if `j1 < j2`, then edge `(T[j1],
T[i])` is after edge `(T[j2], T[i])` in the list of edges into vertex
`T[i]`, i.e. the source vertices are in reverse topological order.

We could do this by calculating a topological order using any
algorithm at all, then sorting the list of edges into each vertex,
using any `O(n log n)` run time sorting algorithm.  However, this
could require up to `O(V log V + E)` time for the topological ordering
plus the sorting.

We would prefer to do it in `O(V+E)` time total, and this is possible
if we construct the sorted lists of edges while calculating the
topological order.

```
Input: DAG G=(V,E)

// i2v and v2i represent a numbering in the range 0 up to n-1 of the
// vertices of V.  This is an arbitrary numbering that has nothing to
// do with the topological ordering.  It is temporary, discarded
// before returning.  We create this numbering so that we can use
// arrays instead of dictionaries/hash-maps for several data
// structures that we need for maintaining a value per vertex in G.

i = 0
for vertex v in V do
    i2v[i] = v
    v2i[v] = i
    sorted_in_edges_temp[i] = []   // empty list
    in_degree[i] = 0
    i = i + 1
end for

for edge (u,v) in E do
    i = v2i[v]
    in_degree[i] = in_degree[i] + 1
end for

// {} is the empty set.  candidates may be implemented as an ordered
// sequence/list of some kind.  The algorithm is correct regardless of
// the order of elements.
candidates = {}
for i in 0 up to n-1 do
    if (in_degree[i] = 0) then
        candidates = candidates + {i}     // add i to candidates
    end if
end for

t = 0
while (candidates is not empty) do
    i = arbitrary element of candidates
    candidates = candidates - {i}       // remove i from candidates
    u = i2v[i]
    T[t] = u
    t2i[t] = i
    t = t + 1
    for edge (u, v) in E do
        j = v2i[v]
        in_degree[j] = in_degree[j] - 1
        if (in_degree[j] = 0) then
            candidates = candidates + {j}     // add j to candidates
        end if
        // put edge (u, v) at beginning of list sorted_in_edges_temp[j]
        sorted_in_edges_temp[j] = [(u, v)] + sorted_in_edges_temp[j]
    end for
end while

if (t < size of V) then
    there is a cycle in G.  No topological ordering exists
end if

for t in 0 up to n-1 do
    i = t2i[t]
    sorted_in_edges[t] = sorted_in_edges_temp[i]
end for

Output:
T[0], ..., T[n-1] is a topological ordering of the vertices V

For all i in 0, ..., n-1, sorted_in_edges[i] is a list of edges into
vertex T[i] that is sorted in reverse topological order by the source
vertex.
```


# Gory details on run time for very sparse DAGs

`n` is the number of nodes in the input graph, and `m` the number of
edges.  `e` is the number of edges in the transitive reduction output
by the algorithm.

Suppose an algorithm runs on a weakly connected DAG in `O(n*(n+e))`
time.  If we ran that algorithm on a DAG with many small weakly
connected components, where `e` is much smaller than `n`, it is
possible that `O(n*(n+e))` could be larger than `O(n*e)` (see note at
end of this section).

Determining all weakly connected components of a directed graph can be
done in `O(n+m)` time.  The result could be a list of sets of nodes in
each weakly connected component, where each node set could also be
represented by a list.

Now run the `O(n*(n+e))` algorithm on each weakly connected component.
The total run time would then be:

```
[Eqn 1]    n_0 + n_1 * (n_1 + e_1) + ... + n_c * (n_c + e_c)
```

where `c` is the number of weakly connected components with at least
one edge, `n_i` is the number of nodes in weakly connected component
`i`, and `e_i` is the number of edges in the output for the weakly
connected component `i`.  I have introduced `n_0` as the number of
"isolated nodes", i.e. those with no edges at all.  Each of those can
be handled in constant time, so the total time to handle them all is
`O(n_0)`.

We know `e_i >= n_1 - 1`, because it requires at least that many edges
to connect a weakly connected component.  We can also express this as
`n_i <= e_i + 1`.  Thus starting with [Eqn 1] we can get the following
upper bound on the run time:

```
           [Eqn 1]
         = n_0 + n_1 * (n_1 + e_1)   + ... + n_c * (n_c + e_c)
           { n_i <= e_i + 1 for all i }
        <= n_0 + n_1 * (2 * e_1 + 1) + ... + n_c * (2 * e_c + 1)
           { algebra }
         = (n_0 + n_1 + ... + n_c) + 2 * (n_1 * e_1 + ... + n_c * e_c)
           { n_0 + n_1 + ... + n_c = n }
         = n + 2 * (n_1 * e_1 + ... + n_c * e_c)
           { n_i <= n for all i }
        <= n + 2 * (n * e_1 + ... + n * e_c)
           { algebra }
         = n + 2 * n * (e_1 + ... + e_c)
           { e_1 + ... + e_c = e }
         = n + 2*n*e
         = O(n*e + n)
         = O(n*e)
```

It requires `O(n+m)` time, where `m` is the number of edges in the
input graph, to determine the weakly connected components, and to do
the topological sorting, so at first it seems that the most accurate
way to describe the run time is `O(n*e + m)`.

But note that in each weakly conncted component, `e >= n-1`, and `m <=
n*(n-1)/2` (ignoring graphs with parallel edges), so `m <= n*e/2`.
That can be summed up across all weakly connected components similarly
to what is shown above, with the result that `m` is `O(n*e)` for the
entire graph.  Thus `O(n*e + m)` is `O(n*e)`.

Note:

If we do _not_ first determine the weakly connected components, and
run the `O(n*(n+e))` algorithm independently on each one, but instead
run the algorithm on the entire input graph all at once, it depends
upon the details of the algorithm, but without further proof it is at
least possible that the run time `O(n*(n+e))` is strictly larger than
`O(n*e)`.

For example, if, `e = n^a` for `0 < a < 1`, then `O(n*e) =
O(n^(1+a))`, strictly less than `O(n^2)`, but `O(n*(n+e))` is `O(n^2 +
n^(1+a)) = O(n^2)`.


# Finding both weakly and strongly connected components in a single linear time 'pass' of a graph

It is certainly straightforward to execute any kind of a linear time
traversal of a directed graph to find all weakly connected components.

When traversing the edges out of a node, we should also traverse all
edges into the node as well, as if they were out edges.  In the first
traversal starting from an arbitrary node, mark all nodes reached as
being in weakly connected component 1.  If there are any unreached
nodes, start another traversal from an arbitrarily selected one, and
mark all nodes reached from that one as being in weakly connected
component 2.  Repeat until all nodes have been marked with a weakly
connected component number.

Performing that, plus a separate linear time algorithm to find all
strongly connected components is still a total of linear time, so in a
big O run time sense, there is no need to try to find both weakly and
strongly connected components in the "same" one traversal of the
graph.

I am not sure, but I suspect that graphs that do not fit in a certain
level of caching in a memory hierarchy, traversing them N times would
often require the data crossing a level of the memory hierarchy N
times, whereas traversing them only once would keep N down to 1
(assuming that the any extra data maintained as a result does not
increasing the working set size too much, which could easily become a
factor).

So I am curious: can a linear time algorithm for finding strongly
connected components be augmented in a straightforward manner to also
find weakly connected components?

Tarjan's algorithm for finding strongly connected components picks an
arbitrary node n1 to start a DFS traversal from, and when all nodes
reachable from n1 have been traversed, it checks whether there is
another node n2 in the graph that has not been reached, and if so,
starts a DFS traversal from n2.

Let us use R(n1) to name the set of nodes reached from n1 in the DFS
traversal starting at n1.  Obviously all nodes in R(n1) are in the
same weakly connected component, but there could be many other nodes
not in R(n1) that are in the same weakly connected component, too.

We could try picking an arbitrary node n2 outside of R(n1) to start
the next DFS traversal.  If it ever reaches an edge that leads into
R(n1), we know that R(n2) and R(n1) are in the same weakly connected
component.  However, they could be in the same weakly connected
component even if there are no direct edges between a node in R(n1)
and a node in R(n2), e.g. there could be a node n3 that has an edge
into a node of R(n1), and another edge into R(n2), where R(n3) is not
reached in the DFS traversals of either R(n1) nor R(n2).

TBD: All we would have to do to find one is to follow one edge into a
node of R(n1) that is from a node outside of R(n1).

Use wcc as an abbreviation for weakly connected component.

I believe this modified version of the algorithm would be correct.

Initialize WCC(n) to 0 for all nodes.  WCC(n)=0 means "this node's wcc
number has not been determined yet".  At the end of executing the
algorithm, all nodes will have a value of WCC(n) in the range [1, W],
where W is the number of wccs in the graph.

Initialize UIE(n) to be false for all nodes.  UIE is an abbreviation
for "Unexplored In-Edges".  We will change this value to true for a
node when we reach it in a DFS traversal, and it has at least one
in-edge.

Initialize UIEset to be an empty set of nodes.  This is a set of
nodes, with no need to maintain any ordering requirements, but for
efficiency's sake it is correct to use a list.  The only operations
needed on UIEset are to add an element, one that is guaranteed not to
have been added before, and to select and remove an arbitrary element.

Initialize w=1.  This is the current wcc number we will assign to all
nodes in the first wcc identified.

For the first arbitrary selected node n1 and all nodes it reaches in
the first DFS traversal starting from n1, assign those nodes WCC(n)=w.

Every time we reach a node n that has at least one in-edge, and
UIE(n)=false, assign UIE(n)=true and add n to UIEset.

When all nodes reachable from n1 via DFS have been identified, and
there are no more, the basic idea is that we want to identify if there
are any edges into R(n1) from outside of R(n1), and if so, start the
next DFS from one of the nodes with such an edge from it into R(n1).
That node will be in the same weakly connected component as all in
R(n1), so we keep w unchanged, and continue marking nodes in the next
DFS with the same value of w.

The only time we increment w is if we look for an edge from an
unexplored node to an explored node, and find none.  Then we increment
w, and look for an arbitrary node that has not been explored yet to
start the next DFS traversal.

We want this extra work to take a total time that is at most linear in
the size of the graph.

After finishing a DFS traversal, pick an arbitrary node in UIEset
