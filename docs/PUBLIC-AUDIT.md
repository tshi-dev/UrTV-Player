# Pre-public audit (repository made public by the owner)

Scope: the whole history (all branches and tags), the workflows, the artifacts and the documents.

## Result in short
| Area | Status |
|---|---|
| Thmanyah font files or subsets in history | **Clean**: none, in any commit, branch or tag. Only IBM Plex Sans Arabic (OFL) was ever committed (`res/font`, design mockups). The text "Thmanyah" appears only in `.gitignore`, the workflow, `Fonts.kt` and `RELEASING.md` |
| CI artifacts containing the font | **Fixed going forward**: the debug APK is now uploaded only when no private font was injected, kept 3 days. **Needs the owner**: existing artifacts (see below) |
| Keys, tokens, keystores, passwords in history | **Clean**: no `.jks/.keystore/.pem/.env`, no token patterns (ghp_/github_pat_/AKIA/AIza/BEGIN PRIVATE KEY), no long secrets; signing material is only in Actions secrets |
| Personal data in the tree | **Fixed**: `docs/design/src/html/jobs.json` held the sandbox path `/home/user/...` (removed). No email, name, serial, Wi-Fi name or real address in the current tree. Private-range IPs in tests/docs are generic examples (`192.168.1.x`, `10.0.0.x`, `192.0.2.x`) |
| Personal data in history | **Needs the owner**: the root commit `11692c4` ("Initial commit") has the owner's personal email address in its author field (not in any file). All other commits are `tshi-dev <272740088+tshi-dev@users.noreply.github.com>`; the 9 Dependabot commits are bot-authored. No AI/co-author lines anywhere. History was not rewritten; options below |
| Screenshots | **Clean**: `docs/shots` and `docs/design` are rendered from generated test data (invented channel names, `example.com`) |
| Workflows | **Fixed** (details below) |
| LICENSE | **Needs the owner**: see below |
| Docs | **Done**: README (en/ar) with install, updates, privacy, credits; CONTRIBUTING; SECURITY; issue templates; `docs/THIRD-PARTY.md` |
| One source of truth for updates | **Done**: stable release assets and "latest" |

## Existing CI artifacts
57 artifacts named `urtv-player-debug`, default retention 90 days (they expire between 2027-02-07 and 2027-02-08). 31 of them were built
on or after 2026-11-09 19:59 UTC, when the private-font step was added, and may contain the Thmanyah files inside the debug APK; the other 26 are older and cannot.
Once public, any signed-in GitHub user could download them. The owner should delete them all (Actions > any run > Artifacts, or
`gh api -X DELETE repos/tshi-dev/UrTV-Player/actions/artifacts/<id>` for each id from `gh api repos/tshi-dev/UrTV-Player/actions/artifacts --paginate`) before flipping the visibility.
The four existing release APKs (v0.9.0 to v0.9.3) are the product and may carry the font; they are listed as pre-releases without the stable asset names.
Workflow run **logs** contain no secrets (secrets are masked) and only the names of the font files.

## Workflow hardening (`.github/workflows/build.yml`, pinned by `WorkflowHygieneTest`)
- No `pull_request_target`; fork pull requests run only tests and get no secrets (`FONTS_TOKEN`, signing secrets are empty for them).
- `permissions: contents: read` at the top; only the release job has `contents: write`.
- The release job runs only in `tshi-dev/UrTV-Player`, for a `v*` tag or a manual run (which needs write access); the tag is validated (`vN.N.N` with an optional `-suffix`).
- Every action is pinned to a commit SHA with a version comment; Dependabot keeps them current.
- Tag, branch and title text reach shell steps only through `env:`, never inline.
- Debug artifacts: only without private fonts, 3 days. Job timeouts of 45 minutes.
- A Robolectric download failure (Maven Central rate limit) is cached and retried once, only for that exact failure.

## Settings the owner should switch on (GitHub, after going public)
1. Settings > Branches: protect `main` (require a pull request and the "Build and test" check; no force pushes or deletions; allow the owner to bypass for direct pushes if he wants to keep pushing straight to main).
2. Settings > Actions > General: "Require approval for all outside collaborators" for fork pull requests; "Read repository contents permission" as the default token permission; allow only GitHub and verified-creator actions (or "selected actions").
3. Settings > Code security: enable private vulnerability reporting, secret scanning with push protection, Dependabot alerts and security updates.
4. Settings > Tags: protect `v*` so only the owner can create release tags.
5. Delete the 57 old artifacts (above).

## License
UrTV (the public repository) has **no LICENSE file** (its GitHub licence field is empty); its README credits its fonts (Thmanyah) and
third parties. This repository mirrors that: no licence file is added here without the owner's decision, the README says all
rights are reserved until he adds one, the fonts notice is in the README credits and `docs/THIRD-PARTY.md`. If he wants one, MIT or
Apache-2.0 are the usual choices for a player; add the file and delete the sentence in the README credits.

## History rewrite option (only if the owner wants the personal email out of the root commit)
Rewriting would change every commit hash, break the tags `v0.9.0` to `v0.9.3` and need a force push. Alternative with no rewrite:
keep it (it is the author field of one commit, the address is not secret to GitHub users who see the commit patch URL) or make the
repository public from a fresh single-commit history. Not done: needs the owner's decision.
