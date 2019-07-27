These files contain 2 very similar patches, the first of which I
proposed as some relatively small additions to the existing JOL
project maintainers as a patch for consideration.  I want to leave
that file as is for a while, to give them time to respond to the idea,
if they are interested.

* jol-0-9-plus-parseInstanceIds.diff

This is a minor variation of the above, with a few more changes that
make it easier for me to test the changes on my dev machine:

* jol-0-9-plus-parseInstanceIds-plus-andy-dev-changes.diff

I have made some slightly bigger changes, that adds '2' variants of 4
different classes, starting with GraphPathRecord2 that has fewer
fields, only the ones I actually use in the cljol project, and then
working up to GraphLayout2.java which has the IdentityHashMap return
value, and no addresses 'map' return value at all, because it is not
really necessary for `cljol`.

These source files are checked into the Github project
[cljol-jvm-support](https://github.com/jafingerhut/cljol-jvm-support)
and [a JAR
file](https://clojars.org/com.fingerhutpress.cljol_jvm_support/cljol_jvm_support)
has been deployed to Clojars.org.


Notes on building the jol-core library from Java source code:

If you do not have javadoc installed in a system, you can still build
and test JOL with this command:

```bash
$ mvn -Dmaven.javadoc.skip=true clean install
```
