(ns cljol.dig9
  (:import (java.lang.reflect Field Modifier))
  (:import (org.openjdk.jol.info ClassLayout GraphLayout
                                 ClassData FieldData))
  (:import (org.openjdk.jol.vm VM))
  (:import (java.lang.reflect Method))
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [ubergraph.core :as uber]
            [ubergraph.alg :as ualg]
            [cljol.graph :as gr]))


(set! *warn-on-reflection* true)


;; bounded-count, starts-with? copied from Clojure's implementation,
;; to enable this code to be used with slightly older versions of
;; Clojure than 1.9.0.

(defn bounded-count-copy
  "If coll is counted? returns its count, else will count at most the first n
  elements of coll using its seq"
  {:added "1.9"}
  [n coll]
  (if (counted? coll)
    (count coll)
    (loop [i 0 s (seq coll)]
      (if (and s (< i n))
        (recur (inc i) (next s))
        i))))

(defn starts-with?-copy
  "True if s starts with substr."
  {:added "1.8"}
  [^CharSequence s ^String substr]
  (.startsWith (.toString s) substr))


(defn map-keys [f m]
  (into (empty m)
        (for [[k v] m] [(f k) v])))


(defn map-vals [f m]
  (into (empty m)
        (for [[k v] m] [k (f v)])))


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


(defn FieldData->map [^FieldData fd]
  {:field-name (.name fd)
   :type-class (.typeClass fd)
   :host-class (.hostClass fd)
   :is-contended? (.isContended fd)
   :contended-group (.contendedGroup fd)
   :ref-field (.refField fd)
   :vm-offset (.vmOffset fd)})


;; Note that much of the information about a class returned by
;; ClassData->map can also be obtained via the Java reflection API in
;; the java.lang.reflect.* classes.  At least one kind of information
;; that can be obtained from ClassData->map that cannot be gotten from
;; Java's reflection API is the :vm-offset of fields.

(defn ClassData->map [^ClassData cd]
  (merge
   {:class-name (.name cd)
    :superclass (.superClass cd)
    :class-hierarchy (.classHierarchy cd)
    :is-array? (.isArray cd)
    :is-contended? (.isContended cd)
    :fields (->> (.fields cd)
                 (map FieldData->map)
                 (sort-by :vm-offset))}
   (if (.isArray cd)
     ;; info specific to array objects
     {:array-component-type (.arrayComponentType cd)
      :array-length (.arrayLength cd)}
     ;; info specific to non-array objects
     {;; oops are "Ordinary Object Pointers" according to this article:
      ;; https://www.baeldung.com/jvm-compressed-oops
      :oops-count (.oopsCount cd)})))


(defn array? [x]
  (if-not (nil? x)
    (. (class x) isArray)))


(defn ^Class array-element-type [arr]
  (. (class arr) getComponentType))


(defn superclasses [cls]
  (take-while identity (iterate (memfn ^Class getSuperclass) cls)))


(defn all-fields
  "Return all fields of the class 'cls', and its superclasses"
  [cls]
  (mapcat #(.getDeclaredFields ^Class %)
          (superclasses cls)))


(defn per-inst-ref-field? [^Field fld]
  (and (not (. (. fld getType) isPrimitive))
       (not (Modifier/isStatic (. fld getModifiers)))))


(defn per-instance-reference-fields [cls]
  (filter per-inst-ref-field?
          (all-fields cls)))


(defn address-of [obj]
  (. (VM/current) addressOf obj))


(defn field-name-and-address [^Field fld obj]
  (. fld setAccessible true)
  (let [fld-val (. fld get obj)]
    [(. fld getName)
     (if (nil? fld-val)
       nil
       (address-of fld-val))]))


;; Several Java interop calls in the next few lines of code cause
;; reflection warnings that are not easily eliminated via type hints,
;; at least not in any way that I know of.  Disable reflection
;; warnings for this short section of code.

(set! *warn-on-reflection* false)

(defn array-elem-name-and-address [idx array-obj]
  (let [array-idx-val (aget array-obj idx)]
    [(str "[" idx "]")
     (if (nil? array-idx-val)
       nil
       (address-of array-idx-val))]))


;; obj() is a private method of class GraphPathRecord.  Use some Java
;; hackery to call it anyway, as long as the security policy in place
;; allows us to.

(def gpr-class (Class/forName "org.openjdk.jol.info.GraphPathRecord"))
(def gpr-obj-method (.getDeclaredMethod gpr-class "obj" nil))
(.setAccessible gpr-obj-method true)

(def empty-obj-array (object-array []))

(defn gpr->java-obj [gpr]
  (.invoke gpr-obj-method gpr empty-obj-array))

(set! *warn-on-reflection* true)



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
  [obj-coll]
  (let [parsed-inst (GraphLayout/parseInstance (object-array obj-coll))
        addresses (.addresses parsed-inst)]
    (map (fn [addr]
           (let [gpr (. parsed-inst record addr)
                 obj (gpr->java-obj gpr)
                 arr? (array? obj)
                 ref-arr? (and arr?
                               (not (. (array-element-type obj)
                                       isPrimitive)))
                 flds (per-instance-reference-fields (class obj))]
             {:address addr
              :obj obj
              :size (. gpr size)
              :path (. gpr path)
              :fields (if ref-arr?
                        (into {} (map #(array-elem-name-and-address % obj)
                                      (range (count obj))))
                        (into {} (map #(field-name-and-address % obj) flds)))}))
         addresses)))


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
;;   :path - value is string
;;   :fields - value is map, where keys are strings, and values are same
;;     as :address, or nil.

(defn validate-one-obj [m]
  (cond (not (map? m))
        {:err :non-map
         :data m}

        (not (every? #(contains? m %) [:address :obj :size :path :fields]))
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
        
        (not (string? (:path m)))
        {:err :path-not-string
         :data m}
        
        (not (map? (:fields m)))
        {:err :fields-not-map
         :data m}
        
        (not (every? #(string? %) (keys (:fields m))))
        {:err :fields-has-non-string-key
         :data m}
        
        (not (every? #(or (nil? %)
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
       (mapcat #(vals (:fields %)))
       (remove nil?)
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
   (if-let [x (any-object-moved? g)]
     {:err :object-moved :err-data x :data g})
   (validate-obj-graph g)
   (if-let [x (any-objects-overlap? g)]
     {:err :two-objects-overlap :err-data x :data g})))


(defn first-if-exactly-one [v]
  (if (= 1 (count v))
    (first v)
    (throw (ex-info (format "Expected a sequence to have exactly 1 element, but it had %d (or more)"
                            (bounded-count-copy v 10))
                    {:bad-sequence v}))))


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

;; TBD: Right now any null references stored in one javaobj will not
;; be represented in the graph created.  It might be nice some time to
;; have an option for that.  In that case, it would probably make the
;; graph easier to read if each nil/null reference had its own
;; separate arrow to its own separate node for each such null
;; reference, otherwise there could be many of them throughout the
;; graph all pointing to a common null node.

;; One reason I chose ubergraph over the rhizome library is that
;; ubergraph supports multiple parallel directed edges from a node A
;; to another node B in the graph at the same time, where each edge
;; has different attributes, and different labels when drawn using
;; Graphviz.  This is important for representing a javaobj A with
;; multiple references to the same javaobj B, but in different fields
;; of A.


(defn address-hex [objmap opts]
  (format "@%08x" (:address objmap)))


(defn size-bytes [objmap opts]
  (format "%d bytes" (:size objmap)))


(defn total-size-bytes [objmap opts]
  (format "%d object%s, %d bytes reachable"
          (:num-reachable-nodes objmap)
          (if (> (:num-reachable-nodes objmap) 1) "s" "")
          (:total-size objmap)))


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


(defn class-description [objmap opts]
  (let [obj (:obj objmap)]
    (if (array? obj)
      (format "array of %d %s" (count obj) (abbreviated-class-name-str
                                            (pr-str (array-element-type obj))))
      (abbreviated-class-name-str (pr-str (class obj))))))


(defn obj-field-value [obj ^Field fld]
  (. fld setAccessible true)
  (.get fld obj))


(defn primitive-class-name? [name-str]
  (contains? #{"boolean" "byte" "short" "char" "int" "float" "long" "double"}
             name-str))


(defn field-values [objmap opts]
  (let [obj (:obj objmap)
        cd (ClassData->map (ClassData/parseClass (class obj)))
        flds (sort-by :vm-offset (:fields cd))]
    (if (seq flds)
      (str/join "\n"
                (for [fld-info flds]
                  (let [primitive? (primitive-class-name? (:type-class fld-info))
                        val (obj-field-value obj (:ref-field fld-info))]
                    (format "%d: %s (%s) %s"
                            (:vm-offset fld-info)
                            (:field-name fld-info)
                            (if primitive? (:type-class fld-info) "ref")
                            (if primitive?
                              val
                              (if (nil? val) "nil" "->")))))))))


(defn path-to-object [objmap opts]
  (str "path=" (:path objmap)))


(defn node-label [objmap opts]
  (str/join "\n"
            (for [f (:node-label-functions opts)]
              (f objmap opts))))


;; ubergraph 0.5.3 already prefixes double quote characters with a
;; backslash (or actually dorothy 0.0.6 does, but ubergraph 0.5.3 uses
;; that), so we should not do so here, so do not do that here.

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


(def all-builtin-node-labels
  [address-hex
   size-bytes
   total-size-bytes
   class-description
   field-values
   path-to-object
   javaobj->str])

(def default-node-labels
  [;;address-hex
   size-bytes
   total-size-bytes
   class-description
   ;;field-values
   ;;path-to-object
   javaobj->str])

(def default-node-labels-except-value
  [;;address-hex
   size-bytes
   total-size-bytes
   class-description
   ;;field-values
   ;;path-to-object
   ;;javaobj->str
   ])


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
                 {:shape "box"
                  :label (node-label (:objmap (uber/attrs g node)) opts)}))
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
  [g opts]
  (-> (uber/multidigraph)
      (uber/add-nodes-with-attrs* (for [objmap g]
                                    [(:address objmap) {:objmap objmap}]))
      (uber/add-edges*
       (for [from-objmap g
             [from-obj-field-name-str to-addr] (:fields from-objmap)
             ;; Do not create edges for null references
             :when (not (nil? to-addr))]
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
  [obj-coll]
  (let [max-tries 4]
    (loop [obj-graph (reachable-objmaps obj-coll)
           num-tries 1]
      (let [errs (object-graph-errors obj-graph)]
        (if errs
          (if (< num-tries max-tries)
            (do
              (System/gc)
              (recur (reachable-objmaps obj-coll) (inc num-tries)))
            (throw
             (ex-info
              (format "reachable-objmaps returned erroneous obj-graphs on all of %d tries"
                      max-tries)
              {:obj-coll obj-coll :errors errs})))
          ;; else
          obj-graph)))))


(defn object-size-bytes [graph node]
  (:size (:objmap (uber/attrs graph node))))


(defn add-total-size-bytes-node-attr
  "Adds attributes :total-size (in bytes, derived from the
  existing :size attribute on the nodes) and :num-reachable-nodes to
  all nodes of g.  All such attributes are actually inside of the
  one :objmap attribute that is directly on the ubergraph nodes."
  [g]
  (let [trnw (gr/total-reachable-node-size g object-size-bytes)]
    (reduce (fn [g n]
              (let [objmap (uber/attr g n :objmap)]
                (uber/add-attr g n
                               :objmap (merge objmap (trnw n)))))
            g (uber/nodes g))))


(defn graph-of-reachable-objects [obj-coll opts]
  (-> (consistent-reachable-objmaps obj-coll)
      (object-graph->ubergraph opts)
      (add-total-size-bytes-node-attr)
      (add-viz-attributes opts)))


(defn graph-summary [g start-node-coll]
  (let [size-bytes-freq (frequencies
                         (map (fn [n]
                                (-> (uber/attr g n :objmap) :size))
                              (uber/nodes g)))
        total-size-bytes (reduce + (for [[size count] size-bytes-freq]
                                     (* size count)))
        ;; TBD: The node collections that ualg/connected-components
        ;; returns can in some cases contain duplicate nodes.  I do
        ;; not know why, but just make sets out of them for now to
        ;; eliminate those.
        weakly-connected-components (map set (ualg/connected-components g))
        spaths (ualg/shortest-path g {:start-nodes start-node-coll})
        nodes-by-distance (group-by :cost
                                    (for [n (uber/nodes g)]
                                      (ualg/path-to spaths n)))
        num-nodes-by-distance (into (sorted-map)
                                    (for [[k v] nodes-by-distance]
                                      [k (count v)]))]

    (println (uber/count-nodes g) "objects")
    (println (uber/count-edges g) "references between them")
    (println total-size-bytes "bytes total in all objects")
    (println (if (ualg/dag? g)
               "no cycles"
               "has at least one cycle"))
    (println (count weakly-connected-components) "weakly connected components")
    (println "number of nodes in all weakly connected components,")
    (println "from most to fewest nodes:")
    (println (sort > (map count weakly-connected-components)))
    (println "map where keys are object size in bytes,")
    (println "values are number of objects with that size:")
    (pp/pprint (into (sorted-map) size-bytes-freq))
    (println)
    (println (count (filter #(= 0 (uber/out-degree g %)) (uber/nodes g)))
             "leaf objects (no references to other objects)")
    (println (count (filter #(= 0 (uber/in-degree g %)) (uber/nodes g)))
             "root nodes (no reference to them from other objects _in this graph_)")

    (println "map where keys are in-degree of an object,")
    (println "values are number of objects with that in-degree:")
    (pp/pprint (into (sorted-map)
                     (frequencies (map #(uber/in-degree g %) (uber/nodes g)))))

    (println "map where keys are out-degree of an object,")
    (println "values are number of objects with that out-degree:")
    (pp/pprint (into (sorted-map)
                     (frequencies (map #(uber/out-degree g %) (uber/nodes g)))))

    ;; TBD: Add stats for total size of all objects at each distance
    (println "map where keys are distance of an object from a start node,")
    (println "values are number of objects with that distance:")
    (pp/pprint num-nodes-by-distance)
    ))


(defn view
  ([obj]
   (view obj {}))
  ([obj opts]
   (let [g (graph-of-reachable-objects [obj] opts)]
     ;; I have found that I get an error from the dorothy library if I
     ;; pass all of opts to uber/viz-graph.  I am not sure what can be
     ;; passed and what not, but it does appear that
     ;; the :node-label-functions key and value are mentioned in the
     ;; exception.
     (uber/viz-graph g
                     ;;(merge {:rankdir :LR} opts)
                     {:rankdir :LR}
                     ))))


(defn write-drawing-file
  ([obj fname format]
   (write-drawing-file obj fname format {}))
  ([obj fname format opts]
   (let [g (graph-of-reachable-objects [obj] opts)]
     (uber/viz-graph g {:rankdir :LR
                        :save {:filename fname :format (keyword format)}}))
   ;; uber/viz-graph returns contents of dot file as a string, which
   ;; can be very long.  Return nil always as a convenience to avoid
   ;; seeing the string printed in a REPL session.
   nil))


(defn write-dot-file
  ([obj fname]
   (write-drawing-file obj fname :dot {}))
  ([obj fname opts]
   (write-drawing-file obj fname :dot opts)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

(load-file "src/cljol/dig9.clj")

(do

(in-ns 'user)
(require '[cljol.dig9 :as d])
(in-ns 'cljol.dig9)
(use 'clojure.pprint)

)

(def opts-for-ubergraph
  (merge default-render-opts
         {:node-label-functions [address-hex
                                 size-bytes
                                 class-description
                                 field-values
                                 ;;path-to-object
                                 javaobj->str
                                 ]}))
(def opts-only-address-on-nodes
  (merge default-render-opts
         {:node-label-functions [address-hex
                                 ;;size-bytes
                                 ;;class-description
                                 ;;field-values
                                 ;;path-to-object
                                 ;;javaobj->str
                                 ]}))
(def opts default-render-opts)
(def opts opts-for-ubergraph)
(def opts opts-only-address-on-nodes)
(def opts (update-in opts-for-ubergraph [:node-label-functions]
                     conj total-size-bytes))

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
                                 ;;path-to-object
                                 ;;javaobj->str
                                 ]}))
(def opts opts-no-value-str)
(pprint opts)

(defn find-node-for-obj [g obj]
  (first (filter (fn [node]
                   (identical? obj (:obj (uber/attr g node :objmap))))
                 (uber/nodes g))))

(defn sum [obj-coll opts]
  (let [g (graph-of-reachable-objects obj-coll opts)]
    (graph-summary g (mapv #(find-node-for-obj g %) obj-coll))
    g))
(def o2 (mapv char "a\"b"))
(def g1 (sum [o1 o2] opts))
(find-node-for-obj g1 o1)

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
(uber/viz-graph g1 {:auto-label true})
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
                                                       ;;path-to-object
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

;; TBD: Find examples that show difference between chunked and
;; unchunked sequences.

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

(defn obj-field-value [obj fld]
  (. fld setAccessible true)
  (.get fld obj))

(defn obj->map [obj]
  (let [cdm (ClassData->map (ClassData/parseInstance obj))]
    (update-in cdm [:fields]
               (fn [field-info-seq]
                 (map (fn [field-info]
                        (assoc field-info
                               :field-value (obj-field-value
                                             obj (:ref-field field-info))))
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

(do

(import '(org.openjdk.jol.info ClassData FieldData))
(require '[cljol.dig9 :as d])
(require '[clojure.string :as str])
(require '[clojure.reflect :as ref])

(defn per-class-fields-via-java-reflect-api [kls]
  (->> (ref/type-reflect kls :ancestors true)
       :members
       (filter (fn [mem]
                 (and (contains? mem :type)
                      (not (contains? (:flags mem) :static)))))))

;; Explnanation for why I am leaving $<digit> alone, but replacing
;; other occurrences of "$" with ".":

;; I saw some classes in io.github.classgraph itself that had fields
;; whose type names ended with "$" and a single digit.

;; My attempt to avoid replacing those is admittedly hackish and I do
;; not understand why the "$<digit>" occur in the first place.

(defn convert-jra-type-name [type-name-str]
  (-> type-name-str
      (str/replace #"[$]([^0-9])" ".$1")
      (str/replace "<>" "[]")))

(convert-jra-type-name "io.github.classgraph.ClassGraph$ScanResultProcessor")

(defn per-class-field-names-and-types-via-java-reflect-api [kls]
  (->> (per-class-fields-via-java-reflect-api kls)
       (map (fn [m]
              {:field-name (str (:name m))
               :type-str (convert-jra-type-name (str (:type m)))}))
       set))

(defn per-class-field-names-and-types-via-jol [kls]
  (->> (ClassData/parseClass kls)
       ClassData->map
       :fields
       (map (fn [m]
              {:field-name (:field-name m)
               :type-str (:type-class m)}))
       set))

(require '[clojure.data :as data])

(defn compare-fields-jra-vs-jol [kls]
  (let [d (data/diff
           (per-class-field-names-and-types-via-java-reflect-api kls)
           (per-class-field-names-and-types-via-jol kls))
        [unique-to-jra unique-to-jol common] d]
    (if (and (nil? unique-to-jra)
             (nil? unique-to-jol))
      :same
      d)))
)

(do

(import '(io.github.classgraph ClassGraph ClassInfo))
;;(def scan-result (.. (ClassGraph.) verbose enableAllInfo scan))
(def scan-result (.. (ClassGraph.) enableAllInfo scan))
(type scan-result)
(def allkls (into {} (.getAllClassesAsMap scan-result)))
(type allkls)
(count allkls)

(def diffs0
  (->> allkls
       (mapv (fn [[class-name-str class-info]]
               (let [kls (. class-info loadClass)]
                 {:class-name-str class-name-str
                  :kls kls
                  :diffs (compare-fields-jra-vs-jol kls)})))))

(def diffs1
  (->> diffs0
       (remove #(= :same (:diffs %)))))

)

(count allkls)
(count diffs0)
(count diffs1)


;; Separate out differences that are only because JOL
;; found "__methodImplCache" and java reflection API did not.  Note:
;; Later I found out that the reason for those differences was that
;; JOL returned all per-instance fields of a class, whereas
;; clojure.reflect/type-reflect was only returning the fields declared
;; directly in that class, ignoring any fields defined in
;; superclasses.  When I added the `:ancestors true` options to the
;; clojure.reflect/type-reflect call, they returned the same lists of
;; per-instance fields (probably for all fields, but I only did
;; careful checking on the per-instance fields).

(defn only-diff-is-method-impl-cache? [diff]
  (and (vector? diff)
       (= 3 (count diff))
       (nil? (nth diff 0))
       (nil? (nth diff 2))
       (let [x (nth diff 1)]
         (and (set? x)
              (= 1 (count x))
              (map? (first x))
              (= "__methodImplCache" (:field-name (first x)))))))

(pprint diffs1)
(def d (nth diffs1 1))
(pprint (:diffs d))
(only-diff-is-method-impl-cache? (:diffs d))

(def diffs2 (group-by #(only-diff-is-method-impl-cache? (:diffs %)) diffs1))

(count diffs2)
(keys diffs2)
(count (get diffs2 false))
;; 870
(count (get diffs2 true))
;; 2297
(pprint (nth (get diffs2 false) 100))

(def kls (Class/forName "clojure.lang.ExceptionInfo"))
(pprint (per-class-field-names-and-types-via-java-reflect-api kls))
(pprint (ref/type-reflect kls :ancestors true))
(pprint (per-class-field-names-and-types-via-jol kls))

(def kls (Class/forName "java.lang.RuntimeException"))
(def kls (Class/forName "java.lang.Exception"))
(def kls (Class/forName "java.lang.Throwable"))
(def kls (Class/forName "java.lang.IExceptionInfo"))



(pprint (compare-fields-jra-vs-jol (class "a")))
(pprint (compare-fields-jra-vs-jol (class 1)))
(pprint (compare-fields-jra-vs-jol (class (class 1))))

(def f1 (per-class-fields-via-java-reflect-api (class "a")))
(def f1 (per-class-fields-via-java-reflect-api (class 1)))
(def f1 (per-class-fields-via-java-reflect-api (class (class 1))))
(pprint f1)

(def f1 (per-class-field-names-and-types-via-java-reflect-api (class "a")))
(def f1 (per-class-field-names-and-types-via-java-reflect-api (class 1)))
(def f1 (per-class-field-names-and-types-via-java-reflect-api (class (class 1))))

(def f2 (per-class-field-names-and-types-via-jol (class "a")))
(def f2 (per-class-field-names-and-types-via-jol (class 1)))
(def f2 (per-class-field-names-and-types-via-jol (class (class 1))))
(pprint f2)

(require '[clojure.reflect :as ref])
(apropos "reflect")
(def sr (ref/type-reflect (class "a")))
(class sr)
(pprint sr)
(keys sr)
(:bases sr)
(:flags sr)
(keys (first (:members sr)))
(pprint (frequencies (map keys (:members sr))))
(def maybe-flds (filter #(contains? % :type) (:members sr)))
(count maybe-flds)
(pprint maybe-flds)
(def per-class-flds (filter #(contains? (:flags %) :static) maybe-flds))
(count per-class-flds)
(def per-instance-flds (remove #(contains? (:flags %) :static) maybe-flds))
(count per-instance-flds)
(pprint per-instance-flds)

(load-file "src/cljol/dig9.clj")

  )
