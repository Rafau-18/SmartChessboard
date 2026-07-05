---
change_id: versioned-release-pipeline
title: Versioned release pipeline — tag → gated Cloudflare deploy + APK GitHub Release
status: new
created: 2026-07-05
updated: 2026-07-05
archived_at: null
---

## Notes

<!-- Free-form notes for this change: links, ad-hoc context, decisions that don't belong in research/frame/plan. -->

### What this is

Infrastructure change (CI/CD release pipeline) — **not** a vertical product slice, so it
is intentionally not an `S-NN` in the roadmap (no PRD FR/US trace), same posture as
`github-ci-and-distribution`. It stands up a **version-tag-triggered release pipeline** that,
on a `v*` tag push, (a) deploys the WasmJS web bundle to the existing Cloudflare Worker via
`wrangler deploy` **behind a human approval gate**, and (b) builds the Android debug APK and
publishes it as a downloadable **GitHub Release** asset.

**Supersedes `github-ci-and-distribution`** (status `planned`, never implemented, 2026-07-02).
That change bundled web→Cloudflare deploy + Android→**Firebase App Distribution** + go-public +
history scrub. Two things changed since:

- Its go-public + history-scrub half is now owned by **`public-repo-and-pr-gate`** (S-14).
- Firebase App Distribution is replaced by the simpler **downloadable APK / GitHub Release**
  model (user decision 2026-07-05). Firebase FAD is folded in here as an **optional final
  nice-to-have phase**, not a requirement.

So this change owns the CI web-deploy + Android-artifact story; `github-ci-and-distribution`
is retired (a superseded note points here).

### Dependency / sequencing

Lands **after `public-repo-and-pr-gate` (S-14) completes** — user intent: "wypchniemy jak tamto
się zakończy". Concretely this change needs, already in place from S-14:

- The repo on **GitHub** with `origin` pointing there (the workflows + Release publishing are
  GitHub-native).
- The repo **public** (GitHub Environments with required reviewers + tag-triggered releases are
  free on public repos).
- The **PR-gated `main`** posture (tags point at commits already merged green through the gate).

This change adds nothing to the go-public / scrub work — it assumes it done.

### Decisions locked (2026-07-05)

- **Trigger = version tag `v*` triggers the pipeline** (`on: push: tags: ['v*']`), NOT
  pipeline-creates-tag. You decide a release, tag an already-merged commit, push the tag; CI
  runs. Rationale under "How the tag interacts with a protected `main`" below.
  - **Optional** `workflow_dispatch` manual fallback (test deploy without cutting a release) is
    a `/10x-plan` decision, not locked here.
- **Manual gate = GitHub Environment `production` with a required reviewer** (the owner) on the
  **`wrangler deploy` job only**. Tag → build + APK + GitHub Release run automatically; the
  live-site deploy **pauses for one "Approve" click** in the Actions UI before it runs. Gates
  exactly the one step that mutates the live web; matches `infrastructure.md` ("Approval:
  human-only … `wrangler deploy` after the gated first deploy"). Native, free on public repos,
  does not touch `main` branch protection.
- **APK delivery = GitHub Release asset, debug-signed.** APK is attached to the GitHub Release
  the tag creates (durable download link, not a retention-limited Actions artifact). Signed with
  a **committed throwaway debug keystore** (password `android`, non-secret by convention) so
  every release has a **stable signature** and installs update in place (v1.0.0 → v1.1.0 without
  uninstall) — same reasoning as FAD's stable-signing need. No release keystore / Play Store in
  scope.
- **Web deploy target = production Cloudflare Worker** `smart-chessboard-web` (the existing one),
  via `wrangler deploy` of the freshly built `productionExecutable/` bundle. No new Worker.

### How the tag interacts with a protected `main`

Branch protection protects **branches, not tags** — `v1.0.0` is a tag ref, a different ref
class, so `main`'s protection rules do not block pushing it.

1. Change lands on `main` normally, via a PR with green CI (the S-14 gate). Nothing is bypassed.
2. When `main` is a release, locally: `git tag v1.0.0 <sha-on-main>` then `git push origin v1.0.0`
   — this pushes a **separate tag ref**, not a commit onto `main`, so it passes despite the gate.
3. The tag push fires the release workflow (`on: push: tags: ['v*']`).

Consequences to honor in the plan:
- **Why tag-triggers-pipeline, not pipeline-creates-tag:** if the pipeline pushed a tag or bumped
  a version file on `main`, *that* push could collide with `main` protection (a commit onto a
  protected branch) and need a token to bypass. Tag-triggers-pipeline never touches `main`.
- The Release-creating job needs **`permissions: contents: write`**; the default `GITHUB_TOKEN`
  can create tags/releases — **no PAT**, and creating a Release is not a push to `main`, so no
  branch-protection collision.
- Optional hardening: GitHub **tag protection rulesets** (who may push `v*` tags) — off by
  default, not required; note as an option.

### Baseline facts (verified 2026-07-05, before history rewrite)

- **`build.yml` already builds both artifacts** on `workflow_dispatch`: `:androidApp:assembleDebug`
  → `android-debug-apk` artifact, and `:webApp:wasmJsBrowserDistribution` → `web-production-bundle`
  artifact (retention 14 days). It does **not** deploy and does **not** publish a Release. The
  release workflow can **reuse `build.yml`'s exact build steps** and add: deploy + Release publish.
  `/10x-plan` decides whether `build.yml` stays as the manual-artifact path, is refactored into a
  reusable workflow, or is partly absorbed.
- **`wrangler.toml`** at repo root: `name = "smart-chessboard-web"`, `preview_urls = true`,
  `[assets] directory = ./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable`. Proven
  manual deploy. `_headers` (COOP/COEP) is a source resource baked into the build output — CI does
  not synthesize it.
- **Deploy pipeline shape** (per `infrastructure.md`): Cloudflare cannot build a KMP project, so
  the pipeline is "GH Actions builds the wasm bundle → `wrangler deploy` the artifact." Deploy auth
  = scoped `CLOUDFLARE_API_TOKEN` (Workers Scripts edit, this project only) + `CLOUDFLARE_ACCOUNT_ID`.
- **Build-time secrets via BuildKonfig** (`SmartChessboard/shared/build.gradle.kts`): `SUPABASE_URL`,
  `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID` injected as `-P` props / env in CI; `local.properties`
  is gitignored. `SupabaseClientProvider.kt` throws on empty URL/anon key, so a *functional* release
  build (web that signs in, APK that signs in) must inject them. A bare debug APK builds without them
  (boots to sign-in) — but a downloadable release APK should have them so it actually works.
- **Android build**: `applicationId = org.rurbaniak.smartchessboard`; AGP 9.0.1 / Kotlin 2.4.0 /
  compileSdk 36 / minSdk 24. No `signingConfigs` today — `assembleDebug` uses the per-machine
  auto debug keystore (unstable signature across runners) → the committed-keystore decision above
  fixes it. Multi-module: `:androidApp` applies `com.android.application`, so even `:webApp` tasks
  may demand `ANDROID_HOME` at Gradle **configuration** time — the web-deploy job likely needs
  `android-actions/setup-android` too (as `build.yml`'s web job does not, but note it may bite).

### Phase sketch (for `/10x-plan` to firm up)

1. **Stable debug signing** — commit a throwaway debug keystore + a `signingConfigs.getByName("debug")`
   in `SmartChessboard/androidApp/build.gradle.kts` so `assembleDebug` produces an identically-signed
   APK on every runner. Verify signer cert is stable across two clean builds.
2. **Release pipeline `release.yml`** on `v*` tags — one workflow, jobs:
   - `web-deploy`: build wasm bundle (inject Supabase/Google creds) → `wrangler deploy`, pinned to
     `environment: production` (required-reviewer approval gate).
   - `android-release`: build `:androidApp:assembleDebug` (creds injected, stable signing) → create
     the GitHub Release for the tag → attach the APK as an asset. `permissions: contents: write`.
   - (release creation via `gh release create` / `softprops/action-gh-release`; APK asset attached).
3. **GitHub Environment `production` + secrets** (human-gated console): create the `production`
   Environment with the owner as required reviewer; add GH Secrets: `CLOUDFLARE_API_TOKEN`,
   `CLOUDFLARE_ACCOUNT_ID`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID`.
4. **End-to-end release** — push a test tag (e.g. `v0.0.1-rc`), watch: APK + Release auto-publish,
   deploy job pauses → approve → live Worker serves the fresh bundle; download the APK from the
   Release and install on a phone; push a second tag → APK updates in place (stable signing).
5. **(Nice-to-have, optional, later) Firebase App Distribution** — add an `android-fad` job/channel
   that also uploads the APK to a FAD tester group, for friends who prefer an install link + update
   notifications over a manual Release download. Not required for the change to be "done"; carried
   over from the retired `github-ci-and-distribution` plan (its Phase 2/4). Needs `FIREBASE_APP_ID`
   + `FIREBASE_SERVICE_ACCOUNT` secrets and console setup — all human-gated.

### Open items for `/10x-plan`

- One `release.yml` (jobs) vs separate `web-deploy.yml` + `android-release.yml`. Leaning: one
  `release.yml` so a tag is a single atomic release event.
- Add `workflow_dispatch` manual fallback to `release.yml` (with a `ref`/dry-run input) or keep
  `build.yml` as the sole manual path? Leaning: keep `build.yml` for pure artifacts, optionally
  add a `workflow_dispatch` to `release.yml` for a no-tag test deploy.
- Whether to also publish the **web bundle** as a Release asset (zip) alongside the APK, or leave
  web as deploy-only. Leaning: deploy-only; the APK is the artifact worth a durable link.
- Release notes body: auto-generated (`generate_release_notes: true`) vs a template. Leaning: auto.
- Whether the `web-deploy` job needs `android-actions/setup-android` (AGP config-time `ANDROID_HOME`
  demand for a `:webApp` task in a multi-module build) — confirm empirically.
- `wrangler-action@v3` vs `npx wrangler deploy` step. Leaning: `cloudflare/wrangler-action@v3`.
- Interaction with S-14's required-check set: the release workflow runs on tags, not `pull_request`,
  so it is **not** a required PR check — confirm it does not accidentally get added to the gate.

### Human-gated steps (agent cannot do unattended)

Create the GitHub Environment `production` + add the required reviewer; add all GH Secrets
(Cloudflare token/account, Supabase URL + anon key, Google client id); the first Cloudflare deploy
auth; **clicking "Approve"** on each gated deploy; (if Phase 5) creating the Firebase project/app +
service account + tester group. Generating + committing the throwaway debug keystore is
agent-doable (non-secret by convention) but confirm before committing a binary.
