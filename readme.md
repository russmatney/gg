# gg

A babashka wrapper with some godot game-dev built-ins.

## status

Quite useful for me already, tho not well documented, and likely plenty of
hiccups/opinions baked in.

I now use `gg changelog` in several repos (game dev or not!) to generate
changelogs, and `gg addonz` to manage Godot addon dependencies across all my
Godot projects with a shared cache.

I owe docs on the zsh config to get pass-through completions and what-not - it's
in my [dotfiles](https://github.com/russmatney/dotfiles) if you're willing to
dig!

## features

- pass through to babashka (`bb`)
- **addonz** - Godot addon dependency management with shared cache (see below)
- plug.gd install (or whatever godot package manager)
  - handful of plug interactions
- gdunit - based test running
- godot export
- itch deploy (via butler)
- aseprite auto-export file watcher
- piecemeal project templating
  - i.e. add bones, log.gd, gdunit, etc to your project
- simple web server for testing web builds
- generate steam boxart from a source
- generating changelogs

## addonz - Godot Addon Management

`gg addonz` is a babashka-based addon management system that uses EDN configuration and a shared cache across all projects.

### Features

- **Shared cache**: All addons are cloned once to `~/.config/gg/.cache/addonz/` and reused across projects
- **Fast installs**: No need to clone repos multiple times
- **Less disk space**: One clone shared by all projects
- **Independent updates**: Update individual addons with `gg addonz update <name>`
- **Version pinning**: Support for branches, tags, and commit hashes
- **Clean updates**: Always deletes and replaces addon directories to avoid stale files

### Quick Start

```bash
# Initialize addonz in your project
gg addonz init

# Edit addonz.edn and add your addons
# Then install them
gg addonz install

# Check status
gg addonz status

# Update all addons
gg addonz update

# Update specific addon
gg addonz update gdUnit4
```

### Configuration

Create `addonz.edn` in your project root:

```clojure
{:addons
 [{:repo "MikeSchulze/gdUnit4"
   :include ["addons/gdUnit4"]}
  {:repo "bitbrain/pandora"
   :include ["addons/pandora"]}
  {:repo "russmatney/log.gd"
   :include ["addons/log"]
   :branch "pretty-loggers"}
  {:repo "KoBeWi/Metroidvania-System"
   :include ["addons/MetroidvaniaSystem"]
   :exclude ["Extensions"]}]

 :config
 {:cache-dir "~/.config/gg/.cache/addonz"
  :addons-dir "addons"
  :index-file "~/.config/gg/.cache/addonz/index.edn"}}
```

**Fields:**
- `:repo` - GitHub `username/repo` format (required)
- `:include` - Array of directories to copy from cache to addons (required)
- `:exclude` - Array of directories to skip when copying (optional)
- `:branch`, `:tag`, `:commit` - Version pinning (optional)

### Commands

```bash
gg addonz init             # Create empty addonz.edn
gg addonz install          # Install all addons from addonz.edn
gg addonz update           # Update all addons (git pull in cache)
gg addonz update <addon>   # Update specific addon (e.g., "gdUnit4")
gg addonz status           # Show installation status
gg addonz clean-cache      # Remove ~/.config/gg/.cache/addonz
gg addonz add <repo>       # Add addon interactively
gg addonz remove <repo>    # Remove addon from config
```

### Migration from plug.gd

Advantages over plug.gd:
- **Shared cache**: No duplicate clones across projects
- **Faster installs**: Reuse cached repos
- **Less disk space**: One clone shared by all projects
- **Independent updates**: `gg addonz update <name>` for single addon
- **Cleaner projects**: No `.plugged/` directory in each project

To migrate:
1. Run `gg addonz init` to create addonz.edn
2. Convert each `plug("user/repo", {include=[...]})` line to EDN map format
3. Run `gg addonz install` to install from shared cache
