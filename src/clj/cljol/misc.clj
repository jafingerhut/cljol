(ns cljol.misc)


;; I wrote these at one point during cljol development, and probably
;; used them at some point, but they do not appear to be used now.
;; Keeping them around for a bit in case I find a use for them.


(defn find-first-or-last
  "Find and return the first item of coll such that (pred item) returns
  logical true.  If there is no such item, but there is at least one
  item in coll, return the last item.  If coll is empty, return the
  not-found value."
  [pred coll not-found]
  (letfn [(step [s n]
            (let [f (first s)]
              (if (pred f)
                [f n]
                (if-let [nxt (next s)]
                  (recur nxt (inc n))
                  [f n]))))]
    (if-let [s (seq coll)]
      (step s 1)
      [not-found -1])))

(comment
(= [6 3] (find-first-or-last #(>= % 5) [2 4 6 8] :not-found))
(= [8 4] (find-first-or-last #(>= % 10) [2 4 6 8] :not-found))
(= [:not-found -1] (find-first-or-last #(>= % 10) [] :not-found))
)


(defn last-and-count
  "Return a vector of 2 elements, taking linear time in the size of
  coll, and traversing through its elements only once.  The first
  element of the returned vector is the last item in coll, or nil if
  coll is empty.  The second element of the returned vector is the
  number of elements in coll, 0 if coll is empty."
  [coll]
  (letfn [(step [s count]
            (if (next s)
              (recur (next s) (inc count))
              [(first s) count]))]
    (if-let [s (seq coll)]
      (step s 1)
      [nil 0])))

(comment
(last-and-count [:a :b :c])
;; => [:c 3]
(last-and-count (take 0 (range 100)))
;; => [nil 0]
(last-and-count (take 1 (range 100)))
;; => [0 1]
(last-and-count (take 5 (range 100)))
;; => [4 5]
)
