(ns changelog
  (:require
   [clojure.string :as string]
   [clojure.edn :as edn]
   [babashka.process :as process]
   [babashka.fs :as fs]
   [clojure.java.shell :as clj-sh]))

(def changelog-path "CHANGELOG.md")

(defn bash [command]
  (clj-sh/sh "bash" "-c" command))

(defn expand
  [path & parts]
  (let [path (apply str path parts)]
    (-> (str "echo -n " path)
        (bash)
        :out)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; commits
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def log-format-keys
  "See `git log --help` 'PRETTY FORMATS'"
  {:commit/hash              "%H"
   :commit/short-hash        "%h"
   ;; :commit/tree             "%T"
   ;; :commit/abbreviated-tree "%t"
   :commit/parent-hash       "%P"
   :commit/parent-short-hash "%p"
   ;; :commit/refs               "%D"
   ;; :commit/encoding           "%e"
   :commit/subject           "%s"
   ;; :commit/sanitized-subject-line "%f"
   :commit/body              "%b"
   :commit/full-message      "%B"
   ;; :commit/commit-notes           "%N"
   ;; :commit/verification-flag      "%G?"
   ;; :commit/signer                 "%GS"
   ;; :commit/signer-key             "%GK"
   :commit/author-name       "%aN"
   :commit/author-email      "%aE"
   :commit/author-date       "%aD"
   :commit/author-date-int   "%at"
   ;; :commit/commiter-name          "%cN"
   ;; :commit/commiter-email         "%cE"
   ;; :commit/commiter-date "%cD"
   ;; :commit/commiter-date-int "%ct"
   })

(def delimiter "^^^^^")
(defn log-format-str []
  (str
    "{"
    (->> log-format-keys
         (map (fn [[k v]] (str k " " delimiter v delimiter)))
         (string/join " "))
    "}"))

(defn first-commit-hash [{:keys [dir]}]
  (->
    ^{:out :string :dir (str dir)}
    (process/$ git rev-list --max-parents=0 HEAD)
    (process/check)
    ((fn [res]
       (when (#{0} (:exit res))
         (-> res :out string/trim-newline))))))

(defn last-tag [{:keys [dir]}]
  (->
    ^{:out :string :dir (str dir)}
    (process/$ git describe --tags --abbrev=0)
    (process/check)
    ((fn [res]
       (when (#{0} (:exit res))
         (-> res :out string/trim-newline))))))

(defn all-tags [{:keys [dir]}]
  (->
    ^{:out :string :dir (str dir)}
    (process/$ git tag)
    (process/check)
    ((fn [res]
       (when (#{0} (:exit res))
         (-> res :out string/split-lines))))))

(comment
  (all-tags {:dir (expand "~/russmatney/dino")})
  (last-tag {:dir (expand "~/russmatney/dino")})
  (first-commit-hash {:dir (expand "~/russmatney/dino")}))

(defn commits
  "Retuns metadata for `n` commits at the specified `dir`."
  [{:keys [dir n after-tag before-tag] :as opts}]
  (let [
        ;; n (or n 500)
        cmd
        (str "git log"
             (when n (str " -n " n))
             (when (or after-tag before-tag)
               (str " " (or after-tag (first-commit-hash opts)) ".." (or before-tag "HEAD")))
             " --pretty=format:'" (log-format-str) "'")]
    (try
      (->
        (process/process cmd {:out :string :dir (str dir)})
        process/check :out
        ((fn [s] (str "[" s "]")))
        ;; remove unsupported escape characters?
        (string/replace "\\~" "")
        ;; pre-precess double quotes (maybe just move to single?)
        (string/replace "\"" "'")
        (string/replace delimiter "\"")
        edn/read-string
        (->> (map #(assoc % :commit/directory (str dir)))
             (sort-by (comp edn/read-string :commit/author-date-int) >)))
      (catch Exception e
        (println "Error fetching commits for dir" dir opts)
        (println e)
        nil))))

(comment
  (->>
    (commits {:dir        (expand "~/russmatney/dino")
              ;; :after-tag  (last-tag {:dir (expand "~/russmatney/dino"
              :before-tag (last-tag {:dir (expand "~/russmatney/dino")})
              })
    ;; count
    last
    ;; (take 2)
    ))

(defn release-boundaries
  [opts]
  (let [first-commit (first-commit-hash opts)
        last-commit  "HEAD"
        tags         (all-tags opts)]
    (->> (concat [first-commit] tags [last-commit])
         (partition 2 1)
         reverse)))

(defn gather-commits [opts]
  (let [boundaries (release-boundaries opts)]
    (->> boundaries
         (map (fn [[after before]]
                [before
                 (commits (-> opts
                              (assoc :before-tag before
                                     :after-tag after)))])))))

(comment
  (release-boundaries {:dir (expand "~/russmatney/dino")})
  (->>
    (gather-commits {:dir (expand "~/russmatney/dino")})
    first second first))

(defn commit-hash-link [{:keys [commit short-dir]}]
  (str "[`" (:commit/short-hash commit) "`]("
       (str "https://github.com/" short-dir "/commit/"
            (:commit/short-hash commit)) ")"))

(defn commit-date [commit]
  (->
    (re-seq
      #"\d\d? \w\w\w \d\d\d\d"
      (:commit/author-date commit))
    first))

(comment
  (first
    (re-seq
      #"\d\d? \w\w\w \d\d\d\d"
      "Wed, 2 Mar 2024 17:42:41 -0400")))


(defn commit->lines [{:as opts :keys [commit]}]
  (->>
    [(str "- (" (commit-hash-link (assoc opts :commit commit)) ") " (:commit/subject commit)
          " - " (:commit/author-name commit))
     (when (seq (string/trim-newline (:commit/body commit)))
       (str "\n" (->> (:commit/body commit)
                      (string/split-lines)
                      (map #(str "  > " %))
                      (string/join "\n"))
            "\n"))]
    (remove nil?)))

(defn tag-section
  ([tag-and-commits] (tag-section nil tag-and-commits))
  ([{:as opts :keys [latest-tag-label]} [tag commits]]
   (let [headline
         ;; TODO link to github tag!
         (str "\n## " (cond
                        (#{"HEAD"} tag) (or latest-tag-label "Untagged")
                        :else           tag))
         commit-lines (->> commits
                           (group-by commit-date)
                           (sort-by (comp edn/read-string :commit/author-date-int first second) >)
                           (mapcat (fn [[date comms]]
                                     (concat [(str "\n### " date "\n")]
                                             (mapcat #(commit->lines (assoc opts :commit %)) comms)))))]
     (concat [(str headline "\n")] commit-lines))))

(defn rewrite-changelog
  ([] (rewrite-changelog nil))
  ([{:as opts :keys [latest-tag-label path]}]
   (let [opts    (if (nil? (:dir opts))
                   (assoc opts :dir
                          ;; default to current directory
                          (str (fs/cwd)))
                   opts)
         opts    (update opts :dir expand)
         opts    (assoc opts :short-dir (str
                                          (-> opts :dir fs/parent fs/file-name)
                                          "/"
                                          (-> opts :dir fs/file-name)))
         content (str "# CHANGELOG\n\n"
                      (str
                        (->> (gather-commits opts)
                             (mapcat (partial tag-section (assoc opts :latest-tag-label latest-tag-label)))
                             (string/join "\n"))))
         p       (or path changelog-path)]
     (println "Writing content to" p)
     (when-let [docs-parent (fs/parent p)]
       (when-not (fs/exists? docs-parent)
         (-> (process/$ mkdir -p ~(str docs-parent)) process/check)))
     (spit p content))))

(comment
  (str (fs/parent (expand "~/russmatney/dino")))
  (fs/parent "hi")
  (->>
    (gather-commits {:dir "~/russmatney/dino"})
    (map #(select-keys % #{:commit/author-date})))
  (rewrite-changelog {:dir "~/russmatney/dino"})
  (rewrite-changelog {:latest-tag-label "v1.0.0"}))
