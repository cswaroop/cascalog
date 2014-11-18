(ns cascalog.logic.testing
  (:require [clojure.test :refer :all]
            [cascalog.api :refer :all]
            [jackknife.core :as u]
            [jackknife.seq :refer (unweave collectify multi-set)]
            [cascalog.cascading.tap :as tap]
            [cascalog.cascading.io :as io]
            [cascalog.logic.platform :as platform])
  (:import [java.io File]
           [cascading.tuple Fields]
           [cascalog.cascading.platform CascadingPlatform]))

;; ## Cascading Testing Functions
;;
;; The following functions create proxies for dealing with various
;; output collectors.

;; End of the Cascading runner functions.

(defn mk-test-tap [fields-def path]
  (-> (tap/sequence-file fields-def)
      (tap/lfs path)))

(letfn [(mapify-spec [spec]
          (if (map? spec)
            spec
            {:fields Fields/ALL :tuples spec}))]

  (defn mk-test-sink [spec path]
    (mk-test-tap (:fields (mapify-spec spec)) path)))

(defn- mk-tmpfiles+forms [amt]
  (let [tmpfiles  (take amt (repeatedly (fn [] (gensym "tap"))))
        tmpforms  (->> tmpfiles
                       (mapcat (fn [f]
                                 [f `(File.
                                      (str (cascalog.cascading.io/temp-dir ~(str f))
                                           "/"
                                           (u/uuid)))])))]
    [tmpfiles (vec tmpforms)]))

(defn- doublify [tuples]
  (vec (for [t tuples]
         (into [] (map (fn [v] (if (number? v) (double v) v))
                       (collectify t))))))

(defn is-specs= [set1 set2]
  (every? true? (doall
                 (map (fn [input output]
                        (let [input  (multi-set (doublify input))
                              output (multi-set (doublify output))]
                          (is (= input output))))
                      set1 set2))))

(defn is-tuplesets= [set1 set2]
  (is-specs= [set1] [set2]))

(defn process?-
  "Returns a 2-tuple containing a sequence of the original result
  vectors and a sequence of the output tuples generated by running the
  supplied queries with test settings."
  [& [ll :as bindings]]
  (let [[log-level bindings] (if (contains? io/log-levels ll)
                               [ll (rest bindings)]
                               [:fatal bindings])]
    (io/with-log-level log-level
      (io/with-fs-tmp [_ sink-path]
        (with-job-conf {"io.sort.mb" 10}
          (let [bindings (mapcat (partial apply normalize-sink-connection)
                                 (partition 2 bindings))
                [specs rules]  (unweave bindings)
                sinks          (map mk-test-sink specs
                                    (u/unique-rooted-paths sink-path))
                _              (apply ?- (interleave sinks rules))
                out-tuples     (doall (map tap/get-sink-tuples sinks))]
            [specs out-tuples]))))))

(defn test-cascading?- [& bindings]
  (let [[specs out-tuples] (apply process?- bindings)]
    (is-specs= specs out-tuples)))

(defn test-clojure?- [spec-orig & bindings]
  (let [out-tuples (apply platform/compile-query bindings)]
    (is-specs= spec-orig out-tuples)))

(defn test?- [& bindings]
  (if (= CascadingPlatform (type platform/*context*))
    (apply test-cascading?- bindings)
    (apply test-clojure?- bindings)))

(defn check-tap-spec [tap spec]
  (is-tuplesets= (tap/get-sink-tuples tap) spec))

(defn check-tap-spec-sets [tap spec]
  (is (= (multi-set (map set (doublify (tap/get-sink-tuples tap))))
         (multi-set (map set (doublify spec))))))

(defn with-expected-sinks-helper [checker bindings body]
  (let [[names specs] (map vec (unweave bindings))
        [tmpfiles tmpforms] (mk-tmpfiles+forms (count names))
        tmptaps (mapcat (fn [n t s]
                          [n `(cascalog.logic.testing/mk-test-sink ~s ~t)])
                        names tmpfiles specs)]
    `(cascalog.cascading.io/with-tmp-files ~tmpforms
       (let [~@tmptaps]
         ~@body
         (dorun (map ~checker ~names ~specs))))))

;; bindings are name spec, where spec is either {:fields :tuples} or
;; vector of tuples
(defmacro with-expected-sinks [bindings & body]
  (with-expected-sinks-helper check-tap-spec bindings body))

(defmacro with-expected-sink-sets [bindings & body]
  (with-expected-sinks-helper check-tap-spec-sets bindings body))

(defmacro test?<- [& args]
  (let [[begin body] (if (keyword? (first args))
                       (split-at 2 args)
                       (split-at 1 args))]
    `(test?- ~@begin (<- ~@body))))

(defmacro thrown?<- [error & body]
  `(is (~'thrown? ~error (<- ~@body))))
