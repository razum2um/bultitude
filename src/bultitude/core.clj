(ns bultitude.core
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [dynapath.util :as dp]
            [total-reader.core :as reader])
  (:import (java.util.jar JarFile JarEntry)
           (java.util.zip ZipException)
           (java.io File BufferedReader PushbackReader InputStreamReader)
           (clojure.lang DynamicClassLoader LineNumberingPushbackReader)))

(declare namespace-forms-in-dir
         file->namespace-forms)

(def ^:dynamic *read-cond*
  (if (or (< 1 (:major *clojure-version*))
          (and (= 1 (:major *clojure-version*))
               (<= 7 (:minor *clojure-version*))))
    {:read-cond :allow}
    nil))

(defn- clojure-source-file? [^File f]
  (and (.isFile f)
       (re-matches #".*\.cljc?" (.getName f))))

(defn- clojure-source-jar-entry? [^JarEntry f]
  (re-matches #".*\.cljc?" (.getName f)))

(defn- jar? [^File f]
  (and (.isFile f) (.endsWith (.getName f) ".jar")))

(defn- drop-quote-if-present [form]
  (if (and (seq? form)
           (= 'quote (first form)))
    (second form)
    form))

(defn- update-compat
  "For compatibility with clojure <1.7"
  [m k f]
  (assoc m k (f (get m k))))

(defn- drop-quote-from-in-ns-form [in-ns-form]
  (-> in-ns-form
      vec
      (update-compat 1 drop-quote-if-present)
      seq))

(defn- do-read
  ([rdr] (do-read rdr true))
  ([rdr ignore-unreadable?]
   (fn []
     (try (if *read-cond*
            (read (assoc *read-cond* :eof ::eof) rdr)
            (read rdr false ::eof))
          (catch Exception e
            (if ignore-unreadable?
              nil
              (throw e)))))))

(defn- remove-quote-from-in-ns-form
  [in-ns-form]
  (cons (first in-ns-form)
        (second (second in-ns-form))))

(defn- read-ns-forms
  "Given a reader on a Clojure source file, read the entire file and return all namespace creating forms."
  ([rdr] (read-ns-forms rdr true))
  ([rdr ignore-unreadable?]
   (let [forms (loop [form-stream (reader/read-file
                                   (LineNumberingPushbackReader. rdr)
                                   'user)
                      forms []]
                 (try
                   (let [form (first form-stream)
                         form-stream (rest form-stream)
                         forms (conj forms form)]
                     (if (empty? form-stream)
                       forms
                       (recur form-stream (conj forms form))))
                   (catch Exception e
                     forms)))]
     (->> forms
          (filter #(contains? #{'ns 'in-ns} (first %)))
          (map remove-quote-from-in-ns-form)))))

(defn- read-ns-form
  "Given a reader on a Clojure source file, read until an ns form is found."
  ([rdr] (read-ns-form rdr true))
  ([rdr ignore-unreadable?]
   (let [form (try (if *read-cond*
                     (read (assoc *read-cond* :eof ::done) rdr)
                     (read rdr false ::done))
                   (catch Exception e
                     (if ignore-unreadable?
                       ::done
                       (throw e))))]
     (cond
       (and (list? form) (= 'ns (first form)))
       form
       (and (list? form) (= 'in-ns (first form)))
       (drop-quote-from-in-ns-form form)
       :else
       (when-not (= ::done form)
         (recur rdr ignore-unreadable?))))))

(defn ns-forms-for-file
  "Returns all namespace forms found in the given file.
  Returns an empty seq if no namespace form was found.
  Returns nil if ignore-unreadable? was set to true and the file was unreadable"
  ([file] (ns-forms-for-file file true))
  ([file ignore-unreadable?]
   (with-open [r (PushbackReader. (io/reader file))]
     (read-ns-forms r ignore-unreadable?))))

(defn ns-form-for-file
  "Returns the first namespace form found in the given file.
  Returns nil:
  - if no namespace form was found
  - if ignore-unreadable? was set to true and the file was unreadable"
  ([file] (ns-form-for-file file true))
  ([file ignore-unreadable?]
   (with-open [r (PushbackReader. (io/reader file))]
     (read-ns-form r ignore-unreadable?))))

(defn namespaces-in-dir
  "Return a seq of all namespaces found in Clojure source files in dir."
  ([dir] (namespaces-in-dir dir true))
  ([dir ignore-unreadable?]
   (map second (namespace-forms-in-dir dir ignore-unreadable?))))

(defn namespace-forms-in-dir
  "Return a seq of all namespace forms found in Clojure source files in dir."
  ([dir] (namespace-forms-in-dir dir true))
  ([dir ignore-unreadable?]
   (for [^File f (file-seq (io/file dir))
         :when (and (clojure-source-file? f) (.canRead f))
         :let [ns-form (ns-form-for-file f ignore-unreadable?)]
         :when ns-form]
     ns-form)))

(defn- ns-form-in-jar-entry
  ([jarfile entry] (ns-form-in-jar-entry jarfile entry true))
  ([^JarFile jarfile ^JarEntry entry ignore-unreadable?]
   (with-open [rdr (-> jarfile
                       (.getInputStream entry)
                       InputStreamReader.
                       BufferedReader.
                       PushbackReader.)]
     (read-ns-form rdr ignore-unreadable?))))

(defn- namespace-forms-in-jar
  ([jar] (namespace-forms-in-jar jar true))
  ([^File jar ignore-unreadable?]
   (try
     (let [jarfile (JarFile. jar)]
       (for [entry (enumeration-seq (.entries jarfile))
             :when (clojure-source-jar-entry? entry)
             :let [ns-form (ns-form-in-jar-entry jarfile entry
                                                 ignore-unreadable?)]
             :when ns-form]
         ns-form))
     (catch ZipException e
       (throw (Exception. (str "jar file corrupt: " jar) e))))))

(defn- split-classpath [^String classpath]
  (.split classpath (System/getProperty "path.separator")))

(defn loader-classpath
  "Returns a sequence of File objects from a classloader."
  [loader]
  (map io/as-file (dp/classpath-urls loader)))

(defn classpath-files
  "Returns a sequence of File objects of the elements on the classpath."
  ([classloader]
   (map io/as-file (dp/all-classpath-urls classloader)))
  ([] (classpath-files (clojure.lang.RT/baseLoader))))

(defn- classpath->collection [classpath]
  (if (coll? classpath)
    classpath
    (split-classpath classpath)))

(defn- classpath->files [classpath]
  (map io/file classpath))

(defn file->namespaces
  "Map a classpath file to the namespaces it contains. `prefix` allows for
   reducing the namespace search space. For large directories on the classpath,
   passing a `prefix` can provide significant efficiency gains."
  [^String prefix ^File f]
  (map second (file->namespace-forms prefix f)))

(defn file->namespace-forms
  "Map a classpath file to the namespace forms it contains. `prefix` allows for
   reducing the namespace search space. For large directories on the classpath,
   passing a `prefix` can provide significant efficiency gains."
  ([prefix f] (file->namespace-forms prefix f true))
  ([^String prefix ^File f ignore-unreadable?]
   (cond
     (.isDirectory f) (namespace-forms-in-dir
                       (if prefix
                         (io/file f (-> prefix
                                        (.replaceAll "\\." "/")
                                        (.replaceAll "-" "_")))
                         f) ignore-unreadable?)
     (jar? f) (let [ns-list (namespace-forms-in-jar f ignore-unreadable?)]
                (if prefix
                  (for [nspace ns-list
                        :let [sym (second nspace)]
                        :when (and sym (.startsWith (name sym) prefix))]
                    nspace)
                  ns-list)))))

(defn namespace-forms-on-classpath
  "Returs the namespaces forms matching the given prefix both on disk and
  inside jar files. If :prefix is passed, only return namespaces that begin with
  this prefix. If :classpath is passed, it should be a seq of File objects or a
  classpath string. If it is not passed, default to java.class.path and the
  current classloader, assuming it is a dynamic classloader."
  [& {:keys [prefix classpath ignore-unreadable?]
      :or {classpath (classpath-files) ignore-unreadable? true}}]
  (mapcat
   #(file->namespace-forms prefix % ignore-unreadable?)
   (->> classpath
        classpath->collection
        classpath->files)))

(defn namespaces-on-classpath
  "Return symbols of all namespaces matching the given prefix both on disk and
  inside jar files. If :prefix is passed, only return namespaces that begin with
  this prefix. If :classpath is passed, it should be a seq of File objects or a
  classpath string. If it is not passed, default to java.class.path and the
  current classloader, assuming it is a dynamic classloader."
  [& args]
  (map second (apply namespace-forms-on-classpath args)))

(defn path-for
  "Transform a namespace into a file path relative to classpath root, using the given extension (.clj default)."
  ([namespace]
   (path-for namespace "clj"))
  ([namespace extension]
   (str (-> (str namespace)
            (.replace \- \_)
            (.replace \. \/))
        "." extension)))

(defn doc-from-ns-form
  "Extract the docstring from a given ns form without evaluating the form. The docstring returned should be the return value of (:doc (meta namespace-symbol)) if the ns-form were to be evaluated."
  [ns-form]
  (:doc (meta (second (second (second (macroexpand ns-form)))))))
