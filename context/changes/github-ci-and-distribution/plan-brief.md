# Public GitHub + CI (web→Cloudflare, Android→Firebase App Distribution) — Plan Brief

> Full plan: `context/changes/github-ci-and-distribution/plan.md`

## What & Why

Move the repo to GitHub and add two **manually-triggered** GitHub Actions pipelines: web → Cloudflare Worker, and Android debug APK → Firebase App Distribution. This un-parks the roadmap's CI + mobile-distribution items and turns the manual, laptop-bound build/deploy into something driveable from GitHub.

## Starting Point

Repo is on **Bitbucket** with no CI (`.github/` absent). Web deploy already works manually — `wrangler.toml` + a source `_headers` file exist and the Worker is live. Build secrets (`SUPABASE_URL`, `SUPABASE_ANON_KEY`, `GOOGLE_SERVER_CLIENT_ID`) flow through BuildKonfig from `local.properties` or `-P`/env. Android `assembleDebug` currently uses a per-machine debug keystore.

## Desired End State

The repo is on GitHub (origin switched; Bitbucket kept as backup). Clicking "Run" on `web-deploy.yml` publishes the site; clicking "Run" on `android-fad.yml` puts a signed APK in a tester's hands. After a final gated step, selected paths (`10xDevsLekcje/` + a user list) are erased from all history and the repo is flipped to public.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Structure | Change folder + plan (not a roadmap slice) | Infra work has no PRD FR/US trace | Plan |
| Pipeline scope | Both web→Cloudflare and Android→FAD | Full "code → live + on device" loop | Plan |
| Triggers | Manual `workflow_dispatch` only | User wants explicit control, no surprise deploys | Plan |
| Public vs scrub timing | Private repo now → CI green → scrub + flip public later | Unblocks CI without exposing course material; matches deferred scrub | Plan |
| GitHub remote model | Switch `origin` to GitHub, keep `bitbucket` backup | Reversible, no lost history | Plan |
| Android signing | Commit a throwaway debug keystore | Stable cert → FAD update-installs work | Plan |
| History scrub tool | `git filter-repo --invert-paths` | Removes paths from every commit, no trace | Plan |

## Scope

**In scope:** GitHub repo + origin switch; `web-deploy.yml` (Cloudflare); `android-fad.yml` (FAD) with committed debug keystore; Firebase console setup; GH Secrets; gated history scrub + public flip.

**Out of scope:** iOS/TestFlight; release signing / Play Store; auto-on-push/PR triggers; CI Supabase migrations; deleting the Bitbucket repo; finalizing the scrub path list (user supplies later).

## Architecture / Approach

Two independent workflow files, each `workflow_dispatch`, each running `setup-java 21` + `setup-android` (AGP configuration needs `ANDROID_HOME` even for `:webApp`). Supabase/Google creds are injected as Gradle `-P` props from GitHub Secrets. Web uses `cloudflare/wrangler-action` against the existing `wrangler.toml`; Android uses `wzieba/Firebase-Distribution-Github-Action`. History rewrite is a one-time `git filter-repo` on a fresh clone, force-pushed to GitHub only.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. GitHub (private) + origin switch | Code on GitHub, origin→GitHub, Bitbucket=backup | Human-gated repo create / `gh auth` |
| 2. Android prereqs (Firebase + debug keystore) | FAD project + stable-signed debug build | Manual console work; service-account handling |
| 3. Web → Cloudflare workflow | Manual deploy publishes the site | `-P` secret injection; AGP `ANDROID_HOME` at config |
| 4. Android → FAD workflow | Manual run ships APK to testers | setup-android, keystore signing, FAD auth |
| 5. History scrub + public flip (gated) | Removed paths gone from history; repo public | Destructive, rewrites all SHAs, force-push |

**Prerequisites:** GitHub account + `gh auth`; Firebase account; scoped Cloudflare API token; user's final removal path list (Phase 5 only).
**Estimated effort:** ~2–3 sessions; Phases 3–4 are the bulk, Phase 5 waits on the user's path list.

## Open Risks & Assumptions

- History rewrite (Phase 5) invalidates every commit SHA recorded across the context docs — accepted as audit-only.
- Bitbucket retains the pre-scrub history (with `10xDevsLekcje/`) unless separately deleted.
- Committing a debug keystore to a (soon) public repo is safe only because it is a throwaway debug cert (password `android`), never a release key.
- Firebase App Distribution service account + Cloudflare token are scoped and live only in GitHub Secrets, never in the repo.

## Success Criteria (Summary)

- Manually dispatching `web-deploy.yml` publishes the current `main` to the live Worker.
- Manually dispatching `android-fad.yml` delivers an installable APK to a tester; a second run updates the install.
- After the gated scrub, the removed paths appear at no commit on the now-public GitHub repo.
