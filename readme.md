# gg

A babashka wrapper with some godot game-dev built-ins.

## status

Quite useful for me already, tho not well documented, and likely plenty of
hiccups/opinions baked in.

I now use `gg changelog` in several repos (game dev or not!) to generate
changelogs, and `gg plug-update` and `gg plug-clear` in all my godot repos to
update dependencies.

I owe docs on the zsh config to get pass-through completions and what-not - it's
in my [dotfiles](https://github.com/russmatney/dotfiles) if you're willing to
dig!

## features/wishlist (todos)

- pass through to babashka (`bb`)
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
