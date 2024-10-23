#!/usr/bin/env bb

(require
  '[babashka.process :as p]
  '[clojure.edn :as edn])

(println "Running `gg`")

(defn gg-dir []
  (str (System/getProperty "user.home") "/russmatney/gg"))
(defn gg-edn []
  (str (gg-dir) "/bb.edn"))

(defn gg-tasks []
  (-> (gg-edn)
      slurp
      edn/read-string
      :tasks
      keys
      (->> (map str)
           (into []))))

(defn run-command
  [args]
  (if (not (seq args))
    (println "no args passed to `gg`")
    (->
      (p/process
        {:inherit true}
        (apply str "bb "
               (when (contains? (gg-tasks) (first args))
                 (str "--config " (gg-edn)))
               " "
               args))
      p/check)))

(run-command *command-line-args*)

(comment
  (run-command ["hi"]))
