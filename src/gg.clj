(ns gg
  (:require
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as string]
   ))

(defn gg-dir []
  (str (System/getProperty "user.home") "/russmatney/gg"))

(defn gg-edn []
  (str (gg-dir) "/bb.edn"))

(defn gg-task-names []
  (-> (gg-edn)
      slurp
      edn/read-string
      :tasks
      keys
      (->> (map str)
           (into #{}))))

(defn run-command
  [{:keys [cmd arg-string]}]
  (println "Running `gg` with cmd" cmd
           (str (when (and arg-string (string/includes? arg-string " "))
                  (str "full arg string:" arg-string))) )
  (println "gg-tasks" (gg-task-names) cmd)
  (println "contains?" (contains? (gg-task-names) cmd))

  ;; TODO consider handling/passing arg-string along?

  (if (not cmd)
    (println "no cmd specified")
    (let [cmd-str
          (str "bb "
               (when ((gg-task-names) cmd)
                 (str "--config " (gg-edn)))
               " " cmd)]
      (println "running cmd" cmd-str)
      (-> (p/process {:inherit true} cmd-str)
          p/check))))

(comment
  (run-command {:cmd "hi"}))
