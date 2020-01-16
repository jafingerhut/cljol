# Notes on transitive reductions, and one algorithm to calculate them

This article belongs better in some place more directly related to
graph algorithms, perhaps the Ubergraph repository.  I am creating it
here for now, just to have a place to put it.


## Definitions


### Transitive closure

The _transitive closure_ of a directed graph `G=(V,E)` is the unique
graph `tc(G)=(V,E*)` with the same set of vertices as `G`, and an edge
`(u,v)` in `E*` if and only if there is a directed path from `u` to `v
`in `G`.

Given a directed graph `G=(V,E)`, we say that the graph `H=(V,F)`
_preserves reachability with `G`_ if, for every pair of vertices `u`, `v`
in `V`, there is a directed path from `u` to `v` in `H` if and only if
there is a directed path from `u` to `v` in `G`.  This is true if and
only if `tc(H)=tc(G)`.


### Irreducible kernel

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
with different number of edges from each other.  (TBD: example?)


### Minimum equivalent graph

A _minimum equivalent graph_ of a directed graph `G=(V,E)` is an
irreducible kernel of `G` that has the minimum number of edges among
all irreducible kernels of `G`.


### Transitive reduction

A _transitive reduction_ of a directed graph `G=V(,E)` is any graph
`H=(V,F)` where all of the following are true:

+ It has the same set of vertices as `G`.
+ Its edges `F` are allowed to include edges that are not in `E`.
+ `H` preserves reachability with `G`.
+ Among all graphs satisfying these properties, `H` has a minimum
  number of edges possible.


### Self loops

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


### Multigraphs, or parallel edges

For most of this, I will consider a directed graph not to contain any
parallel edges, i.e. for any pair of vertices `u` and `v` in a graph,
either there is exactly one edge `(u,v)`, or there is no such edge.

This is an important restriction for some of the discussion below
about a directed acyclic graph having a unique transitive reduction,
for example.  If an input graph can have parallel edges, and one
considers these parallel edges to be different from each other, then
those statements about some graphs being unique need to be amended,
but only slightly.


### Directed acyclic graphs (DAG)

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

TBD: Give description and proof of algorithm to find the transitive
reduction of a DAG in `O(N*e)` time, where `e` is the number of edges
in the transitive reduction (which is guaranteed to be at most the
number of edges in the input graph), and for some input graphs can be
much smaller.


### Graphs with cycles

The concepts of irreducible kernel, minimum equivalent graph, and
transitive reduction can be different from each other for graphs with
cycles.

Abbreviations:

+ IK - irreducible kernel
+ MEG - minimum equivalent graph
+ TR - transitive reduction

```
     +---+              +---+              +---+        
   --| 1 |--          --| 1 |            --| 1 |--      
  /  +---+  \        /  +---+           /  +---+  \     
 |   ^   ^   |      |       ^          |   ^   ^   |            
 |   |   |   |      |       |          |   |   |   |            
 |  /     \  |      |        \         |  /     \  |            
 V  |      | V      V         |        V  |      | V            
+---+      +---+   +---+      +---+   +---+      +---+
| 2 |----->| 3 |   | 2 |----->| 3 |   | 2 |      | 3 |
+---+      +---+   +---+      +---+   +---+      +---+

    Graph G1         IK #1 of G1        IK #2 of G1
                   Also a MEG and       Neither a MEG
                     a TR of G1         nor a TR of G1
```

For graph `G1`, it has at least the two different irreducible kernels
shown, and one has 4 edges while the other has 3.  The one with 3
edges is also a minimum equivalent graph and a transitive reduction of
`G1`, but the one with 4 edges is neither, because it has more than
the minimum possible number of edges.

```
     +---+              +---+              +---+       
   --| 1 |--          --| 1 |              | 1 |--     
  /  +---+  \        /  +---+              +---+  \    
 |   ^   ^   |      |       ^              ^       |   
 |   |   |   |      |       |              |       |   
 |  /     \  |      |        \            /        |   
 V  |      | V      V         |           |        V   
+---+      +---+   +---+      +---+   +---+      +---+ 
| 2 |      | 3 |   | 2 |----->| 3 |   | 2 |<-----| 3 | 
+---+      +---+   +---+      +---+   +---+      +---+ 

    Graph G2         TR #1 of G1         TR #2 of G1
 Also the only
 IK and MEG of G2
```

For graph `G2`, it has only one irreducible kernel and minimum
equivalent graph, which includes all of the edges of `G2`, because
removing any of them would not preserve reachability.

The two transitive reductions shown in the figure have only 3 edges,
strictly less than the 4 edges in the irreducible kernel or minimum
equivalent graph.  The transitive reduction has the freedom to
introduce edges that are not in the original graph, and can thus have
fewer edges than the minimum equivalent graph.

We can extend graph `G2` to a graph `G(N)` that has nodes 4, 5, 6,
etc. up to `N`, with edges `(1,i)` and `(i,1)` for every node `i > 1`.
`G(N)` has only itself as its irreducible kernel and minimum
equivalent graph, with `2*N-2` edges.  Any cycle among the `N`
vertices is a transitive reduction of `G(N)`, with only `N` edges.
This demonstrates that the minimum equivalent graph and transitive
reduction can have almost twice as many edges as a transitive
reduction.  I do not know if there are graphs where the irreducible
kernel or minimum equivalent graph have more than twice as many edges
as a transitive reduction.

So there are graphs with cycles that have more than one irreducible
kernel, more than one minimum equivalent graph, and more than one
transitive reduction.

Some graphs have irreducible kernels that have more edges than the
minimum equivalent graph.  Some graphs with cycles have both the
irreducible kernels and minimum equivalent graphs with more edges than
a transitive reduction.  Some cyclic graphs have transitive reductions
containing edges not in the original graph.



# References

[1] https://en.wikipedia.org/wiki/Transitive_reduction

[2] A. V. Aho, M. R. Garey, and J. D. Ullman, "The transitive
    reduction of a directed graph", SIAM J. Computing, Vol. 1, No. 2,
    June 1972 https://doi.org/10.1137%2F0201008
