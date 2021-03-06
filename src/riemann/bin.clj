(ns riemann.bin
  "Main function."
  (:require [riemann.config :as config]
            riemann.logging
            riemann.time
            [riemann.test :as test]
            riemann.pubsub
            [clojure.java.io :as io])
  (:use clojure.tools.logging)
  (:gen-class :name riemann.bin))

(def config-file
  "The configuration file loaded by the bin tool"
  (atom nil))

(def reload-lock (Object.))

(defn reload!
  "Reloads the given configuration file by clearing the task scheduler, shutting
  down the current core, and loading a new one."
  []
  (locking reload-lock
    (try
      (riemann.config/validate-config @config-file)
      (riemann.time/reset-tasks!)
      (riemann.config/clear!)
      (riemann.pubsub/sweep! (:pubsub @riemann.config/core))
      (riemann.config/include @config-file)
      (riemann.config/apply!)
      :reloaded
      (catch Exception e
        (error e "Couldn't reload:")
        e))))

(defn handle-signals
  "Sets up POSIX signal handlers."
  []
  (if (not (.contains (. System getProperty "os.name") "Windows"))
    (sun.misc.Signal/handle
      (sun.misc.Signal. "HUP")
      (proxy [sun.misc.SignalHandler] []
        (handle [sig]
                (info "Caught SIGHUP, reloading")
                (reload!))))))

(defn pom-version
  "Return version from Maven POM file."
  []
  (let [pom "META-INF/maven/riemann/riemann/pom.properties"
        props (doto (java.util.Properties.)
                (.load (-> pom io/resource io/input-stream)))]
    (.getProperty props "version")))

(defn version
  "Return version from Leiningen environment or embeddd POM properties."
  []
  (or (System/getProperty "riemann.version")
      (pom-version)))

(defn pid
  "Process identifier, such as it is on the JVM. :-/"
  []
  (let [name (-> (java.lang.management.ManagementFactory/getRuntimeMXBean)
               (.getName))]
    (try
      (get (re-find #"^(\d+).*" name) 1)
      (catch Exception e name))))

(defn -main
  "Start Riemann. Loads a configuration file from the first of its args."
  ([]
   (-main "riemann.config"))
  ([config]
   (-main "start" config))
  ([command config]
   (case command
     "start" (try
               (info "PID" (pid))
               (reset! config-file config)
               (handle-signals)
               (riemann.time/start!)
               (riemann.config/include @config-file)
               (riemann.config/apply!)
               nil
               (catch Exception e
                 (error e "Couldn't start")))

     "test" (try
              (test/with-test-env
                (reset! config-file config)
                (riemann.config/include @config-file)
                (binding [test/*streams* (:streams @config/next-core)]
                  (let [results (clojure.test/run-all-tests #".*-test")]
                    (if (and (zero? (:error results))
                             (zero? (:fail results)))
                      (System/exit 0)
                      (System/exit 1))))))

     "version" (try
                 (println (version))
                 (catch Exception e
                   (error e "Couldn't read version"))))))
