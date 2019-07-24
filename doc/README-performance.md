# Performance improvements from ubergraph 0.5.3 to 0.6.0

Some modest reductions in compute time of some steps on large graphs.
Definitely improvements worth having.

macOS 10.13.6

$ java -version
java version "1.8.0_192"
Java(TM) SE Runtime Environment (build 1.8.0_192-b12)
Java HotSpot(TM) 64-Bit Server VM (build 25.192-b12, mixed mode)

git SHA of cljol: 9c38e79e93e7acc191c27b5fb6e426e81c88f53e

```
----------------------------------------------------------------------
[ ... some output elided if the ubergraph version did not affect the
performance ...]

converted 129398 objmaps into ubergraph with 273382 edges: 2277.190175 msec, 1 gc-count, 26 gc-time-msec
The scc-graph has 107087 nodes and 179726 edges, took: 3582.82373 msec, 1 gc-count, 63 gc-time-msec
Calculated num-reachable-nodes and total-size  for scc-graph in: 3679.876264 msec, 2 gc-count, 88 gc-time-msec

calculated :bounded total sizes: 11931.822018 msec, 4 gc-count, 195 gc-time-msec
added graphviz attributes: 8187.715493 msec, 5 gc-count, 134 gc-time-msec
129398 objects
273382 references between them
4987408 bytes total in all objects
25958 leaf objects (no references to other objects)

----------------------------------------------------------------------
converted 129400 objmaps into ubergraph with 273384 edges: 2447.445549 msec, 1 gc-count, 77 gc-time-msec
The scc-graph has 107089 nodes and 179729 edges, took: 4302.79296 msec, 2 gc-count, 735 gc-time-msec
Calculated num-reachable-nodes and total-size  for scc-graph in: 3458.973393 msec, 1 gc-count, 53 gc-time-msec

calculated :bounded total sizes: 12639.766494 msec, 5 gc-count, 961 gc-time-msec
added graphviz attributes: 8667.186223 msec, 6 gc-count, 175 gc-time-msec
129400 objects
273384 references between them
4987448 bytes total in all objects
25958 leaf objects (no references to other objects)
```


Same as above, except one line change to deps.edn to use ubergraph
0.6.0 instead of 0.5.3:

```
----------------------------------------------------------------------
converted 129636 objmaps into ubergraph with 273713 edges: 2265.629436 msec, 1 gc-count, 35 gc-time-msec
The scc-graph has 107319 nodes and 180028 edges, took: 3955.709981 msec, 2 gc-count, 906 gc-time-msec
Calculated num-reachable-nodes and total-size  for scc-graph in: 2206.444629 msec, 1 gc-count, 121 gc-time-msec

calculated :bounded total sizes: 10918.40454 msec, 4 gc-count, 1083 gc-time-msec
added graphviz attributes: 9037.733732 msec, 7 gc-count, 415 gc-time-msec
129636 objects
273713 references between them
4995888 bytes total in all objects
26015 leaf objects (no references to other objects)

----------------------------------------------------------------------
converted 129636 objmaps into ubergraph with 273713 edges: 2272.416103 msec, 1 gc-count, 86 gc-time-msec
The scc-graph has 107319 nodes and 180028 edges, took: 3796.13578 msec, 2 gc-count, 766 gc-time-msec
Calculated num-reachable-nodes and total-size  for scc-graph in: 1934.077117 msec, 1 gc-count, 47 gc-time-msec

calculated :bounded total sizes: 10577.804244 msec, 5 gc-count, 1015 gc-time-msec
added graphviz attributes: 8912.418918 msec, 6 gc-count, 435 gc-time-msec
129636 objects
273713 references between them
4996272 bytes total in all objects
26015 leaf objects (no references to other objects)
```
