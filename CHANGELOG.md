# CHANGELOG


## Untagged


## 


### 23 Nov 2024

- ([`abd6c61`](https://github.com/russmatney/gg/commit/abd6c61)) fix: prevent command-not-found crash - Russell Matney

  > The '$1' quotes here prevent the arg from passing in as `true`!

- ([`b7d5406`](https://github.com/russmatney/gg/commit/b7d5406)) fix: use short-dir in commit hash link - Russell Matney
- ([`9bfa108`](https://github.com/russmatney/gg/commit/9bfa108)) fix: crash when no bb.edn found - Russell Matney

  > Now running everywhere again!

- ([`114be9c`](https://github.com/russmatney/gg/commit/114be9c)) refactor: changelog now directory-agnostic - Russell Matney

  > Irons the hard-coding out of changelog so it can be run in other repos.
  > Still some assumptions in here, but they work for me :P


### 3 Nov 2024

- ([`f161483`](https://github.com/russmatney/gg/commit/f161483)) feat: plug-update,clear commands - Russell Matney

### 24 Oct 2024

- ([`03aca7e`](https://github.com/russmatney/gg/commit/03aca7e)) wip: pull in other shared bb.edn commands, changelog source - Russell Matney

  > These are not genericized yet, but most are already usable in several of
  > my projects, so we're copy-pasting.


### 23 Oct 2024

- ([`a9c37ea`](https://github.com/russmatney/gg/commit/a9c37ea)) chore: add logger and tasks - Russell Matney
- ([`4c1fcd0`](https://github.com/russmatney/gg/commit/4c1fcd0)) feat: call local cmd before gg cmd - Russell Matney
- ([`ece963a`](https://github.com/russmatney/gg/commit/ece963a)) refactor: pull babashka into src/gg - Russell Matney

  > Feels like we want to prefer the local cmd rather than the gg one.
