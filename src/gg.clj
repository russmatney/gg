(ns gg
  (:require
   [babashka.process :as p]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [babashka.fs :as fs]))

(defn gg-dir []
  (str (System/getProperty "user.home") "/russmatney/gg"))

(defn gg-edn []
  (str (gg-dir) "/bb.edn"))

(defn task-names
  ([] (task-names (gg-dir)))
  ([dir]
   (let [edn-path (str dir "/bb.edn")]
     (if (not (fs/exists? edn-path))
       (do
         (println "no bb.edn" edn-path)
         nil)
       (-> edn-path
           slurp
           edn/read-string
           :tasks
           keys
           (->> (map str)
                (into #{})))))))

(defn run-command
  [{:keys [cmd arg-string dir]}]
  (println "Running `gg` with cmd" cmd
           (when (and arg-string (seq arg-string))
             (str " with args: " arg-string)))

  (if (not cmd)
    (println "no cmd specified")
    (let [in-local-dir ((or (task-names dir) #{}) cmd)
          in-gg-dir    ((or (task-names) #{}) cmd)
          config-str   (cond
                         in-local-dir (str "--config " dir "/bb.edn")
                         in-gg-dir    (str "--config " (gg-edn)))
          ;; Extract args from arg-string (remove the command name)
          args         (when arg-string
                         (string/trim (string/replace-first arg-string cmd "")))
          cmd-str      (str "bb " config-str " " cmd (when (seq args) (str " " args)))]
      (if config-str
        (-> (p/process {:inherit true} cmd-str)
            p/check)
        (println "command not found")))))

(comment
  (run-command {:cmd "hi"}))
