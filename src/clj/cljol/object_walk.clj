(ns cljol.object-walk
  (:import (java.lang.reflect Field Method Modifier Constructor))
  (:import (java.util IdentityHashMap))
  (:import (org.openjdk.jol.info ClassLayout GraphLayout GraphPathRecord
                                 ClassData FieldData))
  (:import (org.openjdk.jol.util ObjectUtils)))


(set! *warn-on-reflection* true)


;; obj() is a private method of class GraphPathRecord, and its
;; constructor is also private.  Use some Java hackery to call it
;; anyway, as long as the security policy in place allows us to.

(def ^Class gpr-class (Class/forName "org.openjdk.jol.info.GraphPathRecord"))
(def ^Method gpr-obj-method (.getDeclaredMethod gpr-class "obj" nil))
(.setAccessible gpr-obj-method true)
(def ^Constructor gpr-ctor (first (.getDeclaredConstructors gpr-class)))
(.setAccessible gpr-ctor true)


(defn FieldData->map [^FieldData fd]
  (let [^Field ref-field (.refField fd)]
    {:field-name (.name fd)
     :type-class (.typeClass fd)
     :host-class (.hostClass fd)
     :is-contended? (.isContended fd)
     :contended-group (.contendedGroup fd)
     :ref-field ref-field
     :is-primitive? (. (. ref-field getType) isPrimitive)
     :vm-offset (.vmOffset fd)}))


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


;; This is intended to do what the JOL library's (version 0.9)
;; GraphLayout/parseInstance method does, except for a few
;; enhancements:

;; + Use an IdentityHashMap to track which objects have been visited
;;   so far, rather than their numerical addresses.  This enables
;;   tracking object identities accurately, even if their addresses
;;   change due to GC during the object walk.

;; + Do the walk using a stack on the heap, and not the Java call
;;   stack, to perform the depth-first search of the object reference
;;   graph.

;; + Add an option to prevent following references out of
;;   java.lang.ref.Reference objects (and their subclasses).
;;   Following such references and returning them creates a strong
;;   reference to the objects found, preventing them from being
;;   garbage collected, and in the case of
;;   java.lang.ref.ReferenceQueue objects, many objects that likely
;;   have little or no relationship to the ones that you want to see.

(defn make-and-add-gpr-if-new [obj depth ^IdentityHashMap objects-found]
  (if (. objects-found containsKey obj)
    nil
    (let [;; I would use this if the GraphPathRecord constructor were
          ;; public.
          ;;gpr (GraphPathRecord. parent path (int depth) ^Object obj)
          ctor-args (object-array [nil nil (int depth) obj])
          gpr (.newInstance gpr-ctor ctor-args)]
      (. objects-found put obj gpr)
      gpr)))


(defn get-all-references [obj klass]
  (let [flds (->> (ClassData/parseClass klass)
                  ClassData->map
                  :fields
                  (remove :is-primitive?)
                  (map :ref-field))]
    (keep #(ObjectUtils/value obj %) flds)))


(def empty-obj-array (object-array []))


(defn gpr->java-obj [^GraphPathRecord gpr]
  (.invoke gpr-obj-method gpr empty-obj-array))


(defn peel-references [gpr]
  (let [obj (gpr->java-obj gpr)]
    (if (nil? obj)
      nil
      (let [klass (class obj)]
        (if (. klass isArray)
          (if (. (. klass getComponentType) isPrimitive)
            nil
            (keep #(aget ^objects obj %) (range (count obj))))
          (get-all-references obj klass))))))


(defn apply-conj! [transient-coll s]
  (loop [c transient-coll
         s (seq s)]
    (if-let [s (seq s)]
      (recur (conj! c (first s)) (rest s))
      c)))


(defn parse-instance-ids [stop-fn roots]
  (if (nil? roots)
    (throw (IllegalArgumentException. "Roots are null")))
  (doseq [root roots]
    (if (nil? root)
      (throw (IllegalArgumentException. "Some root is null"))))
  (let [data (GraphLayout. (object-array roots))
        objects-found (java.util.IdentityHashMap.)]
    (loop [cur-layer (keep #(make-and-add-gpr-if-new % 0 objects-found) roots)
           depth 1
           next-layer (transient [])]
      (if-let [s (seq cur-layer)]
        (let [gpr (first s)
              new-gprs (keep #(make-and-add-gpr-if-new % depth objects-found)
                             (peel-references gpr))]
          (recur (rest s) depth (apply-conj! next-layer new-gprs)))
        ;; cur-layer is empty.  Is next-layer non-empty?
        (let [nl (persistent! next-layer)]
          (if (seq nl)
            (recur nl (inc depth) (transient []))
            ;; else both cur-layer and next-layer are empty
            ;; tbd: return something
            nil))))
    {:data data
     :objects-found objects-found}))


(comment
                             
(import '(org.openjdk.jol.info ClassLayout GraphLayout GraphPathRecord
                               ClassData FieldData))

(def data (GraphLayout. (object-array [1 2 3])))
(type data)

)
