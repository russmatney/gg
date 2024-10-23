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
           (str (when (and arg-string (string/includes? arg-string " "))
                  (str "full arg string:" arg-string))) )

  ;; TODO consider handling/passing arg-string along?

  (if (not cmd)
    (println "no cmd specified")
    (let [in-local-dir ((task-names dir) cmd)
          in-gg-dir    ((task-names) cmd)
          cmd-str
          (str "bb "
               (cond
                 in-local-dir (str "--config " dir "/bb.edn")
                 in-gg-dir    (str "--config " (gg-edn)))
               " " cmd)]
      (-> (p/process {:inherit true} cmd-str)
          p/check))))

(comment
  (run-command {:cmd "hi"}))
