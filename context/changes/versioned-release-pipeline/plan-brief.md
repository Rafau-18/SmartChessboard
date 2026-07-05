# Versioned Release Pipeline — Plan Brief

> Full plan: `context/changes/versioned-release-pipeline/plan.md`

## What & Why

Stand up a version-tag-triggered release pipeline. Pushing a `v*` tag onto an already-merged commit
deploys the WasmJS web bundle to the existing Cloudflare Worker **behind a human approval gate** and
builds a stably-signed, tag-versioned Android APK published as a **downloadable GitHub Release** asset.
It turns "cutting a release" into one tag push, with the one live-web mutation guarded by a single
Approve click. Supersedes the retired `github-ci-and-distribution`; the go-public/scrub half is owned by S-14.

## Starting Point

`build.yml` (manual dispatch) already builds both artifacts with the exact steps to reuse, but never
deploys or publishes a Release. Android has no `signingConfigs` (unstable per-runner debug signature)
and hard-coded `versionCode=1`/`versionName="1.0"`. The web deploy target (`wrangler.toml` →
`smart-chessboard-web`) is a proven manual deploy. S-14 already made the repo public and `main` PR-gated.

## Desired End State

`git push origin v1.0.0` → the APK + GitHub Release publish automatically (installs update in place),
while the web deploy pauses for one Approve click, then serves the fresh bundle on the live Worker.
The `main-pr-gate` required checks stay exactly `JVM goldens + wasm smokes` + `gitleaks` — the release
workflows run on tags, never on PRs, so they never enter the gate.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Trigger | `on: push: tags: ['v*']` | Tag a merged commit; never touches protected `main`. | Change |
| Workflow topology | Two workflows (`web-deploy.yml` + `android-release.yml`) | User chose split for independent reruns/smaller files. | Plan |
| Approval gate | GH Environment `production`, required reviewer, on the **deploy job only** | Build runs automatically; only the live-web mutation waits. | Change |
| Gate placement | Split `web-build` (ungated) → `web-deploy` (gated) | Keeps the Approve wait to seconds, not a full build. | Plan |
| APK versioning | `versionName`+`versionCode` derived from the tag | Stable signing alone can't do update-in-place; `versionCode` must increase. | Plan |
| Secret scoping | Cloudflare in the gated Environment; Supabase/Google repo-level | Least-privilege for the CF token; APK publishes ungated with public build creds. | Plan |
| APK signing | Committed throwaway debug keystore (`android`/`androiddebugkey`) | Identical signature every runner → update-in-place. | Change |
| Release contents | APK only (web deploy-only) | The APK is the artifact worth a durable link; web has a live URL. | Plan |
| FAD | Optional Phase 3, separate follow-up PR | Non-required tester-push channel; human-gated Firebase setup. | Plan |
| Delivery | All pipeline code in one PR through the S-14 gate | User asked to merge everything in a single PR. | Plan |

## Scope

**In scope:** stable debug signing + committed keystore; tag-derived `versionName`/`versionCode`;
`web-deploy.yml` (gated Cloudflare deploy); `android-release.yml` (APK + GitHub Release); GH Environment
+ secrets; end-to-end rehearsal; optional FAD.

**Out of scope:** touching `build.yml`; `workflow_dispatch` dry-run; attaching the web bundle to the
Release; release keystore / Play Store / store distribution; new Worker or `wrangler.toml` change;
adding release workflows to the PR gate; PAT usage; tag-protection rulesets.

## Architecture / Approach

One tag push fires two independent workflows. `android-release.yml` runs unattended: derive version
from the tag → `assembleDebug` (stable signing + repo-level Supabase/Google creds) → create the Release
with auto-notes + APK. `web-deploy.yml` splits: `web-build` (ungated, builds + uploads the bundle
artifact) → `web-deploy` (`environment: production` gate + Cloudflare Environment secrets → download
artifact → assert `_headers` → `wrangler deploy`). All code lands in one PR; console setup is done in
the GitHub UI in parallel; the E2E rehearsal runs after merge via a `-rc` tag on `main`.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Pipeline code (one PR) | Signing + tag-versioning + both workflows; console setup in parallel | Committed keystore tripping the `gitleaks` gate (allowlist if so) |
| 2. End-to-end verification | One real `-rc` release: gated deploy + auto Release + update-in-place | Workflows only run once merged to `main`; gate/secret misconfig surfaces here |
| 3. (Optional) Firebase App Dist. | Tester install-link + update notifications | Human-gated Firebase setup; may never run |

**Prerequisites:** S-14 done (repo public, `main` PR-gated, `origin` = GitHub); a scoped Cloudflare API
token; owner available to click Approve.
**Estimated effort:** ~1 session for Phase 1 (one PR) + a short Phase 2 rehearsal; Phase 3 optional/later.

## Open Risks & Assumptions

- `versionCode = MAJOR*10000 + MINOR*100 + PATCH` assumes `MINOR`/`PATCH < 100`; same-core pre-releases
  (`-rc1`/`-rc2`) collide on `versionCode` — fine for RC testing.
- Moving to the committed keystore changes the debug signature once: a pre-pipeline side-loaded debug
  build must be uninstalled once before the first pipeline APK installs over it.
- `gitleaks` may flag the committed keystore/password; the fix (scoped `.gitleaks.toml` allowlist) rides
  the same PR.
- The tag interacts with protected `main` cleanly only because tags are a separate ref class; assumes
  the `main-pr-gate` posture from S-14 is in place.

## Success Criteria (Summary)

- One `v*` tag → APK + GitHub Release auto-publish (update-in-place across tags) and a one-click-gated
  web deploy to the live Worker with COOP/COEP intact.
- The `main-pr-gate` required checks remain unchanged and unaffected by the release workflows.
- The Cloudflare token stays confined to the gated `production` Environment (least-privilege).
