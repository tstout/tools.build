;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.build.file :as file])
  (:import
    [java.io File FileOutputStream FileInputStream BufferedInputStream]
    [java.nio.file Path Paths Files LinkOption]
    [java.util.jar Manifest Attributes$Name JarOutputStream JarEntry]
    [javax.tools ToolProvider]))

(set! *warn-on-reflection* true)

;; clean

(defn clean
  [{:keys [params] :as build-info}]
  (let [{:build/keys [target-dir]} params]
    (println "Cleaning" target-dir)
    (file/delete target-dir)
    build-info))

;; aot

(defn aot
  [{:keys [params] :as build-info}]
  (let [{:build/keys [target-dir main-class]} params]
    (binding [*compile-path* (.toString (file/ensure-dir (jio/file target-dir "classes")))]
      (compile main-class))
    build-info))

;; javac

(defn javac
  [{:keys [lib-map params] :as build-info}]
  (let [{:build/keys [target-dir java-paths javac-opts]} params]
    (println "Compiling Java")
    (when (seq java-paths)
      (let [class-dir (file/ensure-dir (jio/file target-dir "classes"))
            compiler (ToolProvider/getSystemJavaCompiler)
            listener nil ;; TODO - implement listener for errors
            file-mgr (.getStandardFileManager compiler listener nil nil)
            classpath (mapcat :paths (vals lib-map))
            options (concat ["-classpath" classpath "-d" (.getPath class-dir)] javac-opts)
            java-files (mapcat #(file/collect-files (jio/file %) :collect (file/suffixes ".java")) java-paths)
            file-objs (.getJavaFileObjectsFromFiles file-mgr java-files)
            task (.getTask compiler nil file-mgr listener options nil file-objs)]
        (.call task)))
    build-info))

;; pom

(defn sync-pom
  [{:keys [params] :as build-info}]
  (let [{:build/keys [src-pom lib version target-dir]} params
        group-id (or (namespace lib) (name lib))
        artifact-id (name lib)]
    (println "Syncing pom from" src-pom "to" target-dir)
    (let [pom-dir (file/ensure-dir
                    (jio/file target-dir "classes" "META-INF" "maven" group-id artifact-id))]

      ;; TODO: actually run current pom sync
      (jio/copy (jio/file src-pom) (jio/file pom-dir "pom.xml"))

      (spit (jio/file pom-dir "pom.properties")
        (str/join (System/lineSeparator)
          ["# Generated by org.clojure/tools.build"
           (format "# %tc" (java.util.Date.))
           (format "version=%s" version)
           (format "groupId=%s" group-id)
           (format "artifactId=%s" artifact-id)]))
      build-info)))

;; jar

(defn- add-jar-entry
  [^JarOutputStream output-stream ^String path ^File file]
  (.putNextEntry output-stream (JarEntry. path))
  (with-open [fis (BufferedInputStream. (FileInputStream. file))]
    (jio/copy fis output-stream))
  (.closeEntry output-stream))

(defn- copy-to-jar
  ([^JarOutputStream jos ^File root]
    (copy-to-jar jos root root))
  ([^JarOutputStream jos ^File root ^File path]
   (let [root-path (.toPath root)
         files (file/collect-files root)]
     (run! (fn [^File f]
             (let [rel-path (.toString (.relativize root-path (.toPath f)))]
               (println "  Adding" rel-path)
               (add-jar-entry jos rel-path f)))
       files))))

(defn- fill-manifest!
  [^Manifest manifest props]
  (let [attrs (.getMainAttributes manifest)]
    (run!
      (fn [[name value]]
        (.put attrs (Attributes$Name. ^String name) value)) props)))

(defn jar
  [{:keys [params] :as build-info}]
  (let [{:build/keys [lib version main-class target-dir
                      clj-paths resource-dirs]} params
        jar-name (str (name lib) "-" version ".jar")
        jar-file (jio/file target-dir jar-name)
        class-dir (jio/file target-dir "classes")]
    (println "Writing jar" jar-name)
    (let [manifest (Manifest.)]
      (fill-manifest! manifest
        (cond->
          {"Manifest-Version" "1.0"
           "Created-By" "org.clojure/tools.build"
           "Build-Jdk-Spec" (System/getProperty "java.specification.version")}
          main-class (assoc "Main-Class" (str main-class))))
      (with-open [jos (JarOutputStream. (FileOutputStream. jar-file) manifest)]
        (copy-to-jar jos class-dir)
        (run! #(copy-to-jar jos (jio/file %)) clj-paths)))
    build-info))

;; end

(defn end
  "Terminate the build and return nil instead of build-info"
  [build-info]
  nil)