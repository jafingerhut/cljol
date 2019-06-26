(ns cljol.dig9
  (:import (java.lang.reflect Field Modifier))
  (:import (org.openjdk.jol.info ClassLayout
                                 GraphLayout))
  (:import (org.openjdk.jol.vm VM))
  (:import (java.lang.reflect Method))
  (:require [clojure.set :as set]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [rhizome.viz :as viz]
            [rhizome.dot :as dot]))


(set! *warn-on-reflection* true)


(defn map-keys [f m]
  (into (empty m)
        (for [[k v] m] [(f k) v])))


(defn map-vals [f m]
  (into (empty m)
        (for [[k v] m] [k (f v)])))


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



(defn myexternals [x]
  (let [parsed-inst (GraphLayout/parseInstance (object-array [x]))
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


;; Things to check in 'myexternals' return value before processing
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
            root-objs (root-objects g)
            refs-outside-objs (set/difference fld-addresses obj-addresses)]
        (cond (not (empty? refs-outside-objs))
              {:err :some-refs-outside-object-set
               :err-data refs-outside-objs
               :data g}

              (not= 1 (count root-objs))
              {:err :not-1-root-object
               :err-data root-objs
               :data g}

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
;; by (any-object-moved? (myexternals pv1)) or on pv2, nothing has
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


(defn validate-obj-graph [g]
  (or (some validate-one-obj g)
      (let [obj-addresses (object-addresses g)
            fld-addresses (field-addresses g)
            root-objs (root-objects g)
            refs-outside-objs (set/difference fld-addresses obj-addresses)]
        (cond (not (empty? refs-outside-objs))
              {:err :some-refs-outside-object-set
               :err-data refs-outside-objs
               :data g}

              (not= 1 (count root-objs))
              {:err :not-1-root-object
               :err-data root-objs
               :data g}

              :else
              nil))))


(defn object-graph-errors [g]
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
                            (bounded-count v 10))
                    {:bad-sequence v}))))


;; Terminology:

;; A 'javaobj' is one of the Java objects found while calling
;; myexternals on the object given to myexternals as a parameter.

;; An 'objmap' is a Clojure map describing exactly one of those
;; javaobjs.  An objmap contains at lesat the keys returned by
;; myexternals, but perhaps also more.

;; The value returned by myexternals is a sequence of objmaps.

;; render-object-graph creates data structures and functions needed in
;; order to call the rhizome library, using it to draw a figure of the
;; graph where each node represents a javaobj, and there is an edge
;; from node A to B if javaobj A has a reference to javaobj B.

;; In this graph, I want each javaobj in memory to represented by
;; exactly one node.  If a javaobj X is referenced from multiple other
;; javaobjs, and they are in the graph, too, then there should be
;; multiple edges into the node for X.

;; Also, in Clojure two values can be equal according to
;; clojure.core/=, but they might be the identical javaobj, or they
;; might be different javaobjs in memory.  The graph drawn here should
;; show separate nodes if they are separate javaobjs in memory.

;; rhizome lets the caller pick the values used to represent nodes.
;; Because I want different javaobjs in memory to be different nodes
;; in the graph, one way to do that is to use the numeric address of
;; the object in memory to represent a graph node for rhizome.  If we
;; tried using the javaobj itself to represent a node to rhizome, I
;; suspect that it would treat any two of them that were
;; clojure.core/= to each other as the same node, even if they were
;; different javaobjs in memory.

;; TBD: Right now any null references stored in one javaobj will not
;; be represented in the graph created.  It might be nice some time to
;; have an option for that.  In that case, it would probably make the
;; graph easier to read if each nil/null reference had its own
;; separate arrow to its own separate node for each such null
;; reference, otherwise there could be many of them throughout the
;; graph all pointing to a common null node.

;; TBD: It seems that rhizome will let you send it a graph where there
;; are multiple edges from a node A to another node B, and it can draw
;; them separately, but if you want separate drawing properties for
;; each edge independently, when rhizome calls the function provided
;; to it as the value of the key :edge->descriptor, each such function
;; call will have the same arguments A B as the call for all edges
;; from A to B.  There is nothing that rhizome uses to distinguish one
;; such call from another.

;; I am considering implementing what I would call a somewhat hackish
;; approach to work around this property of rhizome, which is to
;; maintain state and count the number of times that :edge->descriptor
;; is called for each pair of nodes [A B], and use that count to
;; return different values for each such A-to-B edge.  That only works
;; if rhizome calls that function exactly once for each edge in the
;; graph, but I think that might be what it does.

;; A cleaner way would be if rhizome's representation of a graph used
;; explicit values to represent each edge, and included that value in
;; its calls to the :edge->descriptor function.  That would be a
;; noticeable change to the rhizome API that would break other
;; people's use of it, if it were made in a future version of rhizome,
;; unless it were somehow an option.


;; Create a map where the keys are ordered pairs of graph nodes [A B].
;; The values are maps representing information about all of the edges
;; from node A to B.

;; In those maps, the value assoc'd with key :num-edges is the number
;; of such parallel edges.

;; The value assoc'd with key :edges is a vector of one map per edge,
;; where the map contains properties descrbing that edge.

;; Examples: If there is only one, that map will look like:
;; {:num-edges 1
;;  :edges [{:field-name-str "x"}]}

;; If there are two such parallel edges, that map will look like:
;; {:num-edges 2
;;  :edges [{:field-name-str "x"} {:field-name-str "y"}]}

(defn make-addr->objmap [g javaobj->label-str]
  (->> (group-by :address g)
       (map-vals first-if-exactly-one)
       (map-vals (fn [objmap]
                   (assoc objmap
                          :label (str (javaobj->label-str (:obj objmap)))
                          :class (class (:obj objmap)))))))

(defn make-edge-map [addr->objmap]
  (->> (for [[from-addr from-obj-map] addr->objmap
             [from-obj-field-name-str to-addr] (:fields from-obj-map)
             :when (not (nil? to-addr))]
         {:node-pair [from-addr to-addr]
          :edge-properties {:field-name-str from-obj-field-name-str}})
       (group-by :node-pair)
       (map-vals #(mapv :edge-properties %))))


(defn new-edge-call-count [m node-pair]
  (assoc m node-pair (inc (get m node-pair 0))))

(defn update-edge-call-count [edge-call-count-atom node1 node2]
  (let [node-pair [node1 node2]
        new-count-map (swap! edge-call-count-atom new-edge-call-count node-pair)]
    (get new-count-map node-pair)))


(defn node-label [objmap opts]
  (let [obj (:obj objmap)
        address-str (if (:label-node-with-address? opts)
                      (format "@%08x\n" (:address objmap))
                      "")
        path-str (if (:label-node-with-path? opts)
                   (str "path=" (:path objmap) "\n")
                   "")]
    (format "%s%d bytes\n%s%s"
            address-str
            (:size objmap)
            path-str
            (:label objmap))))


(defn throw-edge-cb-exception [addr1 addr2 edge-map edge-call-count]
  (throw (ex-info
          (format (str "rhizome called :edge->descriptor fn"
                       " with args %s %s, but no such edge was specified")
                  addr1 addr2)
          {:edge-map edge-map
           :addr1 addr1
           :addr2 addr2
           :edge-call-count edge-call-count})))


(defn truncate-long-str [s n]
  (if (> (count s) n)
    (str (subs s 0 n) " ...")
    s))

(defn str-with-limit [obj n]
  (truncate-long-str (str obj) n))


(defn default-array-label [array]
  (format "array of %d %s" (count array)
          (pr-str (array-element-type array))))

(defn default-javaobj->str [javaobj]
  (if (array? javaobj)
    (default-array-label javaobj)
    (str-with-limit javaobj 50)))


(defn render-object-graph [g opts]
  (let [opts (merge {:render-method :view
                     :node-label-fn default-javaobj->str
                     :label-node-with-address? false
                     :label-node-with-path? false}
                    opts)
        javaobj->label-str (:node-label-fn opts)
        addr->objmap (make-addr->objmap g javaobj->label-str)
        edge-map (make-edge-map addr->objmap)
        graph (into {}
                    (for [[addr objmap] addr->objmap]
                      [addr
                       (->> (vals (:fields objmap))
                            (remove nil?)
                            vec)]))
        node-desc (fn [addr]
                    {:shape "box"
                     :label (node-label (addr->objmap addr) opts)})
        edge-call-count (atom {})
        edge-desc (fn edge-description [addr1 addr2]
                    (let [num-calls (update-edge-call-count edge-call-count
                                                            addr1 addr2)
                          node-pair [addr1 addr2]
                          _ (if-not (contains? edge-map node-pair)
                              (throw-edge-cb-exception addr1 addr2 edge-map
                                                       @edge-call-count))
                          edges-info (edge-map node-pair)
                          edge-info (edges-info (dec num-calls))]
;;                      (println (format "dbg: addr1=%d addr2=%d num-calls=%d edge-info=%s"
;;                                       addr1 addr2 num-calls edge-info))
                      {:label (:field-name-str edge-info)}))]
    ;; TBD: I do not know how to achieve it, but it would be nice if
    ;; array elements were at least usually rendered in order of
    ;; index.  I suspect that putting them in that order into the
    ;; GraphViz .dot file would achieve that in many cases, if not
    ;; all, but not sure how to call and/or modify view-graph and
    ;; graph->dot functions to achieve that.
    (apply (case (get opts :render-method)
             :view viz/view-graph
             :dot-str dot/graph->dot)
           [(keys graph) graph :node->descriptor node-desc
            :edge->descriptor edge-desc
            :vertical? false])))


(defn gen-valid-obj-graph
  "Using myexternals to generate an object graph for a large data
  structure can result in an invalid obj-graph data structure.  For
  example, if a GC occurs during the execution of myexternals, or any
  object is moved in memory for any reason while myexternals is
  executing, the resulting obj-graph data is significantly less
  useful.
 
  This function calls myexternals, checks whether the result has no
  errors according to object-graph-errors, and returns the valid
  obj-graph if there are no errors.

  If there were errors, it retries a few times, calling (System/gc)
  before each further call to myexternals, in hopes that this will
  make it less likely that objects will move during the execution of
  myexternals."
  [obj]
  (let [max-tries 4]
    (loop [obj-graph (myexternals obj)
           num-tries 1]
      (let [errs (object-graph-errors obj-graph)]
        (if errs
          (if (< num-tries max-tries)
            (do
              (System/gc)
              (recur (myexternals obj) (inc num-tries)))
            (throw
             (ex-info
              (format "myexternals returned erroneous obj-graphs on all of %d tries"
                      max-tries)
              {:obj obj :errors errs})))
          ;; else
          obj-graph)))))


(defn view
  ([obj]
   (view obj {}))
  ([obj opts]
   (render-object-graph (gen-valid-obj-graph obj)
                        (merge opts {:render-method :view}))))


(defn write-dot-file
  ([obj fname]
   (write-dot-file obj fname {}))
  ([obj fname opts]
   (with-open [wrtr (io/writer fname)]
     (let [s (render-object-graph (gen-valid-obj-graph obj)
                                  (merge opts {:render-method :dot-str}))]
       (spit wrtr s)))))



(comment

(import '(java.lang.reflect Method))
(import '(org.openjdk.jol.info ClassLayout GraphLayout))
(import '(org.openjdk.jol.vm VM))
(require '[cljol.dig9 :as d])

(def m1 (let [x :a y :b] {x y y x}))
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(def e1 (d/gen-valid-obj-graph m1))
(d/object-graph-errors e1)
(count e1)
(pprint e1)

(d/view m1)
(d/write-dot-file m1 "m1.dot")

(def m1b (let [x "a" y "b"] {x y y x :c x}))
(d/view m1b)
(def m1c {"abc" "def" (str "de" "f") (str "a" "bc") :c "abc"})
(d/view m1c)
(def m1d {"abc" "d\u1234f" (str "d\u1234" "f") (str "a" "bc") :c "abc"})
(d/view m1d)

(def m2 (vec (range 70)))
(def m2 (vec (range 1000)))
(def e2 (d/gen-valid-obj-graph m2))
(def err2 (d/object-graph-errors e2))
(d/write-dot-file m2 "m2.dot")

(def m2 (vec (range 35)))
(d/write-dot-file m2 "m2.dot")

(defn int-map [n]
  (into {} (map (fn [i] [(* 2 i) (inc (* 2 i))])
                (range n))))
(def m5 (int-map 5))
(def m50 (int-map 50))
(def opts {:node-label-fn #(d/str-with-limit % 50)})
(d/view m5 opts)
(d/view m50 opts)
(d/write-dot-file m5 "m5.dot" opts)
(d/write-dot-file m50 "m50.dot" opts)

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def m1 (let [x :a y :b] {x y y x}))
(def e1 (d/myexternals m1))
(pprint e1)

(def a2o (d/make-addr->objmap e1 str))
(pprint a2o)

(def em (d/make-edge-map a2o))
(pprint em)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def a (atom {}))
(d/update-edge-call-count a 1 2)
(d/update-edge-call-count a 2 2)
@a

;; The images produced by GraphLayout/toImage method seem fairly
;; useless to me, from these two examples.
(def m1 (let [x :a y :b] {x y y x}))
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(.toImage p1 "m1.png")

(def m2 (vec (range 1000)))
(def p2 (GraphLayout/parseInstance (object-array [m2])))
(.toImage p2 "m2.png")

  )
