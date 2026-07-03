# Public GitHub + CI (web→Cloudflare, Android→Firebase App Distribution) Implementation Plan

## Overview

Move the repository to GitHub and stand up two **manually-triggered** GitHub Actions pipelines — one that builds the WasmJS web bundle and deploys it to the existing Cloudflare Worker, one that builds the Android debug APK and ships it to Firebase App Distribution (FAD). The repo starts **private** so CI can be proven without exposing course material; a final gated phase scrubs selected paths from git history with `git filter-repo` and flips the repo to **public**.

This is infrastructure work — not a product slice. It un-parks the roadmap's "CI pipeline" and the FAD half of "mobile distribution", and realizes the pre-existing sketch in `docs/vacation-workflow-todo.md` (sections A/B/C) against the web-host decision in `context/foundation/infrastructure.md`.

## Current State Analysis

- **Remote:** `origin` → `git@bitbucket.org:<user>/smartchessboard.git`. No GitHub remote. No `.github/` — zero CI today.
- **Web deploy already works manually:** `wrangler.toml` at repo root (`name = "smart-chessboard-web"`, `preview_urls = true`, `[assets] directory = ./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable`). Production is live at `smart-chessboard-web.<subdomain>.workers.dev` (roadmap Baseline). COOP/COEP `_headers` is a **source resource** (`SmartChessboard/webApp/src/webMain/resources/_headers`) baked into the build output — CI does not synthesize it.
- **Build-time secrets via BuildKonfig:** `SmartChessboard/shared/build.gradle.kts` reads `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID` from `local.properties` (dev) **or** `-P` project properties / env (CI). `SupabaseClientProvider.kt` throws if URL/anon key are empty, so any real web/Android build must have them injected. `local.properties` is gitignored.
- **Android build:** `applicationId = org.rurbaniak.smartchessboard`; AGP 9.0.1 / Kotlin 2.4.0 / compileSdk 36 / minSdk 24 / JVM 11 target. No `signingConfigs` block — `assembleDebug` uses the auto-generated per-machine debug keystore. Multi-module: `:androidApp` applies `com.android.application`, so Gradle **configuration** of the whole build (even `:webApp` tasks) can demand `ANDROID_HOME`.
- **History scrub deferred:** user confirmed `10xDevsLekcje/` for removal; the full path list is supplied later. `git-filter-repo` is **not installed**. History has 186 commits; recorded commit SHAs are scattered across `roadmap.md` / `change.md` / plan `## Progress` sections.

## Desired End State

- The repo lives on GitHub. `origin` → GitHub; `bitbucket` remote retained as backup. During Phases 1–4 the GitHub repo is **private**.
- A `web-deploy.yml` workflow, triggered manually, builds the WasmJS bundle (with Supabase creds injected) and runs `wrangler deploy`; a manual run publishes the current `main` to the live Worker.
- An `android-fad.yml` workflow, triggered manually, builds `:androidApp:assembleDebug` (deterministically signed by a committed debug keystore, with Supabase creds injected) and uploads the APK to Firebase App Distribution; a tester receives an install link.
- After the user finalizes the removal list, Phase 5 scrubs those paths from all history, force-pushes, and flips the repo to **public** — with no trace of the removed paths at any commit.

### Key Discoveries:

- `wrangler.toml` + `_headers` already exist and are proven — web CI only automates the existing `wrangler deploy` (`context/foundation/infrastructure.md` "Getting Started").
- Supabase creds path is `-P`/env → BuildKonfig (`SmartChessboard/shared/build.gradle.kts:15-33,143-148`); the same injection serves web and Android.
- FAD does **not** need `google-services.json` — it is a distribution channel, not a Firebase SDK integration. The app just needs to build and be signed with a stable cert.
- `assembleDebug` with an ephemeral keystore breaks FAD update-installs (each runner signs with a different cert) — a committed debug keystore fixes it (`docs/vacation-workflow-todo.md` "Debug keystore in CI").
- History rewrite changes every commit SHA from the first touched commit onward — the SHAs recorded in the context docs will no longer resolve (accepted, audit-only).

## What We're NOT Doing

- No iOS distribution / TestFlight (needs Apple Dev $99/yr + macOS runner) — out of scope.
- No release-signed Android build or Play Store submission — debug-signed FAD builds only.
- No auto-on-push / PR-preview triggers — **manual `workflow_dispatch` only** for both workflows (per decision).
- No production Supabase migrations from CI — schema work stays manual.
- No deletion of the Bitbucket repo — it is kept as a backup (its old, un-scrubbed history remains there by design).
- No decision on the final scrub path list here — Phase 5 consumes a user-provided list; only `10xDevsLekcje/` is pre-confirmed.

## Implementation Approach

Sequence CI on a **private** GitHub repo first so both pipelines are proven without exposing anything, then do the destructive history scrub + public flip once as a final, human-gated step. Each pipeline is its own workflow file with its own secrets and a manual run as the verification gate. Build secrets are injected as Gradle `-P` properties from GitHub Secrets; nothing secret is committed except a throwaway debug keystore (safe by convention, and only after the repo is confirmed clean).

## Critical Implementation Details

- **AGP-configuration `ANDROID_HOME` demand.** Because `:androidApp` applies `com.android.application`, Gradle may require the Android SDK even to run `:webApp` tasks. Both workflows run `android-actions/setup-android` (which exports `ANDROID_HOME`) to avoid a configuration-time failure; the lighter alternative (a settings profile excluding `:androidApp`) is a fallback only if runner minutes become a concern.
- **`git filter-repo` drops all remotes.** After a rewrite, `filter-repo` removes `origin` as a safety measure; the remotes must be re-added and the push is a `--force`. Run it on a fresh clone or with `--force`, and only after the path list is final — there is no clean partial redo.

## Phase 1: GitHub repo (private) + origin switch

### Overview

Create a private GitHub repo, make it the new `origin`, keep Bitbucket as a backup remote, and confirm nothing secret is currently tracked before any push.

### Changes Required:

#### 1. Remote wiring

**File**: local git config (no repo file change)

**Intent**: Point `origin` at a new private GitHub repo while preserving Bitbucket as a named backup, so all future pushes and CI target GitHub.

**Contract**: `origin` → `git@github.com:<user>/smartchessboard.git`; `bitbucket` → the current Bitbucket URL. Commands:
```bash
gh repo create <user>/smartchessboard --private --source=. --remote=github --push   # human-gated (gh auth)
git remote rename origin bitbucket
git remote rename github origin
git push -u origin --all && git push origin --tags
```

#### 2. Secret-hygiene pre-flight

**File**: `.gitignore` (verify, amend only if a gap is found)

**Intent**: Confirm the known local-only files (`SmartChessboard/local.properties`, `firmware/sdkconfig`, `supabase/.env.local`) are gitignored and not tracked, so even the private push carries no secret.

**Contract**: `git ls-files` contains none of the gitignored secret files; no service-role key or token pattern appears in tracked content.

### Success Criteria:

#### Automated Verification:

- [ ] Remotes are correct: `git remote -v` shows `origin` → GitHub and `bitbucket` → Bitbucket
- [ ] No secret files tracked: `git ls-files | grep -E 'local.properties|sdkconfig|\.env.local'` is empty
- [ ] All branches/tags pushed: `git push origin --all` and `--tags` report up-to-date

#### Manual Verification:

- [ ] GitHub repo exists, is **private**, and `gh repo view --web` shows the pushed history
- [ ] A scan of the tracked tree confirms no credential/token is present before the repo is later made public

**Implementation Note**: After automated checks pass, pause for human confirmation (repo creation + `gh auth` are human-gated) before Phase 2.

---

## Phase 2: Android distribution prerequisites (Firebase + stable debug signing)

### Overview

Stand up Firebase App Distribution (human-gated console work) and make the Android debug build reproducibly signed so successive CI uploads update the same install.

### Changes Required:

#### 1. Committed debug keystore + signing config

**File**: `SmartChessboard/androidApp/distribution-debug.keystore` (new) + `SmartChessboard/androidApp/build.gradle.kts`

**Intent**: Sign `assembleDebug` with a repo-committed, throwaway debug keystore so every CI runner produces an identically-signed APK (FAD treats it as an update, not a new app). Debug keystores are non-secret by convention (password `android`), safe for a public repo.

**Contract**: Add a `signingConfigs.getByName("debug")` pointing at the committed keystore; `assembleDebug` uses it. Generate with:
```bash
keytool -genkey -v -keystore SmartChessboard/androidApp/distribution-debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

#### 2. Firebase App Distribution setup (console — human-gated)

**File**: none (external console)

**Intent**: Create the Firebase project + an Android app matching `org.rurbaniak.smartchessboard`, a service account with **Firebase App Distribution Admin**, and a default tester group, producing `FIREBASE_APP_ID` and the service-account JSON the workflow needs.

**Contract**: `FIREBASE_APP_ID` (string) and `FIREBASE_SERVICE_ACCOUNT` (JSON) captured for GH Secrets; at least one tester in a named group.

### Success Criteria:

#### Automated Verification:

- [ ] Local `ANDROID_HOME=... ./gradlew :androidApp:assembleDebug --no-daemon` produces an APK
- [ ] APK signer cert is the committed keystore's (stable): `keytool -printcert -jarfile <apk>` matches across two clean builds

#### Manual Verification:

- [ ] Firebase project + Android app (`org.rurbaniak.smartchessboard`) exist in the console
- [ ] Service account JSON with App Distribution Admin generated; `FIREBASE_APP_ID` recorded
- [ ] A tester group exists with at least the owner as a tester

**Implementation Note**: Pause for human confirmation of the Firebase console work before Phase 4 consumes its outputs.

---

## Phase 3: Web deploy pipeline → Cloudflare (manual trigger)

### Overview

A `workflow_dispatch` workflow that builds the WasmJS bundle with Supabase creds injected and deploys it to the existing Cloudflare Worker.

### Changes Required:

#### 1. `web-deploy.yml`

**File**: `.github/workflows/web-deploy.yml` (new)

**Intent**: On manual dispatch, check out, set up JDK 21 + Android SDK (AGP config needs it), build the web distribution injecting Supabase creds as `-P` props, then deploy via wrangler.

**Contract**: `on: workflow_dispatch`. Steps: `actions/checkout` → `actions/setup-java@v4` (temurin 21) → `android-actions/setup-android@v3` → Gradle build → `cloudflare/wrangler-action@v3` (`command: deploy`). Build call:
```bash
./gradlew :webApp:wasmJsBrowserDistribution --no-daemon --console=plain \
  -PSUPABASE_URL="$SUPABASE_URL" -PSUPABASE_ANON_KEY="$SUPABASE_ANON_KEY" \
  -PGOOGLE_SERVER_CLIENT_ID="$GOOGLE_SERVER_CLIENT_ID"
```
wrangler-action uses `apiToken: ${{ secrets.CLOUDFLARE_API_TOKEN }}` + `accountId: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}`; `wrangler.toml` already points `[assets]` at `productionExecutable/`.

#### 2. GitHub Secrets (web)

**File**: none (GitHub Settings → Secrets — human-gated)

**Intent**: Provide the deploy + build secrets the workflow reads.

**Contract**: `CLOUDFLARE_API_TOKEN` (scoped Workers Scripts edit, this project only), `CLOUDFLARE_ACCOUNT_ID`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID`.

### Success Criteria:

#### Automated Verification:

- [ ] Workflow is registered: `gh workflow list` shows `web-deploy.yml`
- [ ] A dispatched run succeeds: `gh workflow run web-deploy.yml` then `gh run watch` exits green
- [ ] Deploy step logs a successful `wrangler deploy` with a deployment URL

#### Manual Verification:

- [ ] The live Worker URL serves the freshly built bundle
- [ ] Sign-in + save-a-game works on the deployed site (proves Supabase creds injected correctly; OPFS persists = COOP/COEP intact)

**Implementation Note**: Pause for human confirmation of the deployed site before Phase 4.

---

## Phase 4: Android → Firebase App Distribution pipeline (manual trigger)

### Overview

A `workflow_dispatch` workflow that builds the debug APK (stable-signed, Supabase creds injected) and uploads it to FAD.

### Changes Required:

#### 1. `android-fad.yml`

**File**: `.github/workflows/android-fad.yml` (new)

**Intent**: On manual dispatch, build `:androidApp:assembleDebug` with creds injected, then upload the APK to the Firebase App Distribution tester group.

**Contract**: `on: workflow_dispatch`. Steps: `actions/checkout` → `actions/setup-java@v4` (temurin 21) → `android-actions/setup-android@v3` → `./gradlew :androidApp:assembleDebug` with the same `-P` Supabase/Google props as Phase 3 → `wzieba/Firebase-Distribution-Github-Action@v1` with `appId: ${{ secrets.FIREBASE_APP_ID }}`, `serviceCredentialsFileContent: ${{ secrets.FIREBASE_SERVICE_ACCOUNT }}`, `groups: <tester-group>`, `file: SmartChessboard/androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

#### 2. GitHub Secrets (Android)

**File**: none (GitHub Settings → Secrets — human-gated)

**Intent**: Provide the FAD upload secrets.

**Contract**: `FIREBASE_APP_ID`, `FIREBASE_SERVICE_ACCOUNT` (JSON) — Supabase/Google secrets from Phase 3 are reused.

### Success Criteria:

#### Automated Verification:

- [ ] Workflow is registered: `gh workflow list` shows `android-fad.yml`
- [ ] A dispatched run succeeds: `gh workflow run android-fad.yml` then `gh run watch` exits green
- [ ] Upload step logs a successful FAD release for `org.rurbaniak.smartchessboard`

#### Manual Verification:

- [ ] A tester receives the FAD email / App Tester notification
- [ ] The APK installs on an Android phone and launches (sign-in works — creds injected)
- [ ] A second dispatched run updates the existing install rather than installing a second app (confirms stable signing)

**Implementation Note**: Pause for human confirmation that the APK reached a device before Phase 5.

---

## Phase 5: History scrub + flip to public (gated)

### Overview

Once the user finalizes the removal list, remove those paths from all history, force-push the clean history, and make the repo public. Destructive and one-way — runs last, behind an explicit gate.

### Changes Required:

#### 1. Install `git-filter-repo`

**File**: none (toolchain)

**Intent**: Provide the rewrite tool.

**Contract**: `brew install git-filter-repo`; `git filter-repo --version` works.

#### 2. History rewrite

**File**: git history (all commits)

**Intent**: Remove the confirmed paths (`10xDevsLekcje/` + the user's finalized list) from every commit so no checkout of any commit contains them.

**Contract**: run on a fresh mirror/clone; `--invert-paths` deletes the named paths:
```bash
git filter-repo --path 10xDevsLekcje/ --path <user-path-2> --path <user-path-N> --invert-paths
```
Then re-add remotes (filter-repo drops them) and force-push:
```bash
git remote add origin git@github.com:<user>/smartchessboard.git
git push --force --all origin && git push --force --tags origin
```
Bitbucket is intentionally not force-pushed (kept as pre-scrub backup).

#### 3. Flip visibility + doc reconciliation

**File**: repo visibility + `context/foundation/roadmap.md` + `docs/vacation-workflow-todo.md`

**Intent**: Make the repo public and update the docs to reflect that CI + FAD landed and history was scrubbed (noting the SHA-rewrite consequence).

**Contract**: `gh repo edit <user>/smartchessboard --visibility public --accept-visibility-change-consequences`; roadmap `## Parked` "CI pipeline" + "mobile distribution" notes updated to reference this change; `docs/vacation-workflow-todo.md` marked done (or rewritten to `docs/vacation-workflow.md`).

### Success Criteria:

#### Automated Verification:

- [ ] Removed paths absent from all history: `git log --all --oneline -- 10xDevsLekcje/` (and each user path) returns nothing
- [ ] Force-push to GitHub `origin` completes; `git ls-files | grep -E '<removed paths>'` is empty
- [ ] Repo visibility is public: `gh repo view --json visibility` reports `PUBLIC`

#### Manual Verification:

- [ ] User has provided the final removal path list and explicitly approved the destructive rewrite + force-push
- [ ] Spot-check an old commit on GitHub — the removed paths are gone there too
- [ ] Docs (roadmap Parked, vacation-workflow-todo) reflect the landed state

**Implementation Note**: This phase runs only on explicit user go-ahead with a finalized path list. It rewrites all commit SHAs; recorded SHAs in the context docs will no longer resolve (accepted, audit-only).

---

## Testing Strategy

### Automated (CI / local):

- Both workflows dispatch green (`gh run watch`).
- Web build produces `productionExecutable/` with `_headers`; Android build produces a stably-signed debug APK.
- Post-scrub `git log --all -- <path>` is empty for every removed path.

### Manual:

1. Dispatch `web-deploy.yml`; open the Worker URL, sign in, save a game, reload — persists.
2. Dispatch `android-fad.yml`; install the APK from the FAD link on a phone, launch, sign in.
3. Re-dispatch `android-fad.yml`; confirm the install updates in place (stable signing).
4. After the scrub, verify removed paths are gone at HEAD and at an old commit on GitHub; confirm public visibility.

## Migration Notes

- `origin` moves from Bitbucket to GitHub; `bitbucket` remote retained. Existing local clones must update remotes.
- The Phase 5 force-push rewrites history — any other clone must re-clone afterward. Bitbucket keeps the pre-scrub history as backup.

## References

- Change identity + locked decisions: `context/changes/github-ci-and-distribution/change.md`
- CI sketch (sections A/B/C): `docs/vacation-workflow-todo.md`
- Web host + operational story: `context/foundation/infrastructure.md`
- Secret-hygiene + COOP/COEP rules: `context/foundation/lessons.md`
- BuildKonfig injection: `SmartChessboard/shared/build.gradle.kts:15-33,143-148`
- Android build config: `SmartChessboard/androidApp/build.gradle.kts`

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: GitHub repo (private) + origin switch

#### Automated

- [ ] 1.1 Remotes correct: `git remote -v` shows origin→GitHub, bitbucket→Bitbucket
- [ ] 1.2 No secret files tracked (`git ls-files` grep empty)
- [ ] 1.3 All branches/tags pushed to origin

#### Manual

- [ ] 1.4 GitHub repo exists, is private, history visible via `gh repo view --web`
- [ ] 1.5 Tracked tree confirmed credential-free ahead of eventual public flip

### Phase 2: Android distribution prerequisites (Firebase + stable debug signing)

#### Automated

- [ ] 2.1 `:androidApp:assembleDebug` produces an APK
- [ ] 2.2 APK signer cert stable across two clean builds (`keytool -printcert -jarfile`)

#### Manual

- [ ] 2.3 Firebase project + Android app (`org.rurbaniak.smartchessboard`) exist
- [ ] 2.4 Service account (App Distribution Admin) JSON + `FIREBASE_APP_ID` captured
- [ ] 2.5 Tester group exists with ≥1 tester

### Phase 3: Web deploy pipeline → Cloudflare (manual trigger)

#### Automated

- [ ] 3.1 `gh workflow list` shows `web-deploy.yml`
- [ ] 3.2 Dispatched run succeeds (`gh run watch` green)
- [ ] 3.3 Deploy step logs a successful `wrangler deploy` with URL

#### Manual

- [ ] 3.4 Live Worker URL serves the fresh bundle
- [ ] 3.5 Sign-in + save-a-game works on the deployed site

### Phase 4: Android → Firebase App Distribution pipeline (manual trigger)

#### Automated

- [ ] 4.1 `gh workflow list` shows `android-fad.yml`
- [ ] 4.2 Dispatched run succeeds (`gh run watch` green)
- [ ] 4.3 Upload step logs a successful FAD release

#### Manual

- [ ] 4.4 Tester receives FAD email / App Tester notification
- [ ] 4.5 APK installs and launches on a phone (sign-in works)
- [ ] 4.6 Second run updates the existing install (stable signing confirmed)

### Phase 5: History scrub + flip to public (gated)

#### Automated

- [ ] 5.1 Removed paths absent from all history (`git log --all -- <path>` empty)
- [ ] 5.2 Force-push to GitHub origin completes; `git ls-files` grep of removed paths empty
- [ ] 5.3 Repo visibility PUBLIC (`gh repo view --json visibility`)

#### Manual

- [ ] 5.4 User provided final removal list + approved the destructive rewrite/force-push
- [ ] 5.5 Old commit on GitHub spot-checked — removed paths gone there too
- [ ] 5.6 Docs (roadmap Parked, vacation-workflow-todo) reconciled
