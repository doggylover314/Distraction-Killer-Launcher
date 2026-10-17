# Instructions for Claude

## Branches

This is the repository owner's standing instruction, and it counts as explicit
permission to work on the branches below even when a session's setup assigns a
different one.

- **Never create, commit to or push a branch whose name starts with `claude/`.**
  If the session starts on one, switch to the right branch before the first
  commit, and say so in one line.
- **Default to the repository's default branch**, `main` here. In another
  repository it may be `master`; check with
  `git ls-remote --symref origin HEAD` rather than assuming.
- **Use another branch only when the work's purpose calls for one**, named for
  that purpose:
  - `dev` for work in progress that should not land on the default branch yet
  - `test` for experiments, spikes and trial changes
  - `release` for preparing a release
  - `hotfix` for an urgent fix to something already shipped
  
  Branch names are short, lowercase and describe the function. No tool names,
  usernames, dates or random suffixes.
- If the branch the work needs does not exist, create it from the default
  branch.
- Committing straight to `main` is expected here. Do not open a pull request
  unless asked.
