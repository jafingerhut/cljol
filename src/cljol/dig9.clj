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


(defn myexternals [x]
  (let [parsed-inst (GraphLayout/parseInstance (object-array [x]))
        addresses (.addresses parsed-inst)]
    (map (fn [addr]
           (let [gpr (. parsed-inst record addr)
                 ;;_ (def gpr1 gpr)
                 obj (.invoke gpr-obj-method gpr empty-obj-array)
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


(defn object-moved? [obj-info]
  (let [obj (:obj obj-info)
        addr (:address obj-info)
        cur-addr (address-of obj)]
    (if (not= addr cur-addr)
      (assoc obj-info :cur-address cur-addr))))


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


(defn render-object-graph [g opts]
  (let [obj->label-str (get opts :label-fn str)
        addr->obj-old (into {}
                            (for [obj g]
                              [(:address obj)
                               (assoc obj
                                      :label (obj->label-str (:obj obj))
                                      :class (class (:obj obj)))]))
        addr->obj (->> (group-by :address g)
                       (map-vals first-if-exactly-one)
                       (map-vals (fn [obj-map]
                                   (assoc obj-map
                                          :label (obj->label-str (:obj obj-map))
                                          :class (class (:obj obj-map))))))
        _ (assert (= addr->obj-old addr->obj))
        graph (into {}
                    (for [[addr obj] addr->obj]
                      [addr
                       (->> (vals (:fields obj))
;;                            (map #(if (integer? %)
;;                                    (get addr->obj %)))
                            (remove nil?)
                            vec)]))
        node-desc (fn [addr]
                    (let [obj-info (addr->obj addr)
                          obj (:obj obj-info)]
                      {:shape "box"
                       :label (format "@%08x\n%d bytes\npath=%s\n%s"
                               (:address obj-info)
                               ;;format "%d bytes\npath=%s\n%s"
                               (:size obj-info)
                               (:path obj-info)
                               (cond (array? obj)
                                     (format "array of %d %s"
                                             (count obj)
                                             (pr-str
                                              (array-element-type obj)))
                                     :else (:label obj-info)))}))
        edge-map (into {}
                       (for [[from-addr from-obj-map] addr->obj
                             [from-obj-field-name-str to-addr] (:fields from-obj-map)
                             :when (not (nil? to-addr))]
                         [[from-addr to-addr] {:field-name-str from-obj-field-name-str}]))
        edge-desc (fn edge-description [addr1 addr2]
                    (let [edge-info (get edge-map [addr1 addr2])]
                      (println (format "dbg: addr1=%d addr2=%d edge-info=%s" addr1 addr2 edge-info))
                      {:label (:field-name-str edge-info)}))
        ]
    ;; TBD: I do not know how to achieve it, but it would be nice if
    ;; array elements were at least usually rendered in order of
    ;; index.  I suspect that putting them in that order into the
    ;; GraphViz .dot file would achieve that in many cases, if not
    ;; all, but not sure how to call and/or modify view-graph and
    ;; graph->dot functions to achieve that.
    (apply (case (get opts :render-method :view)
             :view viz/view-graph
             :dot-str dot/graph->dot)
           [(keys graph) graph :node->descriptor node-desc
            :edge->descriptor edge-desc
            :vertical? false])))


(defn truncate-long-str [s n]
  (if (> (count s) n)
    (str (subs s 0 n) " ...")
    s))

(defn str-with-limit [obj n]
  (truncate-long-str (str obj) n))


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

(def m2 (vec (range 70)))
(def m2 (vec (range 1000)))
(def e2 (d/gen-valid-obj-graph m2))
(def err2 (d/object-graph-errors e2))
(d/write-dot-file m2 "m2.dot")

(def m2 (vec (range 35)))
(d/write-dot-file m2 "m2.dot")

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

;; The images produced by GraphLayout/toImage method seem fairly
;; useless to me, from these two examples.
(def m1 (let [x :a y :b] {x y y x}))
(def p1 (GraphLayout/parseInstance (object-array [m1])))
(.toImage p1 "m1.png")

(def m2 (vec (range 1000)))
(def p2 (GraphLayout/parseInstance (object-array [m2])))
(.toImage p2 "m2.png")

  )
