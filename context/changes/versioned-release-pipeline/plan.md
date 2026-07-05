# Versioned Release Pipeline Implementation Plan

## Overview

Stand up a **version-tag-triggered release pipeline**. Pushing a `v*` tag onto an
already-merged commit fires two independent GitHub Actions workflows:

1. **`web-deploy.yml`** — builds the WasmJS bundle and deploys it to the existing
   Cloudflare Worker `smart-chessboard-web` via `wrangler deploy`, **behind a human
   approval gate** (GitHub Environment `production` with a required reviewer) that
   pauses only the live-site mutation, not the build.
2. **`android-release.yml`** — builds a stably debug-signed Android APK whose
   `versionName`/`versionCode` are derived from the tag, then creates the GitHub
   Release for that tag with auto-generated notes and attaches the APK as a durable
   downloadable asset.

Nothing about this pipeline touches the protected `main` branch: a tag is a separate
ref class, so it is pushed without colliding with the `main-pr-gate` ruleset, and
creating a Release uses the default `GITHUB_TOKEN` (no PAT, no push to `main`).

## Current State Analysis

Everything the pipeline reuses already exists and is verified (2026-07-05):

- **`.github/workflows/build.yml`** (manual `workflow_dispatch`) already builds both
  artifacts with the exact steps the release workflows will reuse: `:androidApp:assembleDebug`
  → `androidApp/build/outputs/apk/debug/*.apk`, and `:webApp:wasmJsBrowserDistribution`
  → `webApp/build/dist/wasmJs/productionExecutable/`. It does **not** deploy and does
  **not** publish a Release. It stays as the pure-artifact manual path (untouched).
- **`.github/workflows/tests.yml`** is the S-14 PR gate: jobs `test` (name
  `JVM goldens + wasm smokes`) + `gitleaks`, both on `pull_request`. These two exact
  strings are the required-check contexts in the `main-pr-gate` ruleset
  (`docs/reference/contract-surfaces.md` §7). Ubuntu runners ship a preset `ANDROID_HOME`,
  which is why `build.yml`'s web/android jobs need no `android-actions/setup-android`.
- **`SmartChessboard/androidApp/build.gradle.kts`**: no `signingConfigs` block today —
  `assembleDebug` uses each runner's auto-generated debug keystore (signature differs
  across runners). `versionCode = 1`, `versionName = "1.0"` are hard-coded.
- **`SmartChessboard/shared/build.gradle.kts`**: BuildKonfig injects `SUPABASE_URL`,
  `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID` from `local.properties` (dev) or
  `-P`/env (CI), falling back to empty strings. All three are public-by-design (the
  anon key is RLS-protected; `lessons.md` §37). A bare APK/web build boots without them
  (to the sign-in screen); a *functional* release build must inject them.
- **`wrangler.toml`** (repo root): `name = "smart-chessboard-web"`, `[assets] directory
  = ./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable`. Proven manual deploy.
- **`SmartChessboard/webApp/src/webMain/resources/_headers`** — the COOP/COEP header
  file, baked into `productionExecutable/` by the Compose resources copy. CI does **not**
  synthesize it; it must survive into the deployed bundle (`lessons.md` §27: identical
  COOP/COEP on dev server and host).
- **`context/foundation/infrastructure.md`**: deploy auth = scoped `CLOUDFLARE_API_TOKEN`
  (Workers Scripts edit, this project only) + `CLOUDFLARE_ACCOUNT_ID`; approval is
  human-only on `wrangler deploy`.

**Dependency:** this change lands **after `public-repo-and-pr-gate` (S-14)** — it assumes
the repo is public on GitHub, `main` is PR-gated, and `origin` points at GitHub. It adds
nothing to the go-public / history-scrub work.

## Desired End State

A maintainer cuts a release by tagging an already-merged commit on `main`
(`git tag v1.0.0 <sha> && git push origin v1.0.0`) and:

- The **APK is built and the GitHub Release is published automatically** for the tag,
  APK attached, notes auto-generated. Installing `v1.1.0` over an installed `v1.0.0`
  **updates in place** (stable signature + monotonically increasing `versionCode`), no
  uninstall.
- The **web deploy pauses** in the Actions UI for one "Approve" click; after approval,
  the live Worker `smart-chessboard-web` serves the freshly built bundle (COOP/COEP
  `_headers` intact).
- Neither workflow appears as a required PR check; the `main-pr-gate` ruleset is
  unchanged and still gates on exactly `JVM goldens + wasm smokes` + `gitleaks`.

**Verification:** push `v0.0.1-rc`; observe the APK + Release auto-publish while the web
deploy waits; approve; load the production URL and confirm the new bundle; install the
APK on a phone; push a second tag and confirm update-in-place.

### Key Discoveries:

- Existing exact build steps to reuse: `.github/workflows/build.yml:43-44` (APK) and
  `build.yml:73-74` (web bundle).
- `androidApp/build.gradle.kts:43-44` hard-codes `versionCode = 1` / `versionName = "1.0"`
  — the single biggest correctness gap: stable signing alone does **not** enable
  update-in-place; Android rejects an install-over with an equal `versionCode`.
- No `signingConfigs` in `androidApp/build.gradle.kts:26-60` — the `debug` build type
  falls back to the auto debug keystore; overriding `signingConfigs.getByName("debug")`
  to a committed keystore is what stabilizes the signature.
- `_headers` source: `SmartChessboard/webApp/src/webMain/resources/_headers` (baked into
  the bundle) — the deploy must ship the built directory unmodified.
- §7 contract (`docs/reference/contract-surfaces.md`): required-check strings are
  load-bearing; the new workflows trigger on `push: tags`, never `pull_request`, so they
  cannot enter the gate — confirmed, no ruleset change needed.

## What We're NOT Doing

- **Not** touching `build.yml` — it stays as the manual-dispatch pure-artifact path.
- **Not** adding a `workflow_dispatch` dry-run to the release workflows (considered;
  deferred — the first live rehearsal uses a throwaway `-rc` tag on `main`).
- **Not** attaching the web bundle to the Release (web is deploy-only; the APK is the
  artifact worth a durable link).
- **Not** creating a release keystore, Play Store listing, or store distribution
  (debug-signed APK only; store distribution stays parked per `roadmap.md`).
- **Not** creating a new Worker or changing `wrangler.toml`'s target — the existing
  `smart-chessboard-web` is the deploy target.
- **Not** adding the release workflows to the `main-pr-gate` required checks.
- **Not** using a PAT — the default `GITHUB_TOKEN` with `contents: write` creates tags/Releases.
- **Not** enabling GitHub tag-protection rulesets (who may push `v*`) — noted as optional
  hardening, out of scope here.

## Implementation Approach

Land **all pipeline code in a single PR** (Phase 1), merged through the S-14 gate like any
other change. That PR carries: the committed debug keystore, the `signingConfigs` + version
wiring in `androidApp/build.gradle.kts`, and both new workflow files. The human-gated console
setup (GitHub Environment + secrets) is done in the GitHub UI in parallel — it is not code and
rides no PR. Once the PR is merged, Phase 2 exercises the whole pipeline end-to-end by pushing a
throwaway `-rc` tag onto `main`. Phase 3 (optional) adds a Firebase App Distribution job in a
follow-up PR.

The gate is placed on the **deploy job, not the build job**: `web-deploy.yml` splits into an
ungated `web-build` (builds + uploads the bundle artifact) and a gated `web-deploy`
(`environment: production`, downloads the artifact, runs `wrangler deploy`). This realizes
"tag → build runs automatically, the live deploy waits for one Approve click" and keeps the
approval wait to seconds, not minutes.

Secret scoping follows least-privilege: the Cloudflare token/account live **inside** the gated
`production` Environment (only `web-deploy` opts in, so only it can read them and it pauses for
approval); the public-by-design Supabase/Google build credentials live at **repo level** (both
build jobs read them with no gate, so the APK publishes immediately).

## Critical Implementation Details

- **`versionCode` scheme (correctness-critical, other phases depend on it).** CI derives both
  values from `${{ github.ref_name }}`: `versionName` = the tag minus the leading `v`
  (e.g. `1.2.0`, or `0.0.1-rc` for pre-releases); `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH`
  computed from the core semver (pre-release suffix stripped). Constraint: `MINOR` and `PATCH`
  must stay `< 100`. **Caveat:** two pre-releases of the same core version (`v0.0.1-rc1`,
  `v0.0.1-rc2`) map to the same `versionCode` — acceptable for RC testing (uninstall between RCs,
  or bump the patch). Gradle reads `appVersionName`/`appVersionCode` project properties, falling
  back to the current `"1.0"`/`1` so local builds and the `tests.yml` gate are unaffected.

- **Committed keystore vs the `gitleaks` PR gate.** The Phase-1 PR adds a binary
  `debug.keystore` (throwaway, password `android` — the non-secret Android default). The PR runs
  through the S-14 gate, including `gitleaks` full-history scan. If gitleaks flags the keystore or
  its literal password, add a scoped allowlist entry (path or fingerprint) to `.gitleaks.toml` in
  the same PR so the gate passes — the keystore is intentionally public and carries no real secret.

- **Tag-triggered workflows run from the definition at the tagged commit.** A `v*` workflow only
  exists once its file is on the commit the tag points at. Therefore the end-to-end test (Phase 2)
  must run **after** the Phase-1 PR merges to `main`; tag a commit on `main` that already includes
  the workflows. Do not expect a tag on the feature branch to rehearse the merged pipeline.

- **`_headers` must survive to the deployed bundle.** `web-deploy` deploys the built
  `productionExecutable/` directory unmodified; add a guard step asserting `_headers` is present in
  the artifact before `wrangler deploy`, so a COOP/COEP regression fails loudly rather than
  silently degrading OPFS persistence on the live site (`lessons.md` §27).

## Phase 1: Release pipeline — signing, tag-versioning, and both workflows (one PR)

### Overview

Everything that is code lands here in a single PR: stable debug signing, tag-derived versioning,
`web-deploy.yml`, and `android-release.yml`. The human-gated GitHub console setup (Environment +
secrets) is completed in parallel and verified as a manual item — it is a prerequisite for Phase 2
but ships no code.

### Changes Required:

#### 1. Committed throwaway debug keystore

**File**: `SmartChessboard/androidApp/debug.keystore` (new binary)

**Intent**: Give every runner an identical signing key so the APK signature is stable across
releases, enabling update-in-place. Generated once with the standard Android debug conventions and
committed (non-secret by convention).

**Contract**: RSA-2048, alias `androiddebugkey`, store/key password `android`, long validity.
Generation:
```bash
keytool -genkeypair -v -keystore SmartChessboard/androidApp/debug.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Android Debug,O=Android,C=US"
```
Confirm with the user before committing the binary. If `gitleaks` flags it on the PR, allowlist the
path in `.gitleaks.toml`.

#### 2. Stable signing + tag-derived version wiring

**File**: `SmartChessboard/androidApp/build.gradle.kts`

**Intent**: Point the `debug` signing config at the committed keystore, and source
`versionName`/`versionCode` from project properties with the current values as fallbacks.

**Contract**: Add a `signingConfigs { getByName("debug") { … } }` referencing `debug.keystore`
(the `debug` build type already uses the `debug` signing config). Replace the literals in
`defaultConfig`:
```kotlin
versionName = (project.findProperty("appVersionName") as String?) ?: "1.0"
versionCode = (project.findProperty("appVersionCode") as String?)?.toInt() ?: 1
```

#### 3. `web-deploy.yml` — gated Cloudflare deploy on `v*` tags

**File**: `.github/workflows/web-deploy.yml` (new)

**Intent**: On a `v*` tag, build the functional web bundle (ungated) and deploy it to the existing
Worker behind the approval gate.

**Contract**: `on: push: tags: ['v*']`. Two jobs:
- `web-build` (ubuntu, no environment): checkout → JDK 21 (temurin) → `gradle/actions/setup-gradle`
  → `./gradlew :webApp:wasmJsBrowserDistribution -PSUPABASE_URL=… -PSUPABASE_ANON_KEY=…
  -PGOOGLE_SERVER_CLIENT_ID=…` (repo-level secrets) → `actions/upload-artifact` of
  `SmartChessboard/webApp/build/dist/wasmJs/productionExecutable/`.
- `web-deploy` (`needs: web-build`, `environment: production`): checkout (for `wrangler.toml`) →
  `actions/download-artifact` into the same `productionExecutable/` path → guard step asserting
  `_headers` exists there → `cloudflare/wrangler-action@v3` with `apiToken`/`accountId` from the
  **Environment** secrets and `command: deploy`.

Mirror `build.yml`'s web job for JDK/Gradle setup (no `setup-android` — `ANDROID_HOME` is preset on
ubuntu runners).

#### 4. `android-release.yml` — APK build + GitHub Release on `v*` tags

**File**: `.github/workflows/android-release.yml` (new)

**Intent**: On a `v*` tag, build the stably-signed, tag-versioned APK and publish the GitHub Release
with the APK attached. Ungated (runs to completion automatically).

**Contract**: `on: push: tags: ['v*']`; `permissions: contents: write`. One job `android-release`
(ubuntu): checkout → JDK 21 → setup-gradle → a shell step deriving `APP_VERSION_NAME`/`APP_VERSION_CODE`
from `${{ github.ref_name }}` per the versionCode scheme above → `./gradlew :androidApp:assembleDebug
-PappVersionName=… -PappVersionCode=… -PSUPABASE_URL=… -PSUPABASE_ANON_KEY=… -PGOOGLE_SERVER_CLIENT_ID=…`
(repo-level secrets) → `softprops/action-gh-release@v2` with `tag_name: ${{ github.ref_name }}`,
`generate_release_notes: true`, `files: SmartChessboard/androidApp/build/outputs/apk/debug/*.apk`
(uses the default `GITHUB_TOKEN`).

#### 5. Human-gated GitHub console setup (no code)

**Where**: GitHub repo Settings → Environments + Secrets.

**Intent**: Provision the approval gate and the credentials the workflows read.

**Contract**:
- Create Environment **`production`** with the owner as a **required reviewer**.
- **Environment `production` secrets:** `CLOUDFLARE_API_TOKEN` (scoped: Workers Scripts edit, this
  project only — no DNS/billing/unrelated secrets), `CLOUDFLARE_ACCOUNT_ID`.
- **Repo-level secrets:** `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID`.
- The scoped Cloudflare API token is created by the user in the Cloudflare dashboard (human-only).

### Success Criteria:

#### Automated Verification:

- APK assembles with injected version: `cd SmartChessboard && ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :androidApp:assembleDebug -PappVersionName=1.2.0 -PappVersionCode=10200 --console=plain --no-daemon`
- Signature is stable across two clean builds: `apksigner verify --print-certs` (or `keytool -printcert -jarfile`) on the APK from two separate clean `assembleDebug` runs yields the **same** SHA-256 signer cert.
- Web bundle still builds: `./gradlew :webApp:wasmJsBrowserDistribution --console=plain --no-daemon` and `_headers` is present in `webApp/build/dist/wasmJs/productionExecutable/`.
- Both workflow files are valid YAML / lint clean (e.g. `actionlint` if available) and declare `on: push: tags: ['v*']`.
- The existing S-14 gate is green on the PR: `JVM goldens + wasm smokes` + `gitleaks` both pass (keystore allowlisted if needed).

#### Manual Verification:

- GitHub Environment `production` exists with the owner as required reviewer.
- Environment secrets `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID` set; repo secrets `SUPABASE_URL` + `SUPABASE_ANON_KEY` + `GOOGLE_SERVER_CLIENT_ID` set.
- The scoped Cloudflare API token is confirmed limited to Workers Scripts edit for this project (no DNS/billing).
- The committed `debug.keystore` was reviewed and approved before commit.

**Implementation Note**: After Phase 1's PR is merged and the console setup is confirmed, pause for
manual confirmation before Phase 2 (Phase 2 requires the workflows to be on `main`).

---

## Phase 2: End-to-end release verification

### Overview

Exercise the whole pipeline against a throwaway `-rc` tag on `main`, proving the gated deploy, the
auto-published Release, and update-in-place. No code (unless a defect is found).

### Changes Required:

#### 1. Cut a rehearsal release

**Where**: local git + GitHub Actions UI + a phone.

**Intent**: Drive one real release through the pipeline and observe each contract.

**Contract**: `git tag v0.0.1-rc <sha-on-main> && git push origin v0.0.1-rc`. Then observe both
workflows fire; approve the `web-deploy` gate; verify outcomes. Delete the `-rc` tag/Release
afterward. If any step fails, fix in a follow-up PR and re-tag (`v0.0.2-rc`).

### Success Criteria:

#### Automated Verification:

- Both workflow runs appear for the tag: `gh run list --workflow=web-deploy.yml` and `gh run list --workflow=android-release.yml` show the `v0.0.1-rc` run.
- The Release exists with the APK asset: `gh release view v0.0.1-rc` lists the `*.apk`.
- `android-release.yml` completes without waiting (ungated); `web-deploy`'s `web-deploy` job reports "waiting" until approval.

#### Manual Verification:

- The `web-deploy` job pauses at the approval gate; after clicking Approve, `wrangler deploy` succeeds and the production URL serves the freshly built bundle (sign-in works → Supabase creds injected; OPFS persists → COOP/COEP `_headers` intact).
- The APK downloads from the Release and installs on a phone; the app boots and signs in.
- Pushing a second tag (`v0.0.2-rc`) produces an APK that **installs in place** over `v0.0.1-rc` with no uninstall (stable signature + higher `versionCode`).
- The `main-pr-gate` required checks are unchanged; neither release workflow appears as a PR check.

**Implementation Note**: This phase is verification-only; record outcomes and pause for user
confirmation before considering the pipeline "done". Phase 3 is optional.

---

## Phase 3: (Optional) Firebase App Distribution

### Overview

Add a tester-distribution channel so friends get an install link + update notifications instead of a
manual Release download. Optional, human-gated setup, not required for the change to be "done".
Carried over from the retired `github-ci-and-distribution` plan. Pursued in a separate follow-up PR.

### Changes Required:

#### 1. `android-fad` job (or workflow)

**File**: `.github/workflows/android-release.yml` (add a job) or a new `android-fad.yml`

**Intent**: After the APK is built, upload it to a Firebase App Distribution tester group.

**Contract**: A job that reuses the built APK and calls the Firebase App Distribution GitHub action
(or `firebase appdistribution:distribute`) with `FIREBASE_APP_ID` + a `FIREBASE_SERVICE_ACCOUNT`
(JSON) credential and a tester group. Both secrets are repo-level (no live-web mutation → no gate).

#### 2. Human-gated Firebase console setup (no code)

**Where**: Firebase console.

**Intent**: Provision the Firebase project/app, a service account with App Distribution permissions,
and a tester group.

**Contract**: Create the Firebase project + Android app (applicationId `org.rurbaniak.smartchessboard`),
a service-account JSON with the App Distribution admin role, and a tester group; set `FIREBASE_APP_ID`
+ `FIREBASE_SERVICE_ACCOUNT` as repo secrets.

### Success Criteria:

#### Automated Verification:

- On a `v*` tag, the `android-fad` job completes and the FAD CLI/action reports a successful upload.

#### Manual Verification:

- A tester in the group receives the install/update notification and can install the APK from the FAD link.

**Implementation Note**: Optional phase; only pursue if tester push-distribution is wanted. Human
console setup gates it.

---

## Testing Strategy

### Unit / build-level:

- Stable-signature check: two clean `assembleDebug` runs → identical signer cert (Phase 1 automated).
- Version injection: `-PappVersionName/-PappVersionCode` reflected in the built APK's manifest
  (`aapt dump badging` or `apkanalyzer`), and local no-property build still yields `1.0`/`1`.
- `_headers` presence assertion in the web artifact before deploy.

### Integration (end-to-end, Phase 2):

- Full tag → dual-workflow → gated-deploy → Release path against `v0.0.1-rc`.
- Update-in-place across two tags.
- Gate isolation: release workflows never report as PR checks; `main-pr-gate` unchanged.

### Manual Testing Steps:

1. Merge the Phase-1 PR through the S-14 gate; complete the console setup.
2. `git push origin v0.0.1-rc`; watch both workflows in the Actions tab.
3. Confirm `android-release` runs to completion and publishes the Release + APK unattended.
4. Confirm `web-deploy` pauses; click Approve; confirm the live URL serves the new bundle.
5. Download + install the APK; sign in.
6. Push `v0.0.2-rc`; confirm update-in-place; delete the rehearsal tags/Releases.

## Performance Considerations

Negligible. Two extra tag-triggered workflow runs per release (minutes each on ubuntu). The gate adds
only human wait time on the deploy job; the build runs immediately. No change to the PR-path CI cost.

## Migration Notes

No data migration. The only stateful change is the Android signing identity: moving from each runner's
auto debug keystore to the committed one changes the signature, so an APK from this pipeline will **not**
install over a previously side-loaded auto-signed debug build — uninstall any pre-pipeline debug build
once. From the first pipeline release onward, signatures are stable and updates install in place.

## References

- Change identity: `context/changes/versioned-release-pipeline/change.md`
- Infrastructure decision: `context/foundation/infrastructure.md` (Cloudflare Workers Static Assets)
- CI required-check contract: `docs/reference/contract-surfaces.md` §7
- Reusable build steps: `.github/workflows/build.yml`; PR gate: `.github/workflows/tests.yml`
- Rules: `context/foundation/lessons.md` §27 (COOP/COEP both surfaces), §37 (no secrets in the static bundle)

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles.

### Phase 1: Release pipeline — signing, tag-versioning, and both workflows (one PR)

#### Automated

- [x] 1.1 APK assembles with injected version (`-PappVersionName/-PappVersionCode`)
- [x] 1.2 Signature stable across two clean `assembleDebug` builds (same signer cert)
- [x] 1.3 Web bundle builds and `_headers` present in `productionExecutable/`
- [x] 1.4 Both workflow files valid/lint-clean and declare `on: push: tags: ['v*']`
- [ ] 1.5 S-14 PR gate green (`JVM goldens + wasm smokes` + `gitleaks`; keystore allowlisted if needed)

#### Manual

- [x] 1.6 Environment `production` exists with owner as required reviewer
- [x] 1.7 Environment secrets (`CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_ACCOUNT_ID`) + repo secrets (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID`) set
- [x] 1.8 Scoped Cloudflare API token confirmed (Workers Scripts edit, this project only)
- [x] 1.9 Committed `debug.keystore` reviewed and approved before commit

### Phase 2: End-to-end release verification

#### Automated

- [ ] 2.1 Both workflow runs appear for the `v0.0.1-rc` tag
- [ ] 2.2 Release exists with the APK asset (`gh release view`)
- [ ] 2.3 `android-release` completes unattended; `web-deploy` waits at the gate

#### Manual

- [ ] 2.4 Approve gate → `wrangler deploy` succeeds → production URL serves the new bundle (sign-in + OPFS OK)
- [ ] 2.5 APK downloads from the Release and installs + signs in on a phone
- [ ] 2.6 Second tag installs in place over the first (stable signature + higher `versionCode`)
- [ ] 2.7 `main-pr-gate` required checks unchanged; no release workflow appears as a PR check

### Phase 3: (Optional) Firebase App Distribution

#### Automated

- [ ] 3.1 `android-fad` job completes and reports a successful upload on a `v*` tag

#### Manual

- [ ] 3.2 A tester receives the notification and installs the APK from the FAD link
