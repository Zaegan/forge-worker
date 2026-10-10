# secrets/

**Every credential, token, key and password goes here. Nothing in this directory is ever committed.**

This is a *design*, not a rule. A rule ("don't commit secrets") depends on whoever is committing to
remember, every time. A folder makes the safe path the obvious path. Also ignored globally via
`~/.gitignore_global`, so secrets/ is safe in any repo even before this folder exists.

## What it does and does not protect

- **Does**: stop accidental commits -- which is how essentially all real credential leaks happen.
- **Does not**: stop `git add -f`, which overrides the ignore. This is accident prevention, not access
  control.
- **Does not**: cover a secret that leaks somewhere else -- into a log, a config, or shell history.
  Never echo a credential into a file inside the repo.

## The distinction that matters

`git commit` is local and free -- it has no network capability. `git push` is what publishes.

But a secret committed locally is a secret that **will** be published the first time anyone pushes,
because push sends the whole ancestry. Scrubbing it afterwards means rewriting history, which destroys
the audit trail the commits exist for.

**Local-ness protects work. It does not protect secrets.**
