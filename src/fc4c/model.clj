(ns fc4c.model
  (:refer-clojure :exclude [read])
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.set :refer [union]]
            [clojure.spec.alpha :as s]
            [clojure.string :refer [blank? ends-with? includes? join split]]
            ;; Can’t use clojure.spec.gen because it doesn’t include let
            [clojure.test.check.generators :as gen]
            [clojure.walk :refer [postwalk]]
            [cognitect.anomalies :as anom]
            [expound.alpha :as expound :refer [expound-str]]
            [fc4c.files :refer [yaml-files relativize]]))

;; Fairly generic stuff:
(s/def ::non-empty-string (s/and string? (complement blank?)))
(s/def ::no-linebreaks  (s/and string? #(not (includes? % "\n"))))
(s/def ::non-empty-simple-string (s/and ::non-empty-string ::no-linebreaks))

;; Less generic stuff:
(s/def ::name
  (s/with-gen ::non-empty-simple-string
    ;; This needs to generate a small and stable set of names so that the
    ;; generated relationships have a chance of being valid — or at least useful.
    #(gen/elements ["Front" "Middle" "Back" "Internal" "External" "Mobile"])))

(s/def ::description ::non-empty-string)

;; Non-generic stuff:

(s/def ::set-of-keywords
  (s/coll-of (s/and keyword?
                    (comp (partial s/valid? ::non-empty-simple-string) name))
             :kind set?))

(s/def ::repos ::set-of-keywords)
(s/def ::tags ::set-of-keywords)
(s/def ::system ::name)
(s/def ::container ::name)
(s/def ::technology ::non-empty-simple-string)

(s/def ::system-ref
  (s/keys
    ;; Must contain *either* ::system *or* ::container, or both, so as to
    ;; support these cases:
    ;;
    ;; * a system or container might use a different system with or without
    ;;   specifying the container
    ;; * a container might use a different container of the same/current system,
    ;;   in which case the system is implicit
    ;;
    ;; FYI, the generator doesn’t currently respect the `or` below; a fix for
    ;; this has been contributed to core.spec but not yet released:
    ;; https://dev.clojure.org/jira/browse/CLJ-2046
    :req [(or ::container ::system (and ::system ::container))]
    :opt [::technology ::description]))

;;; order doesn’t really matter here, so I guess it should be a set?
(s/def ::uses
  (s/with-gen (s/coll-of ::system-ref :min-count 1)
    #(gen/vector (s/gen ::system-ref) 5 10)))

(s/def ::container-map
  (s/keys
    :req [::name]
    :opt [::description ::technology ::uses]))

;;; Order doesn’t really matter here, so I guess it should be a set? Or maybe a
;;; map of container names to container-maps? That would be consistent with
;;; ::systems.
(s/def ::containers (s/coll-of ::container-map))

(s/def ::element
  (s/keys
    :req [::name]
    :opt [::description ::uses ::tags]))

(s/def ::system-map
  (s/merge ::element
           (s/keys :opt [::containers ::repos])))

(s/def ::systems
  (s/with-gen (s/map-of ::name ::system-map :min-count 1)
    #(gen/let [m (s/gen (s/coll-of ::system-map))]
       (->> m
            (mapcat (juxt ::name identity))
            (apply hash-map)))))

(s/def ::user-map
  (s/merge ::element
           (s/keys :req [::uses])))

(s/def ::users
  (s/with-gen (s/map-of ::name ::user-map :min-count 1)
    #(gen/let [m (s/gen (s/coll-of ::user-map))]
       (->> m
            (mapcat (juxt ::name identity))
            (apply hash-map)))))

(s/def ::model (s/keys :req [::systems ::users]))

(s/def ::dir-path
  (s/with-gen (s/and ::non-empty-simple-string
                     #(ends-with? % "/"))
              #(gen/let [s (s/gen ::non-empty-simple-string)]
                 (str (->> (repeat 5 s) (join "/")) "/"))))

(s/def ::file (partial instance? java.io.File))

(s/def ::path-or-file (s/or ::dir-path ::file))

(defn- get-tags-from-path
  [file relative-root]
  (as-> (or (relativize file relative-root) file) v
        (split v #"/")
        (map keyword v)
        (drop-last v)
        (set v)
        (conj v (if (includes? (str file) "external")
                    :external
                    :in-house))))

(s/fdef get-tags-from-path
  :args (s/cat :file          ::dir-path
               :relative-root ::dir-path)
  :ret ::tags)

(defn- add-ns [namespace keeword]
  (keyword (name namespace) (name keeword)))

(s/def ::keyword-or-simple-string
  (s/or :keyword keyword?
        :string  ::non-empty-simple-string))

(s/fdef add-ns
  :args (s/cat :namespace ::keyword-or-simple-string
               :keyword   ::keyword-or-simple-string)
  :ret  qualified-keyword?)

(defn- update-all
  "Given a map and a function of entry (coll of two elems) to entry, applies the
  function recursively to every entry in the map."
  {:fork-of 'clojure.walk/stringify-keys}
  [f m]
  (postwalk
    (fn [x]
      (if (map? x)
        (into {} (map f x))
        x))
    m))

(s/def ::map-entry (s/tuple any? any?))

(s/fdef update-all
  :args (s/cat :fn (s/fspec :args (s/cat :entry ::map-entry)
                            :ret  ::map-entry)
               :map map?)
  :ret map?)

; We have to capture this at compile time in order for it to have the value we
; want it to; if we referred to *ns* in the body of a function then, because it
; is dynamically bound, it would return the namespace at the top of the stack,
; the “currently active namespace” rather than what we want, which is the
; namespace of this file, because that’s the namespace all our keywords are
; qualified with.
(def ^:private this-ns-name (str *ns*))

(defn- qualify-keys
  "Given a nested map with keyword keys, qualifies all keys, recursively, with
  the current namespace."
  [m]
  (update-all
    (fn [[k v]]
      (if (and (keyword? k)
               (not (qualified-keyword? k)))
        [(add-ns this-ns-name k) v]
        [k v]))
    m))

(s/fdef qualify-keys
  :args (s/cat :map (s/map-of keyword? any?))
  :ret  (s/map-of qualified-keyword? any?))

;;;; TODO: the functions below don’t sufficiently separate I/O from computation.

(defn- to-set-of-keywords
  [xs]
  (-> (map keyword xs) set))

(s/fdef to-set-of-keywords
  :args (s/cat :xs (s/coll-of string?))
  :ret  (s/coll-of keyword? :kind set?)
  :fn (fn [{{:keys [xs]} :args, ret :ret}]
        (= (count (distinct xs)) (count ret))))

(defn- fixup-element
  [tags-from-path elem]
  (-> elem
      qualify-keys
      (update ::repos to-set-of-keywords)
      (update ::tags to-set-of-keywords)
      (update ::tags (partial union tags-from-path))))

;; A file might contain a single element (as a map), or an array containing
;; multiple elements.
(defn elements-from-file [file relative-root]
  (let [parsed (-> file
                   slurp
                   yaml/parse-string)
        elems (if (associative? parsed)
                  [parsed]
                  parsed)
        tags-from-path (get-tags-from-path file relative-root)]
    (map (partial fixup-element tags-from-path)
         elems)))

(defn read-elements [root-path]
  (->> (yaml-files root-path)
       (mapcat #(elements-from-file % root-path))
       (mapcat (juxt ::name identity))
       (apply hash-map)))

(defn read
  "Pass the path of a dir (must have trailing slash) that contains the dirs
  \"systems\" and \"users\"."
  [root-path]
  (let [model {::systems (read-elements (io/file root-path "systems"))
               ::users (read-elements (io/file root-path "users"))}]
    (if (s/valid? ::model model)
        model
        {::anom/category ::anom/fault
         ::anom/message (expound-str ::model model)
         ::model model})))

;; Not useful for generative testing because the function does I/O; see comment
;; in model_test.clj.
(s/fdef read
  :args (s/cat :dir-path ::dir-path)
  :ret  (s/or :success ::model
              :error   (s/merge ::anom/anomaly
                                (s/keys :req [::model]))))
