(ns fc4.io.edit
  "CLI workflow that watches Structurizr Express diagram files; when changes
  are observed to a YAML file, the YAML in the file is cleaned up and rendered
  to an image file."
  (:require [fc4.cli.util :refer [debug]]
            [fc4.io.render :refer [render-diagram-file]]
            [fc4.io.yaml :refer [process-diagram-file yaml-file?]]
            [hawk.core :as hawk])
  (:import [java.time Instant LocalTime]
           [java.time.temporal ChronoUnit]
           (java.io File OutputStreamWriter)))

(def seconds (ChronoUnit/SECONDS))
(def min-secs-between-changes 1)

(defn since
  [inst]
  (.between seconds inst (Instant/now)))

(defn process-fs-event?
  [context {:keys [kind file] :as _event}]
  (and (#{:create :modify} kind)
       (yaml-file? file)
       (or (not (contains? context file))
           (let [last-processed (get context file)]
             (debug "it’s been" (since last-processed) "seconds since" (.getName file) "was last changed...")
             (>= (since last-processed) min-secs-between-changes)))))

(defn beep
  []
  (print (char 7))
  (flush))

(defn process-file
  [context ^File file]
  (print (str (.withNano (LocalTime/now) 0)
              " "
              (.getName file)
              "... "))
  (flush)

  (try
    (process-diagram-file file)
    (render-diagram-file file)
    (println "✅")

    (catch Exception e
      (beep) ; good chance the user’s terminal is in the background
      (println "🚨" (or (.getMessage e) e))))

  ; Update the state value so process-fn-event? will be able to filter out
  ; events that are dispatched in rapid succession that would otherwise result
  ; in infinite loops.
  (assoc context file (Instant/now)))

(defn process-fs-event
  [context {:keys [file] :as _event}]
  ;; I don’t know why, but for some reason when this function is called by Hawk
  ;; in its background thread, *out* appears to be bound to a writer that isn’t
  ;; writing to System.out. We need it to be System.out so the tests can capture
  ;; the text written to System.out for use in assertions.
  (binding [*out* (OutputStreamWriter. System/out)]
    (process-file context file)))

(def current-watch
  "Useful during development."
  (atom nil))

(defn stop
  "Useful during development, when we won’t want to invoke -main because it
  invokes block."
  ([] (when @current-watch
        (stop @current-watch)))
  ([watch]
   (hawk/stop! watch)))

(defn start
  [& paths]
  (stop)
  (let [watch (hawk/watch! [{:paths   paths
                             :filter  process-fs-event?
                             :handler process-fs-event}])]
    (println "📣 Now watching for changes to YAML files under specified paths...")
    (reset! current-watch watch)))
