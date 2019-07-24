(ns cljol.dig9
  (:import (java.lang.reflect Field Method Modifier))
  (:import (org.openjdk.jol.info ClassLayout GraphLayout2 GraphPathRecord2
                                 ClassData FieldData))
  (:import (org.openjdk.jol.vm VM))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]
            ;;[clj-async-profiler.core :as prof]
            [cljol.object-walk :as ow :refer [ClassData->map]]
            [cljol.graph :as gr]
            [cljol.performance :as perf :refer [my-time print-perf-stats]]))


(set! *warn-on-reflection* true)


;; starts-with? copied from Clojure's implementation, to enable this
;; code to be used with slightly older versions of Clojure than 1.9.0.

(defn starts-with?-copy
  "True if s starts with substr."
  {:added "1.8"}
  [^CharSequence s ^String substr]
  (.startsWith (.toString s) substr))


;; Main web page for JOL (Java Object Layout) library, where links to
;; all example source code mentioned below was found, library source
;; code and JAR build/install instructions, API documentation, and
;; example command lines:

;; http://openjdk.java.net/projects/code-tools/jol/


;; Function class-layout->str as adapted from the following source
;; code:
;; http://hg.openjdk.java.net/code-tools/jol/file/a6a3bf9b6636/jol-cli/src/main/java/org/openjdk/jol/operations/ObjectInternals.java

;; Sample call:
;; (println (class-layout->str #{}))

(defn class-layout->str [obj]
  (let [cls (class obj)
        parsed-cls (ClassLayout/parseClass cls)]
    (.toPrintable parsed-cls obj)))


(defn instance-layout->str [obj]
  (let [parsed-inst (ClassLayout/parseInstance obj)]
    (.toPrintable parsed-inst)))


(defn array? [x]
  (if-not (nil? x)
    (. (class x) isArray)))


(defn ^Class array-element-type [arr]
  (. (class arr) getComponentType))


(defn address-of [obj]
  (. (VM/current) addressOf obj))


(def inaccessible-field-val-sentinel (Object.))
(def non-strong-ref-sentinel (Object.))
(def unknown-sentinel (Object.))

(comment
;; sometimes useful for debugging sentinel values in REPL
(pprint
  [["inaccessible" d/inaccessible-field-val-sentinel]
   ["non-strong" d/non-strong-ref-sentinel]
   ["unknown" d/unknown-sentinel]])
)


(defn cljol-sentinel-value? [obj]
  (or (identical? obj inaccessible-field-val-sentinel)
      (identical? obj non-strong-ref-sentinel)
      (identical? obj unknown-sentinel)))


;; Conditionally require one of cljol.jdk8-and-earlier or
;; cljol.jdk-9-and-later to get a definition of fn obj-field-value
;; appropriate for the JDK running.  JDK 9 and later define a new
;; java.lang.reflect.InaccessibleObjectException exception that did
;; not exist in JDK 8 and earlier.
(def jvm-major-version
  (-> (get (System/getProperties) "java.version")
      (str/split #"\.")
      first
      (Integer/parseInt)))


(if (>= jvm-major-version 9)
  (require '[cljol.jdk9-and-later :as ofv])
  (require '[cljol.jdk8-and-earlier :as ofv]))


(defn non-strong-reference? [obj]
  (instance? java.lang.ref.Reference obj))


(defn address-from-snapshot [obj ^java.util.IdentityHashMap obj->gpr
                             parent-obj parent-obj-field-name-str]
  (cond
    (nil? obj) nil
    (cljol-sentinel-value? obj) obj
    (non-strong-reference? parent-obj) non-strong-ref-sentinel
    :else
    (let [^GraphPathRecord2 gpr (.get obj->gpr obj)]
      (if (nil? gpr)
        (let [^GraphPathRecord2 parent-gpr (.get obj->gpr parent-obj)
              msg (format "Failed to find GraphPathRecord2 in IdentityHashMap obj->grp for object with %s.  Parent object has %s address %s and refers to object through field named '%s'"
                          (class obj) (class parent-obj)
                          (if (nil? parent-gpr)
                            "(unknown)"
                            (. parent-gpr address))
                          parent-obj-field-name-str)
              exception-data {:obj obj :obj->gpr :obj->gpr}]
          ;;(throw (ex-info msg exception-data))
          (println msg)
          unknown-sentinel)
        (. gpr address)))))


#_(defn field-name-and-address [^Field fld obj]
  [(. fld getName)
   (let [fld-val (ofv/obj-field-value obj fld inaccessible-field-val-sentinel)]
     (if (nil? fld-val)
       nil
       (address-of fld-val)))])


(defn field-name-and-snapshot-address [^Field fld obj obj->gpr]
  (let [field-name (. fld getName)]
    [field-name
     (let [fld-val (ofv/obj-field-value obj fld
                                        inaccessible-field-val-sentinel)]
       (address-from-snapshot fld-val obj->gpr obj field-name))]))


;; Several Java interop calls in the next few lines of code cause
;; reflection warnings that are not easily eliminated via type hints,
;; at least not in any way that I know of.  Disable reflection
;; warnings for this short section of code.

(set! *warn-on-reflection* false)

#_(defn array-elem-name-and-address [idx array-obj]
  (let [array-idx-val (aget array-obj idx)]
    [(str "[" idx "]")
     (if (nil? array-idx-val)
       nil
       (address-of array-idx-val))]))


(defn array-elem-name-and-snapshot-address [idx array-obj obj->gpr]
  (let [field-name (str "[" idx "]")]
    [field-name
     (let [array-idx-val (aget array-obj idx)]
       (address-from-snapshot array-idx-val obj->gpr
                              array-obj field-name))]))


;; obj() is a private method of class GraphPathRecord2.  Use some Java
;; hackery to call it anyway, as long as the security policy in place
;; allows us to.

(def gpr-class (Class/forName "org.openjdk.jol.info.GraphPathRecord2"))
(def gpr-obj-method (.getDeclaredMethod gpr-class "obj" nil))
(.setAccessible gpr-obj-method true)

(def empty-obj-array (object-array []))

(defn gpr->java-obj [gpr]
  (.invoke gpr-obj-method gpr empty-obj-array))

(set! *warn-on-reflection* true)


(def size-mismatch-warnings (atom {}))


(defn warn-size-mismatch! [obj gpr fast-size slow-size]
  (let [cls (class obj)
        earlier-report-for-cls? (contains? @size-mismatch-warnings cls)]
    (when-not earlier-report-for-cls?
      (let [arr? (array? obj)
            ref-arr? (and arr? (not (. (array-element-type obj) isPrimitive)))
            cd (ClassData/parseClass (class obj))
            sorted-offsets (if ref-arr?
                             []
                             (vec (sort (map :vm-offset (:fields cd)))))
            max-offset (if ref-arr?
                         nil
                         (if (zero? (count sorted-offsets))
                           0
                           (peek sorted-offsets)))]
        (println "WARNING:" cls "has GraphPathRecord2 size" fast-size
                 "but ClassLayout size" slow-size
                 "sorted-offsets" sorted-offsets)
        (swap! size-mismatch-warnings assoc cls
               {:cls cls :gpr gpr :fast-size fast-size
                :slow-size slow-size
                :sorted-offsets sorted-offsets})))))


(defn clear-atoms! []
  (swap! size-mismatch-warnings (constantly {})))


;; Note 1:

;; I have seen issues before with objects 'obj' such that (=
;; java.lang.Class (class obj)) is true, e.g. for obj=(class
;; 5)=java.lang.Long, where the size in bytes determined by (. gpr
;; size) was significantly larger, e.g. 632 bytes, than the size
;; reported using ClassLayout/parseClass followed by the instanceSize
;; method, e.g. 96 bytes.

;; This can cause later sanity checks of objects that overlap in
;; memory to fail (see function any-objects-overlap?), when they
;; should succeed.

;; From some more testing on macOS and Ubuntu Linux, and JDK versions
;; ranging over 8, 9, 11, and 12, I have only seen this issue with JDK
;; 8, on all operating systems, but so far never with JDK 9, 11, nor
;; 12 (only tested on Linux so far).  So far I have never seen it
;; happen for any objects 'obj' where (class obj) is anything _except_
;; java.lang.Class.

;; I do not know if this is a bug in JOL 0.9, or the JDK, but since it
;; seems to be corrected in later JDK versions, I will not worry too
;; much about it now, other than to try to avoid the problem here.

;; From some testing with a graph of objects containing over 100,000
;; nodes, I did not notice it taking much longer using the 'slow' way,
;; so it is probably a good idea to simply consistently use that way
;; all of the time.

;; Using ClassLayout/parseInstance returns accurate sizes for array
;; objects, whereas ClassLayout/parseClass only returns the size of a
;; 0 length array of the same type, because it has no information
;; about the particular array object to get the number of array
;; elements from.

(defn slow-object-size-bytes [obj]
  ;;(. (ClassLayout/parseClass (class obj)) instanceSize)
  (. (ClassLayout/parseInstance obj) instanceSize))


(defn fast-but-has-bugs-object-size-bytes [^GraphPathRecord2 gpr]
  (. gpr size))


(defn workaround-object-size-bytes [obj gpr opts]
  (let [slow-checking? (get opts :slow-instance-size-checking? false)]
    (if slow-checking?
      (let [fast-size (fast-but-has-bugs-object-size-bytes gpr)
            slow-size (slow-object-size-bytes obj)]
        (when (not= fast-size slow-size)
          (warn-size-mismatch! obj gpr fast-size slow-size))
        slow-size)

;;      ;; else use the workaround that in my testing avoids the bug
;;      (if (= java.lang.Class (class obj))
;;        (slow-object-size-bytes obj)
;;        (fast-but-has-bugs-object-size-bytes gpr))

      (slow-object-size-bytes obj))))


(def stop? (proxy [java.util.function.Predicate] []
             (test [obj]
;;               (when (instance? java.lang.ref.Reference obj)
;;                 (println "called proxy obj with arg having" (class obj)))
               (instance? java.lang.ref.Reference obj))))


(defn reachable-objmaps-helper
  [obj-coll opts]
  (let [debug-level (get opts :reachable-objmaps-debuglevel 0)
        max-attempts (get opts :max-address-snapshot-attempts 3)
        stop-at-references (get opts :stop-walk-at-references true)
        stop-walk-predicate (if stop-at-references stop? nil)
        {^GraphLayout2 parsed-inst :ret :as p}
        (my-time (GraphLayout2/parseInstanceIds stop-walk-predicate
                                                (object-array obj-coll)))
        num-objects-found (. parsed-inst totalCount)
        _ (when (>= debug-level 1)
            (print "reachable-objmaps-helper found" num-objects-found
                   "objects: ")
            (print-perf-stats p))]
    (loop [attempts 0
           done? false]
      (if done?
        parsed-inst
        (if (< attempts max-attempts)
          (let [{success :ret :as p}
                (my-time (. parsed-inst createAddressSnapshot 1))]
            (when (>= debug-level 1)
              (print "Tried to get consistent address for" num-objects-found
                     "objects. " (if success "Succeeded!" "failed") ": ")
              (print-perf-stats p))
            (recur (inc attempts) success))
          ;; else
          (let [msg (str "Failed to get consistent address snapshot after "
                         max-attempts " attempts.")]
            (throw (ex-info msg
                            {:obj-coll obj-coll
                             :parsed-inst parsed-inst
                             :opts opts
                             :max-attempts max-attempts}))))))))


(defn reachable-objmaps
  "Starting from the given collection of objects, follow the
  references in those objects, and in the objects they reach,
  etc. recursively, until all such reachable objects have been found.
  No locking or synchronization of any kind is done, so the results
  may not be a consistent snapshot of any one point in time.

  Even if all of the objects are immutable, as most Clojure data
  structures are, or no changes are being made to mutable objects
  while this function is executing, it is still possible that Java's
  garbage collector might move objects around in memory while this
  walk is performed.  In this case the addresses of objects returned
  will be correct for that object at the time the object's address was
  determined, but references to that object from other objects may
  have different addresses.

  Calling (System/gc) before calling this function should make a
  garbage collection run less likely to occur while this function
  runs.

  See also consistent-reachable-objmaps, which you may prefer to use
  over this one for the assistance it provides in trying to return a
  consistent set of addresses across all objects."
  [obj-coll opts]
  (let [debug-level (get opts :consistent-reachable-objects-debuglevel 0)
        {^GraphLayout2 parsed-inst :ret :as p}
        (my-time (reachable-objmaps-helper obj-coll opts))
        obj->gpr (.objectsFound parsed-inst)
        gprs (. obj->gpr values)]
    (when (>= debug-level 1)
      (print "found" (.totalCount parsed-inst)
             "objects via reachable-objmaps-helper: ")
      (print-perf-stats p))
    (mapv (fn [^GraphPathRecord2 gpr]
            (let [obj (gpr->java-obj gpr)
                  ;; TBD: Eliminate code gpr->java-obj since I made
                  ;; GraphPathRecord2 obj() method public.
                  arr? (array? obj)
                  ref-arr? (and arr?
                                (not (. (array-element-type obj) isPrimitive)))
                  cd (ClassData/parseClass (class obj))
                  flds (->> cd ClassData->map :fields (remove :is-primitive?)
                            (map :ref-field))
                  ;; TBD: Consider calling both
                  ;; array-elem-name-and-address _and_
                  ;; field-name-and-address for array objects, just in
                  ;; case any Java array objects actually do return
                  ;; fields.
                  refd-objs
                  (into {}
                        (if ref-arr?
                          (map #(array-elem-name-and-snapshot-address % obj
                                                                      obj->gpr)
                               (range (count obj)))
                          (map #(field-name-and-snapshot-address % obj obj->gpr)
                               flds)))
                  size-to-use (workaround-object-size-bytes obj gpr opts)]
              {:address (. gpr address)
               :obj obj
               :size size-to-use
               :distance (. gpr depth)
               :fields refd-objs
               ;; Linear search is fast enough for small obj-coll.
               ;; Could switch to using Java IdentityHashMap for faster
               ;; lookups if obj-coll is expected to be large.
               :starting-object? (boolean (some #(identical? obj %) obj-coll))}))
          gprs)))


;; When I use externals on many data structures, I see objects
;; mentioned with a very large value in SIZE column, and (something
;; else) in TYPE and VALUE columns, (somewhere else) in PATH column.
;; See example below for value #{5 7}.  However, when I use footprint
;; function to see a summary, those lines do not show up in the
;; result.

;; It turns out that those lines appear because between the Java
;; objects in the heap that are shown in a single call to externals,
;; there is memory space between them that is not in one of the
;; objects being shown.  These 'gaps' may include other allocated Java
;; objects not shown, or that space may never have contained a Java
;; object, or perhaps it once did, but has since been deallocated
;; during garbage collection.  This can be annoying if you don't want
;; to see these gaps between the objects shown, only the objects
;; themselves.

;; I also found an answer here:

;; https://stackoverflow.com/questions/30021092/what-is-something-else-in-jol-graphlayout-output


;; Things to check in 'reachable-objmaps' return value before processing
;; further, as sanity check, and documentation of the data structure:

;; Sequence of maps, each containing keys:

;;   :address - value satisfies integer? and >= 0
;;   :obj - no check on value
;;   :size - value satisfies integer? and > 0
;;   :fields - value is map, where keys are strings, and values are same
;;     as :address, or nil.

(defn validate-one-obj [m]
  (cond (not (map? m))
        {:err :non-map
         :data m}

        (not (every? #(contains? m %) [:address :obj :size :fields]))
        {:err :obj-map-missing-required-key
         :data m}
        
        (not (integer? (:address m)))
        {:err :address-not-natural-integer
         :data m}

        (not (>= (:address m) 0))
        {:err :address-not-natural-integer
         :data m}
        
        (not (integer? (:size m)))
        {:err :size-not-positive-integer
         :data m}
        
        (not (> (:size m) 0))
        {:err :size-not-positive-integer
         :data m}
        
        (not (map? (:fields m)))
        {:err :fields-not-map
         :data m}
        
        (not (every? #(string? %) (keys (:fields m))))
        {:err :fields-has-non-string-key
         :data m}
        
        (not (every? #(or (nil? %)
                          (cljol-sentinel-value? %)
                          (and (integer? %) (>= % 0)))
                     (vals (:fields m))))
        {:err :fields-has-val-neither-nil-nor-natural-integer
         :data m}
        
        :else  ;; no problems found
        nil))


;; Additional checks:

;; Most precise check - the graph of objects is reachable via a single
;; 'root' object.

;; Quick-and-dirty check - every object's address appears as the
;; address of at least one field in some object (not the same as most
;; precise check if there are cycles in the graph).  Exactly one
;; object violates this rule (the 'root' object).

;; Every field address is equal to the address of some object (no
;; references 'leave the object set').

(defn object-addresses [g]
  (set (map :address g)))


(defn field-addresses [g]
  (->> g
       ;; Ignore any references leading out of soft, weak, or phantom
       ;; reference objects.
       (remove #(instance? java.lang.ref.Reference (:obj %)))
       (mapcat #(vals (:fields %)))
       (remove #(or (nil? %) (cljol-sentinel-value? %)))
       set))


(defn root-objects [g]
  (let [obj-addresses (object-addresses g)
        fld-addresses (field-addresses g)]
    (set/difference obj-addresses fld-addresses)))


(defn validate-obj-graph [g]
  (or (some validate-one-obj g)
      (let [obj-addresses (object-addresses g)
            fld-addresses (field-addresses g)
            ;;root-objs (root-objects g)
            refs-outside-objs (set/difference fld-addresses obj-addresses)]
        (cond (not (empty? refs-outside-objs))
              {:err :some-refs-outside-object-set
               :err-data refs-outside-objs
               :data g}

              ;; This check fails for sets of objects with cycles in
              ;; the references between them.  That does not typically
              ;; happen with Clojure data structures, which are most
              ;; often acyclic, but does happen reasonably often with
              ;; mutable Java data structures.  Removing the check
              ;; below makes cljol more useful for analyzing their
              ;; structure, too.
;;              (not= 1 (count root-objs))
;;              {:err :not-1-root-object
;;               :err-data root-objs
;;               :data g}

              :else
              nil))))

;; I want to compare two object graphs to see what they have in
;; common, and what is unique to each of them.  This will help measure
;; how effective the path copying technique for persistent data
;; structures is in its actual implementation at sharing parts of the
;; existing data structure with new ones created from them via
;; operations like conj, assoc, disj, etc.

;; When should two objects be considered the same in two object
;; graphs?

;; TBD: Get rid of discussion of :path values below if I go with
;; GraphPathRecord2 implementation that leaves them out.

;; If no compaction/moving of objects has occurred in memory between
;; traversing the object graph of objects X and Y, then an object in
;; X's object graph and in Y's object graph are the same if their
;; addresses are the same.  They should also point at identical
;; objects, and have equal :size and :fields values.  Note that the
;; value of their :path keys _could_ differ (I believe), since the
;; path of an object in X's object graph is relative to X, and in Y's
;; object graph is relative to Y.

;; If compaction/moving of objects _has_ occurred, then objects in X
;; and Y's object graphs could overlap, perhaps at the same address,
;; and yet be different objects.  It would be good to do some sanity
;; checking to try to determine if this happens, to avoid erroneous or
;; misleading results.

;; Effectively the values in the :address fields are a snapshot of
;; what an object's address was at the time the data structure was
;; created, and the object could move afterwards.

;; Sanity checks: For each object, see if its current address is the
;; same or different as that recorded in the :address field.  Even
;; doing such a sanity check can return 'nothing has moved' at the
;; time it is checked, but the next garbage collection can change the
;; answer.  Having a way to notify the user on every garbage
;; collection event may be useful to detect when object moves can
;; occur.

;; I have unit-tested any-object-moved? with some simple objects
;; like (def pv1 [1 2 3 4 5]) and (def pv2 (conj pv1 6)), and seen
;; that most often if I do that followed
;; by (any-object-moved? (reachable-objmaps pv1)) or on pv2, nothing has
;; moved.  If I do (System/gc) and then repeat those calls, typically
;; something will have moved.


(defn object-moved? [objmap]
  (let [obj (:obj objmap)
        addr (:address objmap)
        cur-addr (address-of obj)]
    (if (not= addr cur-addr)
      (assoc objmap :cur-address cur-addr))))


(defn any-object-moved?
  "If any object in object graph 'g' is currently at a different
address than the value of its :address key, return the map for that
object with a new key :cur-address containing the value of the
current address, that is different from the one in the :address key.
If all objects are currently at the same address as given by
their :address key, return nil.

Note that if a garbage collection is performed concurrently with the
execution of this function, it could return nil even though at the
time of return, some objects have moved.  Probably the best way to
minimize this possibility is to avoid doing things that trigger
garbage collection, such as allocating a lot of memory in another
thread."
  [g]
  (some object-moved? g))


(defn two-objects-disjoint?
  [oi1 oi2]
  (or (<= (+ (:address oi1) (:size oi1))
          (:address oi2))
      (<= (+ (:address oi2) (:size oi2))
          (:address oi1))))


(defn two-objects-overlap?
  [oi1 oi2]
;;  (println (format "two-objects-overlap oi1: [%x, %x] oi2: [%x, %x]"
;;                   (:address oi1)
;;                   (+ (:address oi1) (:size oi1))
;;                   (:address oi2)
;;                   (+ (:address oi2) (:size oi2))))
  (if-not (two-objects-disjoint? oi1 oi2)
    [oi1 oi2]))


(defn any-objects-overlap?
  "If two objects in the object graph overlap in memory (i.e. the
  range of bytes from :address to :address + :size - 1 includes a byte
  in commong between two objects), return a set of two of them that
  do.  If no pair of objects overlap, return nil."
  [g]
  (let [by-addr (sort-by :address g)]
    (some (fn [[a b]] (two-objects-overlap? a b))
          (partition 2 1 by-addr))))


(defn object-graph-errors
  "Examine a collection of objmaps, as returned by reachable-objmaps,
  to see if any errors or inconsistencies can be found among them.

  I would expect that the vast majority of time that this function
  returns no errors, the data is consistent with each other.

  However, in the spirit of openness, I would like to mention that
  this function deals with objects and their addresses in memory at
  the time they were 'visited' by the function reachable-objmaps.  If
  a garbage collection were done during the execution of
  reachable-objmaps, and not just any garbage collection, but one
  where two objects with the same class swapped addresses with each
  other, or in general some set of objects all did some permutation of
  exchanging addresses with each other, it seems possible that
  object-graph-errors would not be able to detect that."
  [g]
  (or 
   (validate-obj-graph g)
;;   (if-let [x (any-object-moved? g)]
;;     {:err :object-moved :err-data x :data g})
   (if-let [x (any-objects-overlap? g)]
     {:err :two-objects-overlap :err-data x :data g})))


;; Terminology:

;; A 'javaobj' is one of the Java objects found while calling
;; reachable-objmaps on the object given to reachable-objmaps as a
;; parameter.

;; An 'objmap' is a Clojure map describing exactly one of those
;; javaobjs.  An objmap contains at least the keys returned by
;; reachable-objmaps, but perhaps also more.

;; The value returned by reachable-objmaps is a sequence of objmaps.

;; The function object-graph->ubergraph creates a graph, as
;; implemented by the ubergraph library, and that can be used to draw
;; a figure of the graph using Graphviz, where each node represents a
;; javaobj, and there is an edge from node A to B if javaobj A has a
;; reference to javaobj B.

;; In this graph, I want each javaobj in memory to be represented by
;; exactly one node.  If a javaobj X is referenced from multiple other
;; javaobjs, and they are in the graph, too, then there should be
;; multiple edges into the node for X.

;; Also, in Clojure two values can be equal according to
;; clojure.core/=, but they might be the identical javaobj, or they
;; might be different javaobjs in memory.  The graph drawn here should
;; show separate nodes if they are separate javaobjs in memory.

;; ubergraph lets the caller pick the values used to represent nodes.
;; Because I want different javaobjs in memory to be different nodes
;; in the graph, one way to do that is to use the numeric address of
;; the object in memory to represent a graph node for ubergraph.  If
;; we tried using the javaobj itself to represent a node to ubergraph,
;; I suspect that it would treat any two of them that were
;; clojure.core/= to each other as the same node, even if they were
;; different javaobjs in memory.

;; Null references stored in one javaobj will not be represented in
;; the graph created.  They will show up with the name of the fields
;; and the value "nil" if you use the field-values function to label
;; the nodes in any drawn graphs.

;; One reason I chose ubergraph over the rhizome library is that
;; ubergraph supports multiple parallel directed edges from a node A
;; to another node B in the graph at the same time, where each edge
;; has different attributes, and different labels when drawn using
;; Graphviz.  This is important for representing a javaobj A with
;; multiple references to the same javaobj B, but in different fields
;; of A.


(defn address-decimal [objmap _opts]
  (str (:address objmap)))


(defn address-hex [objmap _opts]
  (format "@%08x" (:address objmap)))


(defn size-bytes [objmap _opts]
  (format "%d bytes" (:size objmap)))


(def default-node-count-min-limit 50)
(def default-total-size-min-limit (* 50 24))


(defn total-size-bytes
  "Return a string describing the number of reachable objects from
  this object, and the total size in bytes of those objects.  Shows ?
  instead of the values if they have not been calculated for this
  node."
  [objmap _opts]
  (let [num (:num-reachable-nodes objmap)
        num-known? (number? num)
        total (:total-size objmap)
        total-known? (number? total)
        complete-statistics? (get objmap :complete-statistics true)]
    (format "%s%s object%s, %s bytes reachable"
            (if complete-statistics? "" "at least ")
            (if num-known? (str num) "?")
            (if num-known?
              (if (> num 1) "s" "")
              "s")
            (if total-known? (str total) "?"))))


(defn uniquely-reachable-info
  [objmap _opts]
  (cond
    (:starting-object? objmap)
    (let [{:keys [my-unique-num-reachable-nodes my-unique-total-size]}
          objmap]
      (format "%s object%s, %s bytes reached only from here"
              my-unique-num-reachable-nodes
              (if (> my-unique-num-reachable-nodes 1)
                "s" "")
              my-unique-total-size))
    
    (contains? objmap :reachable-only-from)
    (format "reached only from one start object %d"
            (:reachable-only-from objmap))
    
    :else "reached from multiple start objects"))


(defn scc-size
  "Return a string describing the number of nodes in the same strongly
  connected component as this object."
  [objmap _opts]
  (let [num (:scc-num-nodes objmap)
        num-known? (number? num)]
    (if (= num 1)
      "this object in no reference cycles"
      (format "%s object%s in same SCC with this one"
              (if num-known? (str num) "?")
              (if num-known?
                (if (> num 1) "s" "")
                "s")))))


(def class-name-prefix-abbreviations
  [
   {:prefix "java.lang." :abbreviation "j.l."}
   {:prefix "java.util.concurrent." :abbreviation "j.u.c."}
   {:prefix "java.util." :abbreviation "j.u."}
   {:prefix "clojure.lang." :abbreviation "c.l."}
   ])


(defn abbreviated-class-name-str [s]
  (if-let [x (some (fn [x] (if (starts-with?-copy s (:prefix x)) x))
                   class-name-prefix-abbreviations)]
    (str (:abbreviation x)
         (subs s (count (:prefix x))))
    s))


(defn class-description [objmap _opts]
  (let [obj (:obj objmap)]
    (if (array? obj)
      (format "array of %d %s" (count obj) (abbreviated-class-name-str
                                            (pr-str (array-element-type obj))))
      (abbreviated-class-name-str (pr-str (class obj))))))


(defn field-values [objmap _opts]
  (let [obj (:obj objmap)
        from-non-strong-obj? (instance? java.lang.ref.Reference obj)
        cd (ClassData->map (ClassData/parseClass (class obj)))
        flds (sort-by :vm-offset (:fields cd))]
    (if (seq flds)
      (str/join "\n"
                (for [fld-info flds]
                  (let [primitive? (:is-primitive? fld-info)
                        val (ofv/obj-field-value
                             obj (:ref-field fld-info)
                             inaccessible-field-val-sentinel)
                        inaccessible? (identical?
                                       val inaccessible-field-val-sentinel)]
                    (format "%d: %s (%s) %s"
                            (:vm-offset fld-info)
                            (:field-name fld-info)
                            (if primitive? (:type-class fld-info) "ref")
                            (cond
                              inaccessible? ".setAccessible failed"
                              primitive? val
                              (nil? val) "nil"
                              from-non-strong-obj? "--not-strong-->"
                              :else "->"))))))))


(defn node-label [objmap opts]
  (str/join "\n"
            (for [f (:node-label-functions opts)]
              (f objmap opts))))


;; ubergraph 0.5.3 already prefixes double quote characters with a
;; backslash (or actually dorothy 0.0.6 does, but ubergraph 0.5.3 uses
;; that), so we should not do so here.

(def graphviz-dot-escape-char-map
  {
   (char 0) "\\\\u0000"
   (char 65534) "\\\\ufffe"
   (char 65535) "\\\\uffff"
   \\ "\\\\"
   })

(defn graphviz-dot-escape-label-string [s]
  (str/escape s graphviz-dot-escape-char-map))


(defn truncate-long-str [s n]
  (if (> (count s) n)
    (str (subs s 0 n) " ...")
    s))

(defn str-with-limit [obj n]
  (truncate-long-str (str obj) n))


(defn array-label [array opts]
  (str-with-limit (vec array) (get opts :max-value-len 50)))


(defn javaobj->str-no-escaping [objmap opts]
  (let [obj (:obj objmap)]
    (if (array? obj)
      (array-label obj opts)
      (str-with-limit obj (get opts :max-value-len 50)))))


(defn javaobj->str [objmap opts]
  (let [s1 (javaobj->str-no-escaping objmap opts)
        s2 (graphviz-dot-escape-label-string s1)]
    s2))


(defn non-realizing-value?
  "Return true if calling 'str' on the object will cause no realizing
  of lazy sequences, or any other kind of effect that modifies the
  object or references between objects.  It is acceptable to return
  true if calculated-once-then-cached values like hash codes are
  modified.  Should definitely return false if calling 'str' would
  cause realization.  This function may return false even if it is
  'safe' to call 'str', to avoid having to detect all of the safe
  cases precisely."
  [x depth-remaining]
  (if (zero? depth-remaining)
    ;; To avoid a stack overflow error, give up after a certain depth,
    ;; and assume the worst, that the overall value could cause
    ;; realization of lazy objects to occur if it was str'ed.
    false
    (or (nil? x)
        (number? x)
        (string? x)
        (keyword? x)
        (symbol? x)
        (boolean? x)
        (bytes? x)
        (inst? x)
        (uri? x)
        (uuid? x)
        (class? x)
        ;; Namespaces .toString is just retrieving its name string
        (instance? clojure.lang.Namespace x)
        (and (array? x)
             (or (. (array-element-type x) isPrimitive)
                 (every? #(non-realizing-value? % (dec depth-remaining))
                         (seq x))))
        (and (or (map? x) (vector? x) (set? x))
             (every? #(non-realizing-value? % (dec depth-remaining))
                     (seq x)))
        (and (list? x)
             (non-realizing-value? (first x) (dec depth-remaining))
             (non-realizing-value? (rest x) (dec depth-remaining))))))


(defn non-realizing-javaobj->str
  "Convert values to strings that should never cause any Clojure lazy
  values to be realized."
  [objmap opts]
  (let [obj (:obj objmap)
        max-depth 10]
    (if (non-realizing-value? obj max-depth)
      (javaobj->str objmap opts)
      "val maybe realizes if str'ed")))


(def all-builtin-node-labels
  [address-hex
   address-decimal
   size-bytes
   total-size-bytes
   uniquely-reachable-info
   scc-size
   class-description
   field-values
   javaobj->str
   non-realizing-javaobj->str])

(def default-node-labels
  [;;address-hex
   ;;address-decimal
   size-bytes
   total-size-bytes
   ;;uniquely-reachable-info
   scc-size
   class-description
   field-values
   ;;javaobj->str
   non-realizing-javaobj->str])


(def default-render-opts {:node-label-functions default-node-labels
                          :max-value-len 50})


(defn add-viz-attributes
  "Return an augmented version of the graph `graph` with GraphViz
  attributes to nodes and edges, such as for nodes:

  :shape :label

  and for edges: :label"
  [graph opts]
  (let [opts (merge default-render-opts opts)]
    (as-> graph gr
      (reduce (fn add-node-attrs [g node]
                (uber/add-attrs
                 g node
                 (merge
                  {:shape "box"
                   :label (node-label (uber/attrs g node) opts)}
                  (if (uber/attr g node :starting-object?)
                    {:style "filled"}))))
              gr (uber/nodes gr))
      (reduce (fn add-edge-attrs [g edge]
                (uber/add-attrs
                 g edge
                 {:label (:field-name (uber/attrs g edge))}))
              gr (uber/edges gr)))))


(defn object-graph->ubergraph
  "Create and return an ubergraph graph data structure from the given
  collection of objmaps.  The return value is a 'multidigraph', 'di'
  for directed because edges represent references from object A to
  object B, stored inside of A.  'multi' because from one object A to
  another object B, there may be multiple references, and we want
  these to be explicitly represented in the result.

  TBD: Document the attributes present on the nodes and edges in the
  graph created."
  [g _opts]
  (-> (uber/multidigraph)
      (uber/add-nodes-with-attrs* (for [objmap g]
                                    [(:address objmap) objmap]))
      (uber/add-edges*
       (for [from-objmap g
             [from-obj-field-name-str to-addr] (:fields from-objmap)
             ;; Do not create edges for null references or to sentinel
             ;; values
             :when (not (or (nil? to-addr)
                            (cljol-sentinel-value? to-addr)))]
         [(:address from-objmap) to-addr
          {:field-name from-obj-field-name-str}]))))


(defn consistent-reachable-objmaps
  "As described in the documentation for reachable-objmaps, it can
  return inconsistent results for the addresses of different objects.
 
  This function calls reachable-objmaps, then checks whether the
  result has any errors according to object-graph-errors, returning
  the consistent obj-graph if no errors were found.

  If errors were found, this function retries a few times,
  calling (System/gc) before each further call to reachable-objmaps,
  in hopes that this will make it less likely that objects will move
  during the execution of reachable-objmaps.

  Note: See object-graph-errors documentation for some notes about a
  possible way that it might not detect any errors, even though the
  set of object data is not actually all consistent with each other."
  [obj-coll opts]
  (let [max-tries (get opts :max-reachable-objects-tries 4)
        debug-level (get opts :consistent-reachable-objects-debuglevel 0)]
    (when (>= debug-level 1)
      (println "reachable-objmaps try 1")
      (pp/pprint (perf/gc-collection-stats))
      (println))
    (loop [{obj-graph :ret :as p} (my-time (reachable-objmaps obj-coll opts))
           num-tries 1]
      (when (>= debug-level 1)
        (print "found" (count obj-graph) "objects on try" num-tries
               "of reachable-objmaps: ")
        (print-perf-stats p))
      (let [{errs :ret :as p} (my-time (object-graph-errors obj-graph))]
        (when (>= debug-level 1)
          (print "checked for errors on try" num-tries ": ")
          (print-perf-stats p)
          (if errs
            (println "Found error of type" (:err errs))
            (println "No errors found.")))
        (if errs
          (if (< num-tries max-tries)
            (do
              (let [p (my-time (System/gc))]
                (when (>= debug-level 1)
                  (print "ran GC: ")
                  (print-perf-stats p)))
              (recur (my-time (reachable-objmaps obj-coll opts))
                     (inc num-tries)))
            (throw
             (ex-info
              (format "reachable-objmaps returned erroneous obj-graphs on all of %d tries"
                      max-tries)
              {:obj-coll obj-coll :errors errs})))
          ;; else
          obj-graph)))))


(defn object-size-bytes [graph node]
  (uber/attr graph node :size))


(defn add-complete-total-size-bytes-node-attr
  "Adds attributes :total-size (in bytes, derived from the
  existing :size attribute on the nodes) and :num-reachable-nodes to
  all nodes of g."
  [g]
  (let [trnw (gr/total-reachable-node-size g object-size-bytes)]
    (reduce (fn [g n]
              (uber/add-attrs g n (trnw n)))
            g (uber/nodes g))))


(defn owner-if-unique
  "Given a map from keys to collections of values, create and return a
  map with the values as keys, and the associated value is the one key
  of the input map for which that value is part of its collection, if
  there is only one.  If there is more than one such key, the
  associated value is ::multiple-owners."
  [m]
  (reduce-kv (fn [acc k coll]
               (reduce (fn [acc one-val]
                         (let [k2 (get acc one-val ::not-found)]
                           (cond
                             (= k2 ::not-found) (assoc acc one-val k)
                             (= k2 k) acc
                             :else (assoc acc
                                          one-val ::multiple-owners))))
                       acc coll))
             {} m))


(defn uniquely-owned-values
  "Given a map from keys to collections of values, create and return a
  map with the same keys, where the value associated with each key is
  a set.  The elements of the set are exactly the subset of the input
  collection associated with the same key, that are _only_ in that
  key's collection, and no other key's collection."
  [m]
  (if (= (count m) 1)
    ;; fast path for easy case
    {:owners (zipmap (first (vals m)) (repeat (first (keys m))))
     :uniquely-owned m}
    (let [owners (owner-if-unique m)
          ret (zipmap (keys m) (repeat #{}))]
      {:owners owners
       :uniquely-owned (reduce-kv (fn [acc val k]
                                    (if (= k ::multiple-owners)
                                      acc
                                      (update acc k conj val)))
                                  ret owners)})))


(defn add-bounded-total-size-bytes-node-attr
  "Adds attributes :total-size (in bytes, derived from the
  existing :size attribute on the nodes) and :num-reachable-nodes to
  all nodes of g.  Do this in a way with bounded searching through the
  graph, which may report smaller than the actual total values of
  reachable nodes, but always reports nodes and total size that do
  exist."
  [g opts]
  (let [debug-level (get opts :bounded-reachable-node-stats-debuglevel 0)
        node-count-min-limit (get opts :node-count-min-limit
                                  default-node-count-min-limit)
        total-size-min-limit (get opts :total-size-min-limit
                                  default-total-size-min-limit)
        {scc-data :ret :as scc-perf} (my-time (gr/scc-graph g))
        {:keys [scc-graph components]} scc-data
        _ (when (>= debug-level 1)
            (print "The scc-graph has" (uber/count-nodes scc-graph) "nodes and"
                   (uber/count-edges scc-graph) "edges, took: ")
            (print-perf-stats scc-perf))
        num-reachable-nodes-in-scc (into {}
                                         (for [sccg-node (uber/nodes scc-graph)]
                                           [sccg-node (count sccg-node)]))
        total-size-in-scc (into {}
                                (for [sccg-node (uber/nodes scc-graph)]
                                  [sccg-node
                                   (reduce + (map #(object-size-bytes g %)
                                                  sccg-node))]))
        sccg->orig-start-node
        (into {} (for [sccg-node (uber/nodes scc-graph)
                       :let [orig-start-nodes
                             (set (filter #(uber/attr g % :starting-object?)
                                          sccg-node))]
                       :when (seq orig-start-nodes)]
                   [sccg-node orig-start-nodes]))

        {[scc-node-stats-trans nodes-reached-trans counts-trans] :ret :as p}
        (my-time
         (reduce (fn [[acc nodes-reached counts] n]
                   (let [start-node? (contains? sccg->orig-start-node n)
                         [node-count-ml total-size-ml]
                         (if start-node?
                           [Double/POSITIVE_INFINITY Double/POSITIVE_INFINITY]
                           [node-count-min-limit total-size-min-limit])
                         [stats cnt] (gr/bounded-reachable-node-stats
                                      scc-graph n num-reachable-nodes-in-scc
                                      total-size-in-scc node-count-ml
                                      total-size-ml start-node?)
                         num (:num-reachable-nodes stats)
                         total (:total-size stats)
                         over-bounds? (and (> num node-count-ml)
                                           (> total total-size-ml))]
                     [(assoc! acc n (-> (dissoc stats :nodes-reached)
                                        (assoc :complete-statistics
                                               (not over-bounds?))))
                      (if start-node?
                        (assoc! nodes-reached n (:nodes-reached stats))
                        nodes-reached)
                      (conj! counts cnt)]))
                 [(transient {}) (transient {}) (transient [])]
                 (uber/nodes scc-graph)))
        scc-node-stats (persistent! scc-node-stats-trans)
        nodes-reached (persistent! nodes-reached-trans)
        counts (persistent! counts-trans)
        {:keys [owners uniquely-owned] :as tmp1} (uniquely-owned-values nodes-reached)]
    (when (>= debug-level 1)
      (print "Calculated num-reachable-nodes and total-size"
             " for scc-graph in: ")
      (print-perf-stats p)
      (println "frequencies of different number of nodes DFS traversed:")
      (pp/pprint (into (sorted-map) (frequencies counts)))
      (println)
      ;;(println "nodes-reached=")
      ;;(pp/pprint nodes-reached)
      ;;(println "uniquely-owned=")
      ;;(pp/pprint uniquely-owned)
      ;;(println "owners=")
      ;;(pp/pprint owners)
      ;;(println "tmp1=")
      ;;(pp/pprint tmp1)
      )
    (reduce (fn [g sccg-node]
              (let [stat-attrs
                    (merge
                     (assoc (scc-node-stats sccg-node)
                            :scc-num-nodes (count sccg-node))
                     (if (contains? sccg->orig-start-node sccg-node)
                       {:my-unique-num-reachable-nodes
                        (reduce + (map num-reachable-nodes-in-scc
                                       (uniquely-owned sccg-node)))
                        :my-unique-total-size
                        (reduce + (map total-size-in-scc
                                       (uniquely-owned sccg-node)))})
                     (let [owner (owners sccg-node)
                           orig-graph-start-nodes
                           (if (not= owner ::multiple-owners)
                             (sccg->orig-start-node owner)
                             #{})]
                       ;; See Note 2
                       (if (= (count orig-graph-start-nodes) 1)
                         {:reachable-only-from (first
                                                orig-graph-start-nodes)})))]
                (reduce (fn [g g-node]
                          (uber/add-attrs g g-node stat-attrs))
                        g sccg-node)))
            g (uber/nodes scc-graph))))

;; Note 2

;; Even if an sccg node has only one 'owner' in the scc-graph, there
;; might be more than one node in the original graph in that sccg
;; node.  If so, all of the nodes in scc-graph that are reachable only
;; from the 'owner', are all reachable from multiple original nodes,
;; and should not be marked as :reachable-only-from in the original
;; graph.


(defn add-bounded-total-size-bytes-node-attr2
  "Adds attributes :total-size (in bytes, derived from the
  existing :size attribute on the nodes) and :num-reachable-nodes to
  all nodes of g.  Do this in a way with bounded searching through the
  graph, which may report smaller than the actual total values of
  reachable nodes, but always reports nodes and total size that do
  exist."
  [g opts]
  (gr/bounded-reachable-node-stats2 g object-size-bytes opts))


(defn find-node-for-obj
  "Given a graph g returned by graph-of-reachable-objects, find and
  return a node for the object that is identical the object 'obj', or
  nil if there is none."
  [g obj]
  (first (filter (fn [node]
                   (identical? obj (uber/attr g node :obj)))
                 (uber/nodes g))))


;; This function is not used right now to add :distance attributes to
;; nodes, because the JOL library calculates distances from the root
;; nodes already, and this is redundant.  Keeping the source code
;; around as an example of using ualg/shortest-path and ualg/path-to

#_(defn add-shortest-path-distances
  [g obj-coll]
  (let [start-nodes (mapv #(find-node-for-obj g %) obj-coll)
        spaths (ualg/shortest-path g {:start-nodes start-nodes})]
    (reduce (fn [g n]
              (uber/add-attr g n :distance (:cost (ualg/path-to spaths n))))
            g (uber/nodes g))))


(defn graph-of-reachable-objects [obj-coll opts]
  (let [debug-level (get opts :graph-of-reachable-objects-debuglevel 0)
        calc-tot-size (get opts :calculate-total-size-node-attribute :bounded)
        objmaps (consistent-reachable-objmaps obj-coll opts)
        {g :ret :as p} (my-time (object-graph->ubergraph objmaps opts))
        _ (when (>= debug-level 1)
            (print "converted" (count objmaps) "objmaps into ubergraph with"
                   (uber/count-edges g) "edges: ")
            (print-perf-stats p))
        g (if (contains? #{:complete :bounded :bounded2}
                         calc-tot-size)
            (let [{g :ret :as p}
                  (my-time
                   (case calc-tot-size
                     :complete (add-complete-total-size-bytes-node-attr g)
                     :bounded (add-bounded-total-size-bytes-node-attr g opts)
                     :bounded2 (add-bounded-total-size-bytes-node-attr2 g opts)
                     ))]
              (when (>= debug-level 1)
                (print "calculated" calc-tot-size "total sizes: ")
                (print-perf-stats p))
              g)
            (do (println "skipping calculation of total size")
                g))
        {g :ret :as p} (my-time (add-viz-attributes g opts))
        _ (when (>= debug-level 1)
            (print "added graphviz attributes: ")
            (print-perf-stats p))]
    g))


(defn add-attributes-by-reachability [g attr-maps]
  (let [from-multiple-attrs (-> (filter #(:from-multiple %) attr-maps)
                                first :attrs)
        from-none-attrs (-> (filter #(:from-none %) attr-maps)
                            first :attrs)
        start-objs (->> (filter #(contains? % :only-from) attr-maps)
                        (map (fn [attr-map]
                               (assoc
                                attr-map :node
                                (find-node-for-obj g (:only-from attr-map))))))
        start-node-map (group-by :node start-objs)
        reachable-node-map-from-sets (gr/reachable-nodes g)
        reachable-node-map (into {}
                                 (for [[from-nodes to-nodes]
                                       reachable-node-map-from-sets
                                       from-node from-nodes]
                                   [from-node to-nodes]))
        nodes-reachable-from
        (->> (into []
                   (for [start-node (map :node start-objs)
                         reachable-node (reachable-node-map start-node)]
                     {:start-node start-node :reachable-node reachable-node}))
             (group-by :reachable-node))]
    (reduce (fn [g n]
              (uber/add-attrs
               g n
               (let [num-starts-reached-from (count (nodes-reachable-from n))]
                 (cond
                   (zero? num-starts-reached-from) from-none-attrs
                   (= 1 num-starts-reached-from)
                   (let [start-node (-> (nodes-reachable-from n)
                                        first :start-node)]
                     (get-in start-node-map [start-node 0 :attrs]))
                   :else from-multiple-attrs))))
            g (uber/nodes g))))


(defn graph-summary [g opts]
  (let [size-bytes-freq (frequencies (map #(uber/attr g % :size)
                                          (uber/nodes g)))
        size-breakdown (->> (for [[size cnt] size-bytes-freq]
                              {:size-bytes size
                               :num-objects cnt
                               :total-size (* size cnt)})
                            (sort-by :size-bytes))
        total-size-bytes (reduce + (for [x size-breakdown] (:total-size x)))]
    (println (uber/count-nodes g) "objects")
    (println (uber/count-edges g) "references between them")
    (println total-size-bytes "bytes total in all objects")
    (println (if (ualg/dag? g)
               "no cycles"
               "has at least one cycle"))
    (when (some #{:all :wcc-details} (opts :summary-options))
      (let [;; TBD: The node collections that
            ;; ualg/connected-components returns can in some cases
            ;; contain duplicate nodes.  I do not know why this
            ;; happens.  For now, make sets out of them to eliminate
            ;; those.
            {weakly-connected-components :ret
             :as wcc-perf} (my-time (map set (ualg/connected-components g)))]
        (print (count weakly-connected-components) "weakly connected components"
               "found in: ")
        (print-perf-stats wcc-perf)
        (println "number of nodes in all weakly connected components,")
        (println "from most to fewest nodes:")
        (println (sort > (map count weakly-connected-components)))))
    (when (some #{:all :scc-details} (opts :summary-options))
      (let [{scc-data :ret :as scc-perf} (my-time (gr/scc-graph g))
            {:keys [scc-graph node->scc-set]} scc-data
            scc-components (set (vals node->scc-set))
            scc-component-sizes-sorted (sort > (map count scc-components))]
        (print "The scc-graph has" (uber/count-nodes scc-graph) "nodes and"
               (uber/count-edges scc-graph) "edges, took: ")
        (print-perf-stats scc-perf)
        (println "The largest size strongly connected components, at most 10:")
        (pp/pprint (take 10 scc-component-sizes-sorted))))
    (when (some #{:all :size-breakdown} (opts :summary-options))
      (println "number of objects of each size in bytes:")
      (pp/pprint size-breakdown))
    (when (some #{:all :class-breakdown} (opts :summary-options))
      (println "number and size of objects of each class:")
      (pp/pprint (->> (for [[cls nodes] (group-by #(class (uber/attr g % :obj))
                                                  (uber/nodes g))]
                        {:total-size (reduce + (for [n nodes]
                                                 (uber/attr g n :size)))
                         :num-objects (count nodes)
                         :class (abbreviated-class-name-str (pr-str cls))})
                      (sort-by :total-size)))
      (println))
    (println (count (filter #(= 0 (uber/out-degree g %)) (uber/nodes g)))
             "leaf objects (no references to other objects)")
    (println (count (filter #(= 0 (uber/in-degree g %)) (uber/nodes g)))
             "root nodes (no reference to them from other objects _in this graph_)")
    
    (when (some #{:all :node-degree-breakdown} (opts :summary-options))
      (println "number of objects of each in-degree (# of references to it):")
      (pp/pprint (->> (for [[k v] (frequencies (map #(uber/in-degree g %)
                                                    (uber/nodes g)))]
                        {:in-degree k :num-objects v})
                      (sort-by :in-degree)))
      (println "number of objects of each out-degree (# of references from it):")
      (pp/pprint (->> (for [[k v] (frequencies (map #(uber/out-degree g %)
                                                    (uber/nodes g)))]
                        {:out-degree k :num-objects v})
                      (sort-by :out-degree))))
    (when (some #{:all :distance-breakdown} (opts :summary-options))
      (let [nodes-by-distance (group-by #(uber/attr g % :distance)
                                        (uber/nodes g))
            node-stats-by-distance
            (->> (for [[k nodes] nodes-by-distance]
                   {:distance k
                    :num-objects (count nodes)
                    :total-size (reduce + (for [n nodes]
                                            (uber/attr g n :size)))})
                 (sort-by :distance))]
        (println "Number and total size of objects at each distance from a starting object:")
        (pp/pprint node-stats-by-distance)))))


(defn sum
  ([obj-coll]
   (sum obj-coll {}))
  ([obj-coll opts]
   (let [g (graph-of-reachable-objects obj-coll opts)]
     (graph-summary g opts)
     g)))


(def cljol-node-keys-to-remove
  [:address :size :total-size :num-reachable-nodes :complete-statistics
   :obj :fields :distance])


(defn keep-only-dot-safe-attrs
  "Remove ubergraph node and/or edge attributes created by cljol that
  have nothing to do with Graphviz dot file attributes, and may
  interfere with creation of a valid dot file, e.g. especially the
  value of :obj when converted to a string can contain characters not
  supported in dot files.  Intended to be called on a graph before
  passing the resulting graph to ubergraph.core/viz-graph"
  [g]
  (reduce (fn [g n]
            (uber/set-attrs g n
                            (apply dissoc (uber/attrs g n)
                                   cljol-node-keys-to-remove)))
          g (uber/nodes g)))


(defn view-graph
  ([g]
   (view-graph g {}))
  ([g opts]
   ;; I have found that I get an error from the dorothy library if I
   ;; pass all of opts to uber/viz-graph.  I am not sure what can be
   ;; passed and what not, but it does appear that
   ;; the :node-label-functions key and value are mentioned in the
   ;; exception.
   (-> (keep-only-dot-safe-attrs g)
       (uber/viz-graph (merge {:rankdir :LR} opts)))
   ;; uber/viz-graph returns contents of dot file as a string when
   ;; saving to a file in a format other than dot, which can be very
   ;; long.  Return nil always as a convenience to avoid seeing the
   ;; string printed in a REPL session.
   nil))


(defn view
  ([obj-coll]
   (view obj-coll {}))
  ([obj-coll opts]
   (let [g (graph-of-reachable-objects obj-coll opts)]
     (view-graph g))))


(defn write-drawing-file
  ([obj-coll fname format]
   (write-drawing-file obj-coll fname format {}))
  ([obj-coll fname format opts]
   (let [g (graph-of-reachable-objects obj-coll opts)]
     (view-graph g {:save {:filename fname :format (keyword format)}}))))


(defn write-dot-file
  ([obj-coll fname]
   (write-drawing-file obj-coll fname :dot {}))
  ([obj-coll fname opts]
   (write-drawing-file obj-coll fname :dot opts)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

(load-file "src/cljol/dig9.clj")

(do

(in-ns 'user)
(require '[cljol.dig9 :as d])
(in-ns 'cljol.dig9)
(use 'clojure.pprint)
(use 'clojure.repl)
(def opts-for-ubergraph
  (merge default-render-opts
         {:node-label-functions [address-hex
                                 size-bytes
                                 total-size-bytes
                                 class-description
                                 field-values
                                 javaobj->str
                                 ]}))
(def opts opts-for-ubergraph)

)


(def opts-only-address-on-nodes
  (merge default-render-opts
         {:node-label-functions [address-hex
                                 ;;size-bytes
                                 ;;total-size-bytes
                                 ;;class-description
                                 ;;field-values
                                 ;;javaobj->str
                                 ]}))
(def opts default-render-opts)
(def opts opts-only-address-on-nodes)
(def opts (update-in opts-for-ubergraph [:node-label-functions]
                     conj total-size-bytes))

(def o1 (vec (range 5)))
(def o2 (conj o1 5))
(d/view [o1 o2])
(def opts {:node-label-functions [address-decimal
           size-bytes
           total-size-bytes
           class-description
           field-values
                                  javaobj->str]
           :max-value-len 50})

(def o1 (vec (range 30)))
(def o2 (conj o1 30))

(def o1 (vec (range 32)))
(def o2 (conj o1 32))

(def o1 (set (range 32)))
(def o2 (conj o1 32))

(def g (sum [o1 o2] opts))

(def g2 (add-attributes-by-reachability
         g
         [
          {:only-from o1
           :attrs {:color "red"}}
          {:only-from o2
           :attrs {:color "green"}}
          {:from-multiple true
           :attrs {:color "blue"}}
          {:from-none true
           :attrs {:color "gray"}}
          ]))

(uber/pprint g2)
(view-graph g2)
(view-graph g2 {:save {:filename "g2.pdf" :format :pdf}})

(def e1 *e)
(use 'clojure.repl)
(pst e1 100)

(type (System/getProperties))
(count (System/getProperties))

(do
(def props1 (java.util.Properties.))
(. props1 setProperty "prop1" "val1")
(. props1 setProperty "prop2" "val2")
)
props1

(defn fib-fn [a b]
  (lazy-seq (cons a (fib-fn b (+ a b)))))
(def fib-seq (fib-fn 0 1))
(def o1 (fib-fn 0 1))
(def opts-no-value-str
  (merge default-render-opts
         {:node-label-functions [address-hex
                                 size-bytes
                                 total-size-bytes
                                 class-description
                                 field-values
                                 ;;javaobj->str
                                 ]}))
(def opts opts-no-value-str)
(pprint opts)

(def o1 (let [x :a y :b] {x y y x}))
(def o1 props1)
(def o1 (System/getProperties))
(def o1 (vec (range 1000)))
(def g1 (sum [o1] opts))
(uber/viz-graph (keep-only-dot-safe-attrs g1) {:rankdir :LR})
(view-graph g1)
(write-dot-file [o1] "o1.dot" opts)
(write-drawing-file [o1] "o1.pdf" :pdf opts)

(def o2 (mapv char "a\"b"))
(def g1 (sum [o1 o2] opts))
(find-node-for-obj g1 o1)

(def spaths (ualg/shortest-path
             g1 {:start-nodes (mapv #(find-node-for-obj g1 %) [o1])
                 :traverse true
                 :max-cost 2}))
(count spaths)
(pprint spaths)

(uber/viz-graph (keep-only-dot-safe-attrs g1) {:rankdir :LR})
(uber/viz-graph (keep-only-dot-safe-attrs g2) {:rankdir :LR})
(graph-summary g2 opts)

(def g2 (uber/remove-nodes* g1 (gr/leaf-nodes g1)))
(def g2 (gr/induced-subgraph g1 (filter #(<= (uber/attr g % :distance) 2)
                                        (uber/nodes g1))))

(def wcc (ualg/connected-components g1))
(require '[clojure.data :as data])
(data/diff (set (first wcc)) (set (uber/nodes g1)))
(count (first wcc))
(count (set (first wcc)))

(def o1 (mapv char "a\"b"))
(def o1 props1)
(def o1 (System/getProperties))
(def o1 (let [x :a y :b] {x y y x}))
(def o1 fib-seq)
(def o1 [fib-seq (nthrest fib-seq 1) (nthrest fib-seq 2)
         (nthrest fib-seq 3) (nthrest fib-seq 4)])
(def o1 (vec (range 100)))
(def o1 (into (vector-of :long) (range 100)))
(def o1 (long-array (range 100)))
;; see sharing of data between two similar vectors
(def o1 (let [v1 (vec (range 50))] (list v1 (conj v1 50))))
(def o1 (let [v1 (into (vector-of :long) (range 100))]
          (list v1 (conj v1 100))))
(view o1 opts)

(def g1 (graph-of-reachable-objects [o1] opts))
(def gt1 (add-total-size-bytes-node-attr g1))
(def trnw (gr/total-reachable-node-size gt1 object-size-bytes))
(pprint trnw)
(uber/pprint gt1)
(uber/viz-graph (add-viz-attributes gt1 opts) {:rankdir :LR})

(find-obj g1 o1)
(def s1 (ualg/shortest-path g1 {:start-nodes [(find-obj g1 o1)]}))
(type s1)
(pprint (for [n (uber/nodes g1)]
          [n (:cost (ualg/path-to s1 n))]))
(def s1 (ualg/shortest-path g1 {:start-nodes [28997820360]}))
(def nbd1 (into (sorted-map)
                (group-by :cost
                          (for [n (uber/nodes g1)]
                            (ualg/path-to s1 n)))))
(pprint nbd1)

(uber/pprint g1)
(view o1 opts)
(write-dot-file o1 "o1.dot" opts)
(write-drawing-file o1 "o1.pdf" :pdf opts)

(uber/count-nodes g1)
(uber/count-edges g1)
(ualg/scc g1)
(map set (ualg/scc g1))
(frequencies (map count (ualg/scc g1)))
(dissoc (group-by count (ualg/scc g1)) 1)

(uber/nodes g1)
(uber/edges g1)
(uber/has-node? g1 1)
(uber/has-node? g1 28998500328)
(uber/has-node? g1 {:a 1 :b 2})

(def g1 (uber/multidigraph 1 2 3 4
                           [1 2] [3 1] [2 3] [3 4]))
(def g1 (uber/multidigraph 1 2 3 4
                           [1 2] [1 3] [2 3] [3 4]))
(def g1 (uber/multidigraph 1 2 3 4
                           [1 2] [1 3] [2 3] [3 4] [4 1]))
(def g1 (uber/multidigraph 1 2 3 4
                                 [1 3] [2 3] [3 4]))
(def g1 (uber/multidigraph 1 2 3 4
                           [1 2] [3 4] [4 3]))
(uber/pprint g1)
(uber/viz-graph (keep-only-dot-safe-attrs g1) {:auto-label true})
(dag-reachable-nodes g1)
(reachable-nodes g1)

(def sccg-and-nodemap (scc-graph g1))
(uber/pprint (:scc-graph sccg-and-nodemap))
(uber/viz-graph (:scc-graph sccg-and-nodemap) {:auto-label true})

(ualg/topsort g1)
(ualg/topsort (:scc-graph sccg-and-nodemap))


(do
(def s1 (apply str (map char (range   0  32))))
(def s2 (apply str (map char (range  32  64))))
(def s3 (apply str (map char (range  64  96))))
(def s4 (apply str (map char (range  96 128))))
(def s5 (apply str (map char (range 128 160))))
(def s6 (apply str (map char (range 160 192))))
(def s7 (apply str (map char (range 192 224))))
(def s8 (apply str (map char (range 224 256))))
(def s9 (apply str (map char (range 256 271))))
(def s10 (apply str (map char (range (- 65536 32) 65536))))
(def s11 (apply str (map char (range (- 0xd800 16) (+ 0xd800 16)))))
(def s11 (apply str (map char (range (- 0xd800 16) (+ 0xd800 16)))))
(def s12 (apply str (map char (range (- 0xe000 16) (+ 0xe000 16)))))
)

(let [extra-opts {:max-value-len 1024}
      opts-for-ubergraph (merge opts-for-ubergraph extra-opts)]
  (doseq [[s name-prefix]
          [
           [s1 "s1"]
           [s2 "s2"]
           [s3 "s3"]
           [s4 "s4"]
           [s5 "s5"]
           [s6 "s6"]
           [s7 "s7"]
           [s8 "s8"]
           [s9 "s9"]
           [s10 "s10"]
           [s11 "s11"]
           [s12 "s12"]
           ]]
    ;;(def g2 (graph-of-reachable-objects [s] opts-for-ubergraph))
    (write-dot-file s (str name-prefix ".dot") opts-for-ubergraph)
    (write-drawing-file s (str name-prefix ".pdf") :pdf opts-for-ubergraph)
    ))

(pprint (into (sorted-map) graphviz-dot-escape-char-map))

(def e1 *e)
(use 'clojure.repl)
(pst e1 100)
(pprint (Throwable->map e1))


(def o1 (mapv char "a\"b"))
(def e1 (consistent-reachable-objmaps [o1]))
(pprint e1)
(def opts {})
(def u1 (object-graph->ubergraph e1 opts))
(uber/pprint u1)
(def t1 (add-total-size-bytes-node-attr u1))
(uber/pprint t1)
(def s1 (add-shortest-path-distances t1 [o1]))
(uber/pprint s1)
;;      (add-viz-attributes opts)


(def m1 (let [x :a y :b] {x y y x}))
(def m1 (mapv char "a\"b\\c|d{e}f"))
(def m1 (mapv char "a\"b"))
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(def e1 (consistent-reachable-objmaps [m1]))
(object-graph-errors e1)
(count e1)
(pprint e1)

(def g1 (graph-of-reachable-objects [m1] opts-for-ubergraph))
(uber/pprint g1)
(uber/viz-graph g1 {:rankdir :LR})
(write-dot-file m1 "m1.dot" opts-for-ubergraph)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; This code uses the library: ubergraph 0.5.3
;; which in turn uses the library: dorothy 0.0.6

;; That version of dorothy when given node and edge attributes with
;; the key :label and a value that is a string, will do these
;; replacements in function dorothy.core/escape-quotes:

;;   "  replaced with   \"

;; That version of ubergraph, before passing label strings to dorothy,
;; will do these replacements in function
;; ubergraph.core/escape-backslashes, but only if you request that it
;; create node and edge labels for you with the :auto-label true
;; option.  Without that option, this replacement is not performed on
;; labels you provide to ubergraph.

;;   \  replaced with   \\

;; rhizome 0.2.9 makes these replacements in label strings, in
;; function rhizome.dot/escape-string:

;; Replace any of these single characters:
;;   \  |  {  }  "
;; with that character prepended with a backslash character.


;; What does dot support in label strings?  I am not 100% sure, but
;; rhizome has been working pretty well so far with what I have thrown
;; at it.

;; One way to use ubergraph 0.5.3 and dorothy 0.0.6 without changing
;; them, and get the rhizome set of characters escaped exactly once
;; each, is, before calling ubergraph to add the labels, to replace
;; the strings that rhizome does, _except for_ ".  Then let dorothy
;; replace the ".

;; Dorothy has a bug - it only escapes double quote characters in
;; labels, but not any of the characters below, so we do it here

(def escapable-characters-not-handled-by-dorothy-0-0-6 "\\|{}")

(defn escape-label-string
  "Escape characters that are significant for the dot format."
  [s]
  (reduce
   #(str/replace %1 (str %2) (str "\\" %2))
   s
   escapable-characters-not-handled-by-dorothy-0-0-6))

;;(require '[rhizome.dot :as dot])

(defn javaobj->str-dot-escaping [objmap opts]
  (let [s1 (javaobj->str objmap opts)
        s2 (escape-label-string s1)]
    ;;(println) (print "s1=") (pr s1)
    ;;(println) (print "s2=") (pr s2)
    ;;(println)
    s2))

(def opts-for-ubergraph (merge default-render-opts
                               {:node-label-functions [address-hex
                                                       size-bytes
                                                       class-description
                                                       ;;field-values
                                                       javaobj->str-dot-escaping
                                                       ]}))

(def s1 (apply str (map char (range 20))))
s1
(apropos "readably")
(doc *print-readably*)
(binding [*print-readably* false]
  (println "\nprintln:")
  (println s1)
  (println "\npr:")
  (pr s1)
  (println "\nret value is from pr-str:")
  (pr-str s1)
  )
(apropos "print-dup")
(doc *print-dup*)
(binding [*print-dup* true]
  (println s1)
  (pr s1)
  )

(pr-str s1)
(mapv int (take 20 s1))
(mapv int (take 20 (pr-str s1)))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(view m1 opts)
(write-dot-file m1 "m1.dot" opts)

(def my-map {:a 1 :b 2 :c 3})
(view my-map opts)

(def my-map2 {:a 1 :b 2 :c 3 :d 4 :e 5 :f 6 :g 8})
(view my-map2 opts)
(write-dot-file my-map2 "my-map2.dot" opts)

(def my-map3 {:a [1 2] :b "d\u1234of" :c #{:a :b} :d {:e 10 :f 11}})
(view my-map3 opts)
(write-dot-file my-map3 "my-map3.dot" opts)

;; Versions:

;; sun.arch.data.model 64
;; 
;; Ubuntu 18.04.2
;; OpenJDK 11.0.3
;; Clojure 1.5.1 - but Clojure version should not matter for this,
;; since this is just Java strings.

;; selected key/val pairs from (System/getProperties):
;; "os.arch" "amd64",
;; "java.version" "11.0.3",
;; "java.runtime.version" "11.0.3+7-Ubuntu-1ubuntu218.04.1",
;; "java.vm.name" "OpenJDK 64-Bit Server VM",
;; "sun.arch.data.model" "64",
;; "java.vm.compressedOopsMode" "32-bit",

;; All strings had a 24-byte java.lang.String object, which pointed at
;; another Java object which was an array of primitive bytes.
;; The length of the array of bytes always equaled the length of the string.
;; The size of the array object varied as follows:

;; B=byte array length  A=array object size
;; -------------------  -------------------
;;  0                   16
;;  1- 8                24
;;  9-16                32
;; 17-24                40
;; 25-32                48
;; 33-40                56

;; 42-48                64
;; 50-56                72
;; 58-64                80
;; 66-72                88

;; All of the above are consistent with the formula:
;; A = 16 + 8*ceiling(B/8)

;; Every String with this version of the JVM takes either L bytes to
;; store its characters, if those characters all fit within 8 bits, or
;; 2*L bytes if they do not.

;; The array object is 16 bytes on top of that, and the String object
;; is 24 bytes on top of that.

(def strings1 (into [] (map #(subs "1234567890abcdefghijklmnopqrstuvwxyz" 0 %)
                            (range 37))))
(view strings1 opts)

(def s2 (apply str (map char (range 1234 (+ 1234 36)))))
(def strings2 (into [] (map #(subs s2 0 %)
                            (range 37))))
(view strings2 opts)

(defn node-label-no-str-calls [javaobj]
  "")
(def opts-no-label (merge opts {:node-label-functions node-label-no-str-calls}))

(def lazy-seq1 (map (fn [x] (println "generating elem for x=" x) (inc x))
                    (range 100)))
(nth lazy-seq1 0)
(nth lazy-seq1 5)
(nth lazy-seq1 30)

;; Interesting!  Self-loop for optimal memory efficiency!
(def lazy2 (repeat 42))
(view lazy2 opts-no-label)
(take 1 lazy2)
(take 10 lazy2)

;; Generates a linked list of a Repeat object, each with a count 1
;; less than the one before.
(def lazy3 (repeat 10 "a"))
(view lazy3 opts-no-label)
(take 1 lazy3)
(take 4 lazy3)

(def lazy4 (seq (vec (range 100))))
(view lazy4 opts-no-label)
(take 1 lazy4)
(take 4 lazy4)

(def lazy-seq1 (map inc (range 8)))
;; I do not know if there is a straightforward way to look at
;; lazy-seq1's object graph without realizing it, at least not with
;; the default options for cljol.  I am pretty sure that calling `str`
;; to generate a string representation of a value forces the lazy
;; sequence to be realized.
;;(write-dot-file lazy-seq1 "lazy-seq1-unrealized.dot" opts)
(view lazy-seq1 opts-no-label)
(write-dot-file lazy-seq1 "lazy-seq1-unrealized.dot" opts-no-label)
(println (first lazy-seq1))
(view lazy-seq1 opts-no-label)
(view (doall lazy-seq1) opts)
(write-dot-file (doall lazy-seq1) "lazy-seq1-realized.dot" opts)
(write-dot-file (doall (map inc (range 100))) "lazy-seq2-realized.dot" opts)

;; These functions have optimizations that handle chunked sequences
;; given to them specially, and preserve the chunked-ness in their
;; results.
;; map, filter, remove, keep

(write-dot-file (doall (filter even? (range 100))) "lazy-seq3-realized.dot" opts)

;; This gives an unchunked lazy sequence:
(view (doall (distinct (range 10))) opts)
(write-dot-file (doall (distinct (range 10))) "lazy-seq4-realized.dot" opts)

(def arr1 (int-array (range 50)))
(view arr1 opts)
(def arr2 (int-array (range 500)))
(view arr2 opts)
(def arr3 (int-array (range 501)))
(view arr3 opts)
(def arr4 (long-array (range 501)))
(view arr4 opts)
(def s1 "The quick brown fox jumped over the lazy lazy frickin' dog.")
(view s1 opts)
(count s1)
(def s2 "The quick br\u1234wn fox jumped over the lazy lazy frickin' dog.")
(view s2 opts)
(count s2)

(def m1b (let [x "a" y "b"] {x y y x :c x}))
(view m1b opts)
(def m1c {"abc" "def" (str "de" "f") (str "a" "bc") :c "abc"})
(view m1c opts)
(def m1d {"abc" "d\u1234f" (str "d\u1234" "f") (str "a" "bc") :c "abc"})
(view m1d opts)

(def s1e "The qu\u1234ck brown fox jumped over the lazy dog.")
(def m1e [s1e (subs s1e 1 10) (subs s1e 3 12) (subs s1e 15 20)])
(view m1e opts)
(write-dot-file m1e "substrings.dot" opts)

(def m2 (vec (range 70)))
(def m2 (vec (range 1000)))
(def e2 (consistent-reachable-objmaps [m2]))
(def err2 (object-graph-errors e2))
(write-dot-file m2 "m2.dot" opts)

(def m2 (vec (range 35)))
(write-dot-file m2 "m2.dot" opts)

(defn int-map [n]
  (into {} (map (fn [i] [(* 2 i) (inc (* 2 i))])
                (range n))))
(def m5 (int-map 5))
(def m50 (int-map 50))
;; TBD: older style opts
(def opts {:node-label-fn #(str-with-limit % 50)})
(view m5 opts)
(view m50 opts)
(write-dot-file m5 "m5.dot" opts)
(write-dot-file m50 "m50.dot" opts)

(def m5 (int-map 5))
(def m50 (int-map 50))

(defn externals [x]
  (let [parsed-inst (GraphLayout/parseInstance x)]
    (.toPrintable parsed-inst)))

(externals m1)
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(type p1)
(def a1 (.addresses p1))
(type a1)
(count a1)
(type (.iterator a1))
(def i1 (iterator-seq (.iterator a1)))
i1
(print (.toPrintable p1))

(def o1 (System/getProperties))
(view o1 opts)
(write-dot-file o1 "getproperties.dot" opts)
(def e1 *e)
(def ed1 (ex-data e1))
(keys ed1)
(type (:errors ed1))
(keys (:errors ed1))
(:err (:errors ed1))

(def o2 (atom [1 2]))
(view o2 opts)
(write-dot-file o2 "atom.dot" opts)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; The images produced by GraphLayout/toImage method seem fairly
;; useless to me, from these two examples.
(def m1 (let [x :a y :b] {x y y x}))
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(.toImage p1 "m1.png")

(def m2 (vec (range 1000)))
(def p2 (GraphLayout/parseInstance (object-array [m2])))
(.toImage p2 "m2.png")

(print (class-layout->str ""))
(print (class-layout->str "1"))
(print (class-layout->str "1234"))
(print (class-layout->str "12345678"))
(print (class-layout->str "123456789"))

(print (class-layout->str (int 1)))
(print (class-layout->str 1))
(print (class-layout->str (double 1)))
(print (class-layout->str (int-array [])))
(print (class-layout->str (int-array [0])))
(print (class-layout->str (int-array [0 1 2 3 4 5 6 7])))

(import '(org.openjdk.jol.info ClassData FieldData))

(def cd (ClassData/parseInstance "a"))
(def flds (. cd fields))
(def flds (.fields cd))
flds
(count flds)
(map type flds)

(def f1 (first flds))
f1
(pprint (->> flds (map FieldData->map) (sort-by :vm-offset)))

(def s1 "a")
(def cdm1 (ClassData->map (ClassData/parseInstance s1)))
(pprint cdm1)
(pprint (ClassData->map (ClassData/parseInstance s1)))
(pprint (ClassData->map (ClassData/parseInstance (int-array [1 2 3]))))


(def fld0 (-> cdm1 :fields (nth 0) :ref-field))
(. fld0 setAccessible true)
(.get fld0 s1)

(defn obj->map [obj]
  (let [cdm (ClassData->map (ClassData/parseInstance obj))]
    (update-in cdm [:fields]
               (fn [field-info-seq]
                 (map (fn [field-info]
                        (assoc field-info
                               :field-value (ofv/obj-field-value
                                             obj (:ref-field field-info)
                                             inaccessible-field-val-sentinel)))
                      field-info-seq)))))

(def s1 "a")
(print (class-layout->str s1))
(pprint (obj->map s1))

(def s2 "\u1234")
(print (class-layout->str s2))
(pprint (obj->map s2))

(import '(org.openjdk.jol.info ClassLayout GraphLayout))
(defn class-layout->str2 [obj layouter]
  (let [cls (class obj)
        parsed-cls (ClassLayout/parseClass cls layouter)]
    (.toPrintable parsed-cls obj)))

(def l1 (org.openjdk.jol.layouters.CurrentLayouter.))
(print (class-layout->str2 s1 l1))

(def dmc (org.openjdk.jol.datamodel.CurrentDataModel.))
(def l2 (org.openjdk.jol.layouters.RawLayouter. dmc))
(print (class-layout->str2 s1 l2))

(def dmc (org.openjdk.jol.datamodel.CurrentDataModel.))
(def l3 (org.openjdk.jol.layouters.HotSpotLayouter. dmc))
(print (class-layout->str2 s1 l3))

  )
