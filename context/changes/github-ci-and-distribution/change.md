---
change_id: github-ci-and-distribution
title: Public GitHub + CI — web→Cloudflare and Android→Firebase App Distribution
status: planned
created: 2026-07-02
updated: 2026-07-02
archived_at: null
---

## Notes

Infrastructure change (CI/CD + distribution) — **not** a vertical product slice, so it is intentionally not an `S-NN` in the roadmap (no PRD FR/US trace). It **un-parks** two roadmap `## Parked` items — "CI pipeline (build/test/deploy workflows)" and the CI half of "Store/TestFlight mobile distribution" (Firebase App Distribution only, not app stores). Builds on an earlier local CI/distribution planning sketch (not published) and the web-host decision in `context/foundation/infrastructure.md` (Cloudflare Workers Static Assets).

### Decisions locked (2026-07-02)

- **Scope: two pipelines.**
  - Web → **Cloudflare Workers Static Assets** (`wrangler deploy`; `_headers` COOP/COEP must land in `productionExecutable/`). Already designed in `infrastructure.md`.
  - Android → **Firebase App Distribution**: GH Actions builds `:androidApp:assembleDebug` and uploads the APK to FAD.
- **GitHub: switch `origin` from Bitbucket to GitHub** (GitHub becomes primary; Bitbucket kept as history/backup or as a secondary push — decide in plan). Repo is **public**.
- **Triggers: manual (`workflow_dispatch`) as the baseline** per user ("ręczne trigerry"). Whether to also add auto-on-push (`main`) / PR-preview is an open item for the plan.
- **History scrub before the first public push.** Rewrite git history with `git filter-repo` (prerequisite: `brew install git-filter-repo`, not currently installed) to remove selected paths with no trace. **`10xDevsLekcje/` is confirmed for removal**; the **full path list is user-provided later** at implement time (treat as a manual input gate — do not hardcode). Consequences to honor: history rewrite **changes all commit SHAs** from the first touched commit onward, so the SHAs recorded across `roadmap.md` / `change.md` / plan `## Progress` sections will no longer resolve; requires `push --force`; the old history remains on Bitbucket / any existing clones until separately removed.

### Open items for the plan

- Exact scrub path list (user supplies later) + whether Bitbucket's copy must also be purged/deleted.
- Trigger model confirm: manual-only vs manual + auto-on-push/PR-preview.
- Android debug-keystore persistence in CI so successive FAD uploads update the **same** app install (commit a debug keystore or generate deterministically).
- Multi-module AGP demanding `ANDROID_HOME` during Gradle *configuration* (not just build) — runner uses `android-actions/setup-android`; confirm `:webApp`-only builds don't require it.
- Secrets inventory (GH Secrets, never in repo): `CLOUDFLARE_API_TOKEN` (scoped, Workers Scripts edit, this project only) + `CLOUDFLARE_ACCOUNT_ID`; `FIREBASE_SERVICE_ACCOUNT_JSON` + `FIREBASE_APP_ID`; `SUPABASE_URL` + Supabase anon key (build-time inject, public by design — service-role key never in the bundle, per `lessons.md`).

### Human-gated steps (agent cannot do unattended)

Create the GitHub repo + first push; create Firebase project/app + service account + tester group; add all GH Secrets; first Cloudflare deploy auth (`wrangler login`); run the destructive `git filter-repo` + `push --force`.
