(ns cljol.dig
  (:import (java.lang.reflect Field Modifier))
  (:import (org.openjdk.jol.info ClassLayout
                                 GraphLayout))
  ;;(:import (org.openjdk.jol.util VMSupport))
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

;; Main web page for JOL (Java Object Layout) library, where links to
;; all example source code mentioned below was found, library source
;; code and JAR build/install instructions, API documentation, and
;; example command lines:

;; http://openjdk.java.net/projects/code-tools/jol/


;; Function internals as adapted from the following source code:
;; http://hg.openjdk.java.net/code-tools/jol/file/a6a3bf9b6636/jol-cli/src/main/java/org/openjdk/jol/operations/ObjectInternals.java

;; Sample call:
;; (println (internals #{}))

(defn internals [x]
  (let [cls (class x)
        parsed-cls (ClassLayout/parseClass cls)]
    (.toPrintable parsed-cls x)))


;; Function internals as adapted from the following source code:
;; http://hg.openjdk.java.net/code-tools/jol/file/a6a3bf9b6636/jol-cli/src/main/java/org/openjdk/jol/operations/ObjectExtclsernals.java

;; Sample call:
;; (println (externals #{5}))

(defn externals [x]
  (let [parsed-inst (GraphLayout/parseInstance x)]
    (.toPrintable parsed-inst)))


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


(defn field-name-and-address [^Field fld obj]
  (. fld setAccessible true)
  (let [fld-val (. fld get obj)]
    [(. fld getName)
     (if (nil? fld-val)
       nil
       ;;(VMSupport/addressOf fld-val)
       -1
       )]))


(defn array-elem-name-and-address [idx array-obj]
  (let [array-idx-val (aget array-obj idx)]
    [(str "[" idx "]")
     (if (nil? array-idx-val)
       nil
       ;;(VMSupport/addressOf array-idx-val)
       -1
       )]))


(defn myexternals [x]
  (let [parsed-inst (GraphLayout/parseInstance x)
        addresses (.addresses parsed-inst)]
    (map (fn [addr]
           (let [gpr (. parsed-inst record addr)
                 obj (. gpr obj)
                 arr? (array? obj)
                 ref-arr? (and arr?
                               (not (. (array-element-type obj)
                                       isPrimitive)))
                 flds (per-instance-reference-fields (class obj))]
             {:address addr
              :obj obj
              ;;:size (VMSupport/sizeOf obj)
              :size -1
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


(defn object-moved? [obj-info]
  (let [obj (:obj obj-info)
        addr (:address obj-info)
        cur-addr ;;(VMSupport/addressOf obj)
                 -1]
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


(defn eq-obj-info?
  "The object info for two objects is considered to be for the same
  object if the objects are identical, and all of the
  fields :address :size and :fields are =.  :path and any other keys
  are ignored."
  [oi1 oi2]
  (and (identical? (:obj oi1) (:obj oi2))
       (= (:address oi1) (:address oi2))
       (= (:size oi1) (:size oi2))
       (= (:fields oi1) (:fields oi2))))


;; Assume that for each of the object graphs individually, the
;; following are true:

;; (nil? (validate-obj-graph g))
;; (not (any-objects-overlap? ex))
;; (not (any-object-moved? ex))

;; Verify that no objects overlap when the graph objects are
;; considered together as a whole.

(defn compare-obj-graphs [g1 g2]
  (let [wrap (fn [source obj-info]
               {:address (:address obj-info)
                :source source
                :obj-info obj-info})
        g1-wrapped (map #(wrap :g1 %) g1)
        g2-wrapped (map #(wrap :g2 %) g2)
        by-addr (group-by :address (concat g1-wrapped g2-wrapped))
        
        cmp-results
        (for [[addr wrapped-obj-infos] by-addr]
          (if (= 2 (count wrapped-obj-infos))
            (let [[w1 w2] wrapped-obj-infos
                  same? (eq-obj-info? (:obj-info w1)
                                      (:obj-info w2))]
              (if same?
                {:comparison-result
                 (if same?
                   :both
                   :both-same-address-but-different-objects)
                 :obj-info (if same?
                             (:obj-info w1)
                             [(:obj-info w1) (:obj-info w2)])}))
            ;; else only 1 object graph has an object with this address.
            (let [[w] wrapped-obj-infos]
              {:comparison-result (if (= :g1 (:source w)) :g1-only :g2-only)
               :obj-info (:obj-info w)})))
        
        by-cmp-result (->> cmp-results
                           (group-by :comparison-result)
                           (map-vals #(map :obj-info %)))]
    ;; Check for overlap *after* identifying which pairs of objects
    ;; are considered the same.
    (if (contains? by-cmp-result :both-same-address-but-different-objects)
      (assoc by-cmp-result :err :overlap
             :data :both-same-address-but-different-objects)
      (let [combined-objs (apply concat (vals by-cmp-result))]
        (if-let [overlap (any-objects-overlap? combined-objs)]
          (assoc by-cmp-result :err :overlap
                 :data overlap)
          (assoc by-cmp-result :err nil))))))


(defn total-size [g1]
  (reduce + (map :size g1)))


(defn object-graph-errors [g]
  (or 
   (if-let [x (any-object-moved? g)]
     {:err :object-moved :err-data x :data g})
   (validate-obj-graph g)
   (if-let [x (any-objects-overlap? g)]
     {:err :two-objects-overlap :err-data x :data g})))


(defn sizes [obj1 obj2]
  (let [g1 (myexternals obj1)
        g2 (myexternals obj2)]
    (or
     (object-graph-errors g1)
     (object-graph-errors g2)
;;     (if-let [x (any-object-moved? g1)]
;;       {:err :object-moved :err-data x :data g1})
;;     (if-let [x (any-object-moved? g2)]
;;       {:err :object-moved :err-data x :data g2})
;;     (validate-obj-graph g1)
;;     (validate-obj-graph g2)
;;     (if-let [x (any-objects-overlap? g1)]
;;       {:err :two-objects-overlap :err-data x :data g1})
;;     (if-let [x (any-objects-overlap? g2)]
;;       {:err :two-objects-overlap :err-data x :data g2})
     (let [diff (compare-obj-graphs g1 g2)]
       (if-not (nil? (:err diff))
         diff
         (let [{:keys [g1-only g2-only both]} diff
               obj1-size (total-size g1)
               obj2-size (total-size g2)
               obj1-only-size (total-size g1-only)
               obj2-only-size (total-size g2-only)
               both-size (total-size both)]
           {:err (cond
                   (not= obj1-size (+ obj1-only-size both-size))
                   :obj1-size-mismatch
                   (not= obj2-size (+ obj2-only-size both-size))
                   :obj2-size-mismatch
                   :else nil)
            :obj1-size obj1-size
            :obj2-size obj2-size
            :obj1-only-size obj1-only-size
            :obj2-only-size obj2-only-size
            :both-size both-size}))))))


(defn render-object-graph [g opts]
  (let [obj->label-str (get opts :label-fn str)
        addr->obj (into {}
                        (for [obj g]
                          [(:address obj)
                           (assoc obj
                                  :label (obj->label-str (:obj obj))
                                  :class (class (:obj obj)))]))
        graph (into {}
                    (for [[addr obj] addr->obj]
                      [addr
                       (->> (vals (:fields obj))
;;                            (map #(if (integer? %)
;;                                    (get addr->obj %)))
                            (remove nil?)
                            vec)]))
        desc (fn [addr]
               (let [obj-info (addr->obj addr)
                     obj (:obj obj-info)]
                 {:shape "box"
                  :label (format ;;"@%08x\n%d bytes\npath=%s\n%s"
                                 "%d bytes\npath=%s\n%s"
                                 ;(:address obj-info)
                                 (:size obj-info)
                                 (:path obj-info)
                                 (cond (array? obj)
                                       (format "array of %d %s"
                                               (count obj)
                                               (pr-str
                                                (array-element-type obj)))
                                       :else (:label obj-info)))
                  }))
        ]
;;    (doseq [addr (keys graph)]
;;      (println (desc addr)))
;;    graph

    ;; TBD: I do not know how to achieve it, but it would be nice if
    ;; array elements were at least usually rendered in order of
    ;; index.  I suspect that putting them in that order into the
    ;; GraphViz .dot file would achieve that in many cases, if not
    ;; all, but not sure how to call and/or modify view-graph and
    ;; graph->dot functions to achieve that.
    (apply (case (get opts :render-method :view)
             :view viz/view-graph
             :dot-str dot/graph->dot)
           [(keys graph) graph :node->descriptor desc :vertical? false])))


(defn truncate-long-str [s n]
  (if (> (count s) n)
    (str (subs s 0 n) " ...")
    s))

(defn str-with-limit [obj n]
  (truncate-long-str (str obj) n))


(defn view
  ([obj]
   (view obj {}))
  ([obj opts]
   (render-object-graph (myexternals obj) (merge opts {:render-method :view}))))


(defn write-dot-file
  ([obj fname]
   (write-dot-file obj fname {}))
  ([obj fname opts]
   (with-open [wrtr (io/writer fname)]
     (let [s (render-object-graph (myexternals obj)
                                  (merge opts {:render-method :dot-str}))]
       (spit wrtr s)))))


;; Function internals as adapted from the following source code:
;; http://hg.openjdk.java.net/code-tools/jol/file/a6a3bf9b6636/jol-cli/src/main/java/org/openjdk/jol/operations/ObjectFootprint.java

;; Sample call:
;; (println (footprint #{5}))

(defn footprint [x]
  (let [parsed-inst (GraphLayout/parseInstance x)]
    (.toFootprint parsed-inst)))


(comment

(use 'clojure.pprint)
(use 'cljol.dig)
  
(def pvs (reductions conj [] (range 100)))

(def pvs-sizes (map-indexed (fn [i [a b]] (assoc (sizes a b) :idx i))
                            (partition 2 1 pvs)))

(pprint pvs-sizes)

(defn csv-lines [sizes]
  (apply str
         (map (fn [{:keys [idx obj1-size obj1-only-size
                           obj2-only-size both-size]}]
                (format "%d,%d,%d,%d,%d\n"
                        idx
                        obj1-size
                        obj1-only-size
                        obj2-only-size
                        both-size))
              sizes)))

(spit "persistent-vector-sizes.csv" (csv-lines pvs-sizes))


(use 'clojure.pprint)
(use 'cljol.dig)
(def phms (reductions (fn [m x] (assoc m x x)) {} (range 100)))

(def phms-sizes (map-indexed (fn [i [a b]] (assoc (sizes a b) :idx i))
                             (partition 2 1 phms)))

(->> phms-sizes (filter :err) (map #(select-keys % [:idx :err :err-data])))
(spit "persistent-hash-map-sizes.csv" (csv-lines phms-sizes))

;; For reasons I haven't determined yet, I often see
;; error :some-refs-outside-object-set when running the code
;; immediately above.  Which indexes it occurs on seems to vary from
;; one run to the next.

;; I have also found that if I repeat the following 3 lines multiple
;; times in succession, the first 2 or 3 times I see some of those
;; errors, and then afterwards I do not see it again.  Perhaps this is
;; because the data structures finally get to an 'old' generation and
;; are no longer moved around in memory after that?

(def phms-sizes (map-indexed (fn [i [a b]] (assoc (sizes a b) :idx i))
                             (partition 2 1 phms)))
(->> phms-sizes (filter :err) (map #(select-keys % [:idx :err :err-data])))


(use 'clojure.pprint)
(use 'cljol.dig)

(defn int-map [n]
  (into {} (map (fn [i] [(* 2 i) (inc (* 2 i))])
                (range n))))

(def m5 (int-map 5))
(def m50 (int-map 50))
(System/gc)
(def opts {:label-fn #(str-with-limit % 50)})
(view m5 opts)
(view m50 opts)
(write-dot-file m5 "m5.dot" opts)
(write-dot-file m50 "m50.dot" opts)

;; The graph created with this data shows that when the same object is
;; referenced from multiple places in an array (:b in this case, which
;; is both element 2 and 5 of a PersistentArrayMap), the graph is
;; confusing.  Not sure of the best way to improve on that right now,
;; but wanted to record a small example that demonstrates the issue.
(write-dot-file {:a 1 :b 2 :c :b} "test.dot")

)
