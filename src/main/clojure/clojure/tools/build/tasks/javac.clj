;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns clojure.tools.build.tasks.javac
  (:require
    [clojure.java.io :as jio]
    [clojure.string :as str]
    [clojure.tools.build.task.api :as tapi]
    [clojure.tools.build.task.file :as file])
  (:import
    [java.io File]
    [javax.tools ToolProvider DiagnosticListener]))

(set! *warn-on-reflection* true)

(defn javac
  [{:keys [libs] :as basis} {:build/keys [project-dir output-dir] :as params}]
  (let [java-paths (tapi/resolve-param basis params :build/java-paths)]
    (when (seq java-paths)
      (let [javac-opts (tapi/resolve-param basis params :build/javac-opts)
            class-dir (file/ensure-dir (jio/file output-dir (tapi/resolve-param basis params :build/class-dir)))
            compiler (ToolProvider/getSystemJavaCompiler)
            listener (reify DiagnosticListener (report [_ diag] (println (str diag))))
            file-mgr (.getStandardFileManager compiler listener nil nil)
            class-dir-path (.getPath class-dir)
            classpath (str/join File/pathSeparator (conj (mapcat :paths (vals libs)) class-dir-path))
            options (concat ["-classpath" classpath "-d" class-dir-path] javac-opts)
            java-files (mapcat #(file/collect-files (jio/file project-dir %) :collect (file/suffixes ".java")) java-paths)
            file-objs (.getJavaFileObjectsFromFiles file-mgr java-files)
            task (.getTask compiler nil file-mgr listener options nil file-objs)
            success (.call task)]
        (when-not success
          (throw (ex-info "Java compilation failed" {})))))))