Machine tested on is macOS 10.14.6 with Oracle JDK 8 installed.

```bash
$ java -version
java version "1.8.0_192"
Java(TM) SE Runtime Environment (build 1.8.0_192-b12)
Java HotSpot(TM) 64-Bit Server VM (build 25.192-b12, mixed mode)

$ wc dark-corpus-2.edn 
       0 22982424 150383643 dark-corpus-2.edn
```

Link to EDN file dark-corpus-2.edn obtained from here on 2020-Oct-27:
https://www.dropbox.com/s/urby2ahcwp58l4f/dark-corpus-2.edn?dl=0

Found in this Github issue for the nippy project:
https://github.com/ptaoussanis/nippy/issues/136

Note: I recommend that you do _not_ try to run `d/sum` or `d/view` on
the entire map `x1` created below.  It would be very slow and consume
a lot of memory, at least 5 times than the memory required by `x1`
itself.  These functions are not really designed for analyzing such
large data structures.  They are quick at analyzing smaller data
structures, as demonstrated below.


```bash
$ clj -Sdeps '{:deps {cljol/cljol {:git/url "https://github.com/jafingerhut/cljol" :sha "ff33d97f8375b4a0aaf758295e0aef7185ef9d6e"}}}'

Clojure 1.10.1
```

```clojure
user=> (def fname "dark-corpus-2.edn")
#'user/fname
(require '[clojure.edn :as edn]
         '[clojure.java.io :as io])
nil
user=> (require '[cljol.dig9 :as d])
Boxed math warning, cljol/ubergraph_extras.clj:128:27 - call: public static java.lang.Number clojure.lang.Numbers.divide(java.lang.Object,long).
Boxed math warning, cljol/ubergraph_extras.clj:128:9 - call: public static boolean clojure.lang.Numbers.lt(long,java.lang.Object).
nil
user=> (def cljol-opts {:summary-options #{:size-breakdown :class-breakdown}})
#'user/cljol-opts

;; Read the EDN file

user=> (def x1 (edn/read (java.io.PushbackReader. (io/reader fname))))
#'user/x1

;; Printed representation as EDN is about 150 million chars long
user=> (count (str x1))
150383643

;; Examine first key/value pair

user=> (def kv0 (nth (seq x1) 0))
#'user/kv0

user=> kv0
[("profanity" "unholy") {"its" 2}]

;; 34 chars long as EDN

user=> (count (str kv0))
34

;; d/sum shows that it occupies 12 JVM objects in memory, totaling 360
;; bytes of memory

user=> (def s1 (d/sum [kv0] cljol-opts))
# WARNING: Unable to attach Serviceability Agent. You can try again with escalated privileges. Two options: a) use -Djol.tryWithSudo=true to try with sudo; b) echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope
12 objects
11 references between them
360 bytes total in all objects
no cycles
number of objects of each size in bytes:
({:size-bytes 24, :num-objects 6, :total-size 144}
 {:size-bytes 32, :num-objects 3, :total-size 96}
 {:size-bytes 40, :num-objects 3, :total-size 120})
number and size of objects of each class:
({:total-size 24, :num-objects 1, :class "j.l.Long"}
 {:total-size 24, :num-objects 1, :class "[Ljava.lang.Object;"}
 {:total-size 32, :num-objects 1, :class "c.l.MapEntry"}
 {:total-size 32, :num-objects 1, :class "c.l.PersistentArrayMap"}
 {:total-size 72, :num-objects 3, :class "j.l.String"}
 {:total-size 80, :num-objects 2, :class "c.l.PersistentList"}
 {:total-size 96, :num-objects 3, :class "[C"})

4 leaf objects (no references to other objects)
1 root nodes (no reference to them from other objects _in this graph_)
#'user/s1

;; d/view will show a picture of these 12 objects, the size and class
;; of each, the value of their fields, and a printed representation of
;; each one's value.

user=> (d/view [kv0])
nil

;; The number of memory bytes occupied by these objects divided by the
;; number of characters in the EDN representation averages to 10.6
;; memory bytes per character.

user=> (/ 360.0 34)
10.588235294117647

;; Let us create a map that contains only the first 100 keys of the
;; full map.

user=> (def x1-100 (select-keys x1 (take 100 (keys x1))))
#'user/x1-100

;; Its EDN representation is 4304 characters.  It is represented in
;; memory by 1382 JVM objects, totaling 41,656 bytes.

user=> (count (str x1-100))
4304
user=> (def s1 (d/sum [x1-100] cljol-opts))
1382 objects
1640 references between them
41656 bytes total in all objects
no cycles
number of objects of each size in bytes:
({:size-bytes 16, :num-objects 8, :total-size 128}
 {:size-bytes 24, :num-objects 777, :total-size 18648}
 {:size-bytes 32, :num-objects 278, :total-size 8896}
 {:size-bytes 40, :num-objects 243, :total-size 9720}
 {:size-bytes 48, :num-objects 59, :total-size 2832}
 {:size-bytes 56, :num-objects 5, :total-size 280}
 {:size-bytes 64, :num-objects 3, :total-size 192}
 {:size-bytes 72, :num-objects 2, :total-size 144}
 {:size-bytes 80, :num-objects 2, :total-size 160}
 {:size-bytes 112, :num-objects 2, :total-size 224}
 {:size-bytes 144, :num-objects 3, :total-size 432})
number and size of objects of each class:
({:total-size 72,
  :num-objects 3,
  :class "c.l.PersistentHashMap$ArrayNode"}
 {:total-size 128,
  :num-objects 8,
  :class "j.u.c.atomic.AtomicReference"}
 {:total-size 200, :num-objects 5, :class "c.l.PersistentHashMap"}
 {:total-size 312, :num-objects 13, :class "j.l.Long"}
 {:total-size 432,
  :num-objects 3,
  :class "[Lclojure.lang.PersistentHashMap$INode;"}
 {:total-size 2064,
  :num-objects 86,
  :class "c.l.PersistentHashMap$BitmapIndexedNode"}
 {:total-size 3072, :num-objects 96, :class "c.l.PersistentArrayMap"}
 {:total-size 6696, :num-objects 182, :class "[Ljava.lang.Object;"}
 {:total-size 8000, :num-objects 200, :class "c.l.PersistentList"}
 {:total-size 9432, :num-objects 393, :class "j.l.String"}
 {:total-size 11248, :num-objects 393, :class "[C"})

416 leaf objects (no references to other objects)
1 root nodes (no reference to them from other objects _in this graph_)
#'user/s1

;; Average of 9.7 memory bytes per character of EDN for this map

user=> (/ 41656.0 4304)
9.678438661710038
```

If we extrapolate for the entire map, whose EDN representation is
150,383,643 characters, times an average of 9.7 memory bytes per
character, that is about 1.5 GBytes of memory.
