These files contain 2 very similar patches, the first of which I
proposed as some relatively small additions to the existing JOL
project maintainers as a patch for consideration.  I want to leave
that file as is for a while, to give them time to respond to the idea,
if they are interested.

* jol-0-9-plus-parseInstanceIds.diff

This is a minor variation of the above, with a few more changes that
make it easier for me to test the changes on my dev machine:

* jol-0-9-plus-parseInstanceIds-plus-andy-dev-changes.diff

Below is a somewhat bigger change, that adds '2' variants of 4
different classes, starting with GraphPathRecord2 that has fewer
fields, only the ones I actually use in the cljol project, and then
working up to GraphLayout2.java which has the IdentityHashMap return
value, and no addresses 'map' return value at all, because it is not
really necessary for cljol.

* jol-0-9-minor-andy-dev-changes.diff
* GraphLayout2.java
* GraphPathRecord2.java
* GraphVisitor2.java
* GraphWalker2.java

This file contains changes to the dig9.clj source file of cljol, to
take advantage of the changes in the last batch of files above.

* dig9.ihm-changes.clj

Early July 2019 version of the last file above contains changes that
take advantage of the first patch above.


If you do not have javadoc installed in a system, you can still build
and test JOL with this command:

```bash
$ mvn -Dmaven.javadoc.skip=true clean install
```
