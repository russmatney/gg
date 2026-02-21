(ns addonz
  (:require
   [babashka.process :as p]
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as string]
   [clojure.java.io :as io]
   [tasks :refer [shell-and-log notify expand]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Path Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn expand-path
  "Expand ~ in paths to home directory"
  [path]
  (if (string/starts-with? path "~")
    (string/replace-first path "~" (str (fs/home)))
    path))

(defn ensure-dir
  "Create directory if it doesn't exist"
  [dir]
  (when-not (fs/exists? dir)
    (fs/create-dirs dir))
  dir)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Config Management
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-config
  {:cache-dir "~/.config/gg/.cache/addonz"
   :addons-dir "addons"
   :index-file "~/.config/gg/.cache/addonz/index.edn"})

(defn read-addonz-config
  "Read and parse addonz.edn from current directory"
  []
  (let [config-file "addonz.edn"]
    (if (fs/exists? config-file)
      (try
        (edn/read-string (slurp config-file))
        (catch Exception e
          (println "Error parsing addonz.edn:" (.getMessage e))
          (throw (ex-info "Failed to parse addonz.edn" {:file config-file} e))))
      (throw (ex-info "addonz.edn not found. Run 'gg addonz init' to create it."
                      {:file config-file})))))

(defn write-addonz-config
  "Write addonz.edn to current directory"
  [config]
  (spit "addonz.edn" (with-out-str (clojure.pprint/pprint config)))
  (println "Created addonz.edn"))

(defn get-config
  "Get merged config (defaults + addonz.edn :config)"
  []
  (let [file-config (try
                      (read-addonz-config)
                      (catch Exception _
                        {}))
        user-config (:config file-config)
        merged (merge default-config user-config)]
    (-> merged
        (update :cache-dir expand-path)
        (update :index-file expand-path))))

(defn ensure-cache-dir
  "Create cache directory if missing"
  []
  (let [config (get-config)
        cache-dir (:cache-dir config)]
    (ensure-dir cache-dir)
    cache-dir))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Git Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn github-url
  "Convert 'user/repo' to full git URL"
  [repo]
  (str "https://github.com/" repo ".git"))

(defn repo->dir-name
  "Convert 'user/repo' to 'repo' for directory name"
  [repo]
  (last (string/split repo #"/")))

(defn clone-repo
  "Clone GitHub repo to shared cache"
  [{:keys [repo cache-dir]}]
  (let [repo-dir (str cache-dir "/" (repo->dir-name repo))
        url (github-url repo)]
    (if (fs/exists? repo-dir)
      (do
        (println "Repository already cached:" repo-dir)
        repo-dir)
      (do
        (println "Cloning" repo "to cache...")
        (shell-and-log (str "git clone " url " " repo-dir))
        repo-dir))))

(defn update-repo
  "Git pull existing cached repo"
  [repo-path]
  (println "Updating" repo-path "...")
  (shell-and-log {:dir repo-path} "git pull"))

(defn checkout-version
  "Checkout specific version (branch/tag/commit)"
  [{:keys [repo-path branch tag commit]}]
  (let [ref (or commit tag branch)]
    (when ref
      (println "Checking out" ref "in" repo-path)
      (shell-and-log {:dir repo-path} (str "git checkout " ref)))))

(defn get-current-commit-hash
  "Get current commit SHA"
  [repo-path]
  (try
    (-> (p/process ["git" "rev-parse" "HEAD"] {:dir repo-path :out :string})
        (p/check)
        :out
        string/trim)
    (catch Exception e
      (println "Error getting commit hash for" repo-path ":" (.getMessage e))
      "unknown")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Index Tracking
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn read-index
  "Read index file"
  [index-file]
  (if (fs/exists? index-file)
    (try
      (edn/read-string (slurp index-file))
      (catch Exception e
        (println "Error reading index, creating new one:" (.getMessage e))
        {:cached-repos {}}))
    {:cached-repos {}}))

(defn write-index
  "Write global installation tracking"
  [index-file data]
  (ensure-dir (fs/parent index-file))
  (spit index-file (with-out-str (clojure.pprint/pprint data))))

(defn update-index-entry
  "Record addon state in cache"
  [index-file repo commit-hash]
  (let [index (read-index index-file)
        entry {:commit commit-hash
               :last-updated (str (java.time.Instant/now))}
        existing (get-in index [:cached-repos repo])
        entry (if existing
                (merge existing entry)
                (assoc entry :cached-at (str (java.time.Instant/now))))]
    (write-index index-file
                 (assoc-in index [:cached-repos repo] entry))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; File Operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn clean-addon-dir
  "Delete existing addon directory before copying"
  [addon-name addons-dir]
  (let [addon-path (str addons-dir "/" addon-name)]
    (when (fs/exists? addon-path)
      (println "Cleaning existing addon directory:" addon-path)
      (fs/delete-tree addon-path))))

(defn clean-all-addons
  "Clean multiple addon directories"
  [addon-names addons-dir]
  (doseq [addon-name addon-names]
    (clean-addon-dir addon-name addons-dir)))

(defn copy-addon-files
  "Copy from cache to project addons/"
  [{:keys [cache-path include exclude addons-dir]}]
  (ensure-dir addons-dir)
  (doseq [include-path include]
    (let [src-path (str cache-path "/" include-path)
          addon-name (last (string/split include-path #"/"))
          dest-path (str addons-dir "/" addon-name)]

      (when-not (fs/exists? src-path)
        (throw (ex-info (str "Include path not found in cache: " src-path)
                        {:path src-path})))

      ;; Always clean before copying
      (clean-addon-dir addon-name addons-dir)

      (println "Copying" src-path "to" dest-path)
      (fs/copy-tree src-path dest-path {:replace-existing true})

      ;; Handle excludes
      (when (seq exclude)
        (doseq [exclude-path exclude]
          (let [exclude-full (str dest-path "/" exclude-path)]
            (when (fs/exists? exclude-full)
              (println "Excluding" exclude-full)
              (fs/delete-tree exclude-full))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Commands
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn init
  "Create skeleton addonz.edn if missing"
  []
  (if (fs/exists? "addonz.edn")
    (println "addonz.edn already exists")
    (write-addonz-config
     {:addons []
      :config {:cache-dir "~/.config/gg/.cache/addonz"
               :addons-dir "addons"
               :index-file "~/.config/gg/.cache/addonz/index.edn"}})))

(defn install
  "Main installation flow (clone to cache + clean + copy + track)"
  []
  (let [config (read-addonz-config)
        addons (:addons config)
        app-config (get-config)
        cache-dir (:cache-dir app-config)
        addons-dir (:addons-dir app-config)
        index-file (:index-file app-config)]

    (when-not (seq addons)
      (println "No addons configured in addonz.edn")
      (System/exit 0))

    (ensure-cache-dir)
    (ensure-dir addons-dir)

    (println "Installing" (count addons) "addon(s)...")

    (doseq [addon addons]
      (let [repo (:repo addon)
            _ (when-not repo
                (throw (ex-info "Addon missing :repo field" {:addon addon})))
            include (:include addon)
            _ (when-not (seq include)
                (throw (ex-info (str "Addon " repo " missing :include field") {:addon addon})))
            exclude (:exclude addon)
            branch (:branch addon)
            tag (:tag addon)
            commit (:commit addon)]

        (println "\nProcessing addon:" repo)

        ;; Clone or verify cache
        (let [repo-path (clone-repo {:repo repo :cache-dir cache-dir})]

          ;; Checkout version if specified
          (checkout-version {:repo-path repo-path
                             :branch branch
                             :tag tag
                             :commit commit})

          ;; Copy files
          (copy-addon-files {:cache-path repo-path
                             :include include
                             :exclude exclude
                             :addons-dir addons-dir})

          ;; Update index
          (let [commit-hash (get-current-commit-hash repo-path)]
            (update-index-entry index-file repo commit-hash)))))

    (try
      (notify "addonz install complete" (str (count addons) " addon(s) installed"))
      (catch Exception _
        nil))
    (println "\nInstallation complete!")))

(defn update-all
  "Update all addons (git pull in cache + recopy)"
  []
  (let [config (read-addonz-config)
        addons (:addons config)
        app-config (get-config)
        cache-dir (:cache-dir app-config)
        addons-dir (:addons-dir app-config)
        index-file (:index-file app-config)]

    (when-not (seq addons)
      (println "No addons configured in addonz.edn")
      (System/exit 0))

    (println "Updating" (count addons) "addon(s)...")

    (doseq [addon addons]
      (let [repo (:repo addon)
            repo-path (str cache-dir "/" (repo->dir-name repo))]

        (println "\nUpdating addon:" repo)

        (when (fs/exists? repo-path)
          ;; Pull latest
          (update-repo repo-path)

          ;; Checkout version if specified
          (checkout-version {:repo-path repo-path
                             :branch (:branch addon)
                             :tag (:tag addon)
                             :commit (:commit addon)})

          ;; Recopy files
          (copy-addon-files {:cache-path repo-path
                             :include (:include addon)
                             :exclude (:exclude addon)
                             :addons-dir addons-dir})

          ;; Update index
          (let [commit-hash (get-current-commit-hash repo-path)]
            (update-index-entry index-file repo commit-hash)))))

    (try
      (notify "addonz update complete" (str (count addons) " addon(s) updated"))
      (catch Exception _
        nil))
    (println "\nUpdate complete!")))

(defn find-addon-by-name
  "Find addon in config by matching repo name or include path"
  [addon-name config]
  (let [addons (:addons config)]
    (->> addons
         (filter (fn [addon]
                   (or
                    ;; Match repo name (last part)
                    (= addon-name (repo->dir-name (:repo addon)))
                    ;; Match any include path addon name
                    (some #(= addon-name (last (string/split % #"/")))
                          (:include addon)))))
         first)))

(defn update-one
  "Update specific addon (e.g., 'gdUnit4')"
  [addon-name]
  (if-not addon-name
    (println "Usage: gg addonz update <addon-name>")
    (let [config (read-addonz-config)
          addon (find-addon-by-name addon-name config)
          app-config (get-config)
          cache-dir (:cache-dir app-config)
          addons-dir (:addons-dir app-config)
          index-file (:index-file app-config)]

      (if-not addon
        (println "Addon not found:" addon-name)
        (let [repo (:repo addon)
              repo-path (str cache-dir "/" (repo->dir-name repo))]

          (println "Updating addon:" repo)

          (if-not (fs/exists? repo-path)
            (println "Repository not found in cache. Run 'gg addonz install' first.")
            (do
              ;; Pull latest
              (update-repo repo-path)

              ;; Checkout version if specified
              (checkout-version {:repo-path repo-path
                                 :branch (:branch addon)
                                 :tag (:tag addon)
                                 :commit (:commit addon)})

              ;; Recopy files
              (copy-addon-files {:cache-path repo-path
                                 :include (:include addon)
                                 :exclude (:exclude addon)
                                 :addons-dir addons-dir})

              ;; Update index
              (let [commit-hash (get-current-commit-hash repo-path)]
                (update-index-entry index-file repo commit-hash))

              (try
                (notify "addonz update complete" (str "Updated " repo))
                (catch Exception _
                  nil))
              (println "Update complete!"))))))))

(defn status
  "Display installation status"
  []
  (let [config (read-addonz-config)
        addons (:addons config)
        app-config (get-config)
        cache-dir (:cache-dir app-config)
        addons-dir (:addons-dir app-config)
        index-file (:index-file app-config)
        index (read-index index-file)]

    (println "\nAddon Status:")
    (println "=============\n")

    (if-not (seq addons)
      (println "No addons configured in addonz.edn")
      (doseq [addon addons]
        (let [repo (:repo addon)
              repo-name (repo->dir-name repo)
              repo-path (str cache-dir "/" repo-name)
              cached? (fs/exists? repo-path)
              installed? (and cached?
                              (every? #(fs/exists? (str addons-dir "/" (last (string/split % #"/"))))
                                      (:include addon)))
              index-entry (get-in index [:cached-repos repo])
              commit-hash (when cached? (get-current-commit-hash repo-path))
              short-hash (when commit-hash (subs commit-hash 0 7))]

          (println "Addon:" repo)
          (println "  Cached:" (if cached? "✓" "✗") (when cached? (str "(" repo-path ")")))
          (println "  Installed:" (if installed? "✓" "✗"))
          (when short-hash
            (println "  Commit:" short-hash))
          (when index-entry
            (println "  Last updated:" (:last-updated index-entry)))
          (println))))

    (println "\nCache directory:" cache-dir)
    (println "Addons directory:" addons-dir)))

(defn clean-cache
  "Remove entire cache directory"
  []
  (let [app-config (get-config)
        cache-dir (:cache-dir app-config)]

    (if-not (fs/exists? cache-dir)
      (println "Cache directory does not exist:" cache-dir)
      (do
        (println "Removing cache directory:" cache-dir)
        (fs/delete-tree cache-dir)
        (println "Cache cleaned successfully!")))))

(defn add
  "Interactive add with prompts for include/exclude"
  [repo-spec]
  (if-not repo-spec
    (println "Usage: gg addonz add <user/repo>, saw: " repo-spec *command-line-args*)
    (if-not (re-matches #".+/.+" repo-spec)
      (println "Invalid repo format. Use 'user/repo' format.")
      (let [config (try
                     (read-addonz-config)
                     (catch Exception _
                       {:addons []}))
            existing-repos (set (map :repo (:addons config)))]

        (if (contains? existing-repos repo-spec)
          (println "Addon already exists:" repo-spec)
          (do
            (println "\nAdding addon:" repo-spec)
            (println "Which directories should be included?")
            (println "Example: addons/gdUnit4")
            (print "Include paths (comma-separated): ")
            (flush)
            (let [include-input (read-line)
                  include (if (string/blank? include-input)
                            []
                            (mapv string/trim (string/split include-input #",")))
                  _ (when (empty? include)
                      (println "Warning: No include paths specified. You'll need to edit addonz.edn manually."))
                  _ (println "\nAny directories to exclude? (optional)")
                  _ (println "Example: Extensions")
                  _ (print "Exclude paths (comma-separated, or press Enter to skip): ")
                  _ (flush)
                  exclude-input (read-line)
                  exclude (if (string/blank? exclude-input)
                            []
                            (mapv string/trim (string/split exclude-input #",")))
                  new-addon {:repo repo-spec
                             :include include}
                  new-addon (if (seq exclude)
                              (assoc new-addon :exclude exclude)
                              new-addon)
                  new-config (update config :addons (fnil conj []) new-addon)]

              (write-addonz-config new-config)
              (println "\nAddon added successfully!")
              (println "Run 'gg addonz install' to install it."))))))))

(defn remove
  "Remove addon from config"
  [repo-spec]
  (if-not repo-spec
    (println "Usage: gg addonz remove <user/repo>")
    (let [config (read-addonz-config)
          addons (:addons config)
          filtered (filterv #(not= (:repo %) repo-spec) addons)]

      (if (= (count addons) (count filtered))
        (println "Addon not found:" repo-spec)
        (do
          (write-addonz-config (assoc config :addons filtered))
          (println "Addon removed from addonz.edn:" repo-spec)
          (println "Note: Files in addons/ directory are not automatically deleted."))))))
