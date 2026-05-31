---
project: Smart Chessboard
researched_at: 2026-05-30
recommended_platform: Cloudflare (Workers Static Assets)
runner_up: Render (Static Sites)
context_type: mvp
tech_stack:
  language: Kotlin (Kotlin/Wasm)
  framework: Compose Multiplatform (WasmJS target)
  runtime: Browser — client-side WebAssembly, static assets, no server-side compute
---

## Scope of this decision

Smart Chessboard is a monorepo with three sub-projects. **Two of the three deployment targets are already settled and are NOT what this document decides:**

| Sub-project | Deployment target | Status |
|---|---|---|
| Backend (`supabase/`) | Supabase Cloud (managed Postgres + Auth + one Edge Function), EU Frankfurt | Already decided in `tech-stack.md` + `prd.md` — a managed backend, not a platform under evaluation here |
| Mobile (Android + iOS) | App stores / TestFlight / Play Internal Testing | Out of scope — mobile distribution is a separate, post-MVP decision |
| Firmware (`firmware/`) | Manual flash (`pio run -t upload`) | Parked — not deployed to any platform |
| **Web (WasmJS)** | **← the only open platform decision** | **This document** |

This research answers exactly one question: **where to host the static WasmJS web bundle** (`SmartChessboard/webApp/build/dist/wasmJs/productionExecutable/`). The web target is a **purely client-side SPA** — WebAssembly runs in the browser, Room persists via OPFS in a Web Worker, and Supabase is called directly from the client. There is **no server-side compute requirement**, so the platform need is: cheapest viable static hosting + CDN, with custom-response-header support (COOP/COEP + `application/wasm`), driveable by an agent from a CLI.

## Recommendation

**Deploy the web bundle on Cloudflare — via Workers Static Assets** (`wrangler deploy` with an assets-only Worker), not Cloudflare Pages.

Cloudflare wins decisively on the interview's heaviest weight (minimize cost): static-asset requests and bandwidth are genuinely free and unmetered, with no per-request charge and no site-pause-on-overage. It also auto-detects `application/wasm` MIME, auto-compresses the `.wasm` response, supports COOP/COEP via a `_headers` file, ships best-in-class agent-readable docs (`llms.txt` + Markdown content negotiation) and multiple GA MCP servers, and is fully driveable from `wrangler`. **Workers Static Assets is chosen over Pages** because (a) Cloudflare is steering new projects to Workers and de-emphasizing Pages investment, and (b) Workers has a first-class `wrangler rollback`, which Pages lacks. The interview confirmed no prior platform familiarity, so the `cloudflare-pages` hint previously in `tech-stack.md` was not load-bearing — Cloudflare wins on merit, and within Cloudflare the forward-looking product is Workers Static Assets.

> **Decision status (2026-05-30): locked.** Workers Static Assets is committed and propagated to `tech-stack.md` (frontmatter `deployment_target` + web-hosting row) and `lessons.md` (COOP/COEP rule). The Pages comparison that follows is the **rationale and audit trail** for that choice — not an open question to re-litigate. If a future change reopens it, update all three files together.

## Platform Comparison

Hard filter applied first: the web target needs **no persistent server-side connections** (BLE is mobile-only, Supabase Realtime is unused, game sync is plain CRUD from the client). That keeps all static hosts in play but makes **container-PaaS platforms (Fly.io, Railway) overkill** — they have no first-class static product, require running an nginx/Caddy container, cost $2–5/mo minimum, serve from a single region without a CDN, and their headline advantage (co-located Postgres) is dead here because the backend is external Supabase. They are dropped before weighting.

| Platform | CLI-first | Managed / static | Agent-readable docs | Stable deploy API | MCP / integration |
|---|---|---|---|---|---|
| **Cloudflare** (Workers Static Assets) | ✅ Pass — `wrangler`, structured JSON, non-interactive | ✅ Pass — zero-infra static + edge CDN | ✅ Pass — `llms.txt` + `llms-full.txt` + `Accept: text/markdown` | ✅ Pass — `wrangler deploy`; `wrangler rollback` (Workers) | ✅ Pass — multiple GA MCP servers (docs/API/bindings/builds); observability beta |
| **Render** (Static Sites) | 🟡 Partial — CLI GA Dec 2024, thinner; no rollback command | ✅ Pass — first-class static site + CDN, no spin-down | ✅ Pass — `llms.txt`/`llms-full.txt` + `render-oss/skills` repo | 🟡 Partial — `render deploys create --wait` / deploy hook; no rollback cmd | 🟡 Partial — MCP GA but cannot trigger deploys (read-heavy) |
| **Vercel** | ✅ Pass — `vercel`, first-class `rollback`/`promote` | ✅ Pass — fully managed static | ✅ Pass — `llms-full.txt` | ✅ Pass — `vercel --prod --yes` deterministic | 🟡 Partial — MCP public beta, frozen 13-tool set |
| Netlify | 🟡 Partial — `netlify deploy --prod`; rollback via API only | ✅ Pass — fully managed static | ✅ Pass — `llms.txt` + `.md` suffix | 🟡 Partial — `--prod` required; rollback via `restoreSiteDeploy` API | ✅ Pass — MCP GA |
| Fly.io | ✅ Pass — `flyctl` | ❌ Fail — container/VM, no static product | 🟡 Partial — no `llms.txt`; per-page MD on GitHub | ✅ Pass — `fly deploy`, image-pinned rollback | 🟡 Partial — MCP experimental |
| Railway | ✅ Pass — `railway` | ❌ Fail — container, no CDN | 🟡 Partial — `.md` files, no `llms.txt` | ✅ Pass — `railway up --detach` | 🟡 Partial — MCP beta |

### Shortlisted Platforms

#### 1. Cloudflare — Workers Static Assets (Recommended)

The only candidate that is free with **unmetered** static bandwidth (no monthly GB cap, no overage, no site-pause), and the only one that **auto-compresses `application/wasm`** and auto-sets the `.wasm` MIME type — both directly relevant to shipping a multi-MB Kotlin/Wasm bundle cheaply. COOP/COEP go in a `_headers` file. Agent-operability is the best of the field: `wrangler` is non-interactive and JSON-friendly, docs are published as `llms.txt`/`llms-full.txt` with Markdown content negotiation, and several MCP servers are GA. Choosing **Workers Static Assets** over Pages also buys a real `wrangler rollback` (Pages has none) and aligns with Cloudflare's stated direction for new projects. Per-file limit is 25 MiB (comfortable for a minified Compose/Wasm bundle).

#### 2. Render — Static Sites

The strongest genuinely-free runner-up: a first-class Static Site product with a global CDN that does **not** spin down, **Brotli compression on `.wasm`**, and COOP/COEP + `application/wasm` headers declarable in `render.yaml`. It also publishes `llms.txt`/`llms-full.txt` and an official `render-oss/skills` repo. The gaps vs. Cloudflare: the CLI is GA but thinner (no rollback command — redeploy a prior commit), the free tier is 100 GB bandwidth (a large `.wasm` bundle burns it faster than Cloudflare's unmetered tier), slow Kotlin/Wasm Gradle builds eat the 500 free build-minutes, and the MCP server cannot trigger deploys. A safe, no-lock-in fallback if Cloudflare's COOP/COEP or Pages-vs-Workers friction ever proves blocking.

#### 3. Vercel

Best developer experience and the cleanest CLI in the field — `vercel --prod --yes` is deterministic and `vercel rollback`/`promote` are first-class. It serves `application/wasm` and supports COOP/COEP via `vercel.json` headers. The gap vs. the top two is cost-and-terms, which the interview weighted heavily: the free **Hobby** plan **prohibits commercial use** (a personal MVP is allowed, but the bar is a future trap), caps bandwidth at 100 GB hard, and **Pro is $20/seat/month**. Preview URLs are also wrapped in Vercel Authentication by default. Fine technically, weaker on the cost axis.

> Netlify was deliberately left off the podium: its new (post-2025-09-04) credit-based free plan **pauses the site** (shows "Site not available") at roughly 15 GB of bandwidth and does **not** compress `.wasm` — the worst cost profile of the static trio for a large-bundle WasmJS app, despite a GA MCP server.

## Anti-Bias Cross-Check: Cloudflare + Kotlin/Wasm static

### Devil's Advocate — Weaknesses

1. **Pages vs Workers Static Assets is a real fork, not cosmetics.** Cloudflare is moving new-project investment to Workers and de-emphasizing Pages. Picking Pages means building on the slower-moving product; picking Workers Static Assets means a newer config surface (`wrangler.toml` `[assets]`) whose docs and agent muscle-memory are still settling. Half-fetched docs can describe the wrong product. (Resolved here by committing to Workers Static Assets explicitly.)
2. **COOP/COEP via `_headers` breaks cross-origin subresources.** Setting `Cross-Origin-Embedder-Policy: require-corp` blocks every cross-origin resource that doesn't send `Cross-Origin-Resource-Policy` — public Supabase Storage objects, web fonts, analytics. Supabase `fetch`/XHR calls are fine; embedded cross-origin assets are not. A subtle, runtime-only class of failures.
3. **No build of a KMP project on Cloudflare's side.** Cloudflare's Git-connected builds can't run a Gradle/Kotlin toolchain. The realistic pipeline is "GitHub Actions builds the wasm bundle → `wrangler deploy` the artifact," so Cloudflare's free build minutes are irrelevant and the build cost lives in GH Actions.
4. **Rollback is per-version, within retained versions.** `wrangler rollback` exists (a Workers advantage over Pages) but only targets versions Cloudflare still retains; a clean revert to an arbitrary old build may still require a rebuild from a prior commit.
5. **A near-25-MiB `.wasm` is plausible for an unminified Compose build.** Production minification keeps it well under the 25 MiB per-file cap, but a debug/dev artifact deployed by mistake could approach it; and even compressed, first-load parse/instantiate of a large `.wasm` on a mobile browser is slow — a platform can't fix that.

### Pre-Mortem — How This Could Fail

Six months in, the web target is a graveyard. The team took Cloudflare "because `tech-stack.md` said so" and never revisited the Pages-vs-Workers question, so half the agent's fetched docs described the wrong product and config drifted. OPFS-backed Room silently failed on web because COOP/COEP were never wired — and because the bare `webApp/build.gradle.kts` dev server doesn't set them either, it failed locally too, but nobody had exercised web persistence, so it surfaced only when a friend reported "my games vanish on the laptop." The fix (enable COEP) then blocked Supabase Storage avatar loads for lack of CORP, cascading into more breakage. Every deploy was a full minutes-long `wasmJsBrowserDistribution`, and a bad deploy meant scrambling for a rollback that only covered retained versions. The web target — only ever a nice-to-have (FR-020) — consumed disproportionate debugging time before the team finally invoked Open Question #6 and froze web, a week too late.

### Unknown Unknowns

- **Nothing sets COOP/COEP today — not even local dev.** The current `webApp/build.gradle.kts` is bare (`wasmJs { browser(); binaries.executable() }`) with no devServer header config, and `androidx.sqlite:sqlite-web` (Room 3.0 OPFS) needs SharedArrayBuffer → COOP/COEP, or it logs "Cannot install OPFS: Missing SharedArrayBuffer and/or Atomics" and persistence degrades. The header posture must be configured in **two independent places** (the Kotlin/Wasm webpack dev server *and* the Cloudflare host) and kept identical, or you get "works on one surface, silently broken on the other." Captured as a hard rule in `lessons.md`.
- **A static deploy has no server-side secrets.** There is no `wrangler secret` on an assets-only Worker — everything compiled into the bundle is public by definition. Only the Supabase **anon key** (RLS-protected) may ever land in the wasm bundle; a service-role key would be a public leak. Static hosting offers nowhere to hide a secret.
- **`instantiateStreaming` depends on `Content-Type: application/wasm` surviving the edge.** Cloudflare auto-sets and compresses it, but if any transform interferes the browser falls back to slower non-streaming compilation — usually fine, worth knowing.
- **`*.workers.dev` preview URLs are public and indexable; fork-PR previews don't build.** For a small-circle MVP that's acceptable — data stays gated by Supabase auth + RLS regardless of who loads the SPA shell — but "private by default" (NFR) protects *data*, not the preview URL.

## Operational Story

How Cloudflare Workers Static Assets actually operates for this project, day to day. One concrete answer per axis.

- **Preview deploys**: `npx wrangler versions upload` publishes a non-production version and returns a preview URL; per-branch stable preview URLs are GA (since mid-2025). Previews are public by default — gate any sensitive one behind **Cloudflare Access** (Zero Trust, free tier). Fork PRs do not build a preview.
- **Secrets**: a pure static deploy has **no runtime secrets**. The only values that reach the browser are `SUPABASE_URL` + the Supabase **anon key**, injected at build time (GitHub Actions secrets → Gradle build args) and public by design. Deploy auth uses a **scoped** `CLOUDFLARE_API_TOKEN` (Workers Scripts edit for this one project only — no DNS, no unrelated secrets, no billing) plus `CLOUDFLARE_ACCOUNT_ID`, stored in GH Secrets, never committed to `.mcp.json` or pasted into chat. The Supabase service-role key never goes near the web bundle.
- **Rollback**: `npx wrangler deployments list` then `npx wrangler rollback [<version-id>]` (Workers supports this; Pages does not). Caveat: only versions Cloudflare still retains are targetable; a revert to an arbitrary older build may require a rebuild from that commit. No data caveat — the web client is stateless; persistence is OPFS (local) + Supabase (server-side, unaffected by a frontend rollback).
- **Approval**: human-only — creating/rotating the Cloudflare API token, first Worker/project creation, attaching the custom domain, and deleting the Worker. Agent may, unattended — build the bundle, `wrangler versions upload` (preview), `wrangler deploy` (after the gated first deploy), `wrangler tail`, `wrangler deployments list`, and `wrangler rollback` within retained versions.
- **Logs**: `npx wrangler tail` streams live, but an assets-only Worker runs no code, so there are no runtime logs to read for pure static serving. Build/deploy logs live in GitHub Actions (`gh run view <run-id> --log`). The Cloudflare observability MCP (beta, 2026-05-30) exposes analytics for read-only queries.

## Risk Register

Every risk names the lens that surfaced it, so a future reader can see why it is on the list.

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| COOP/COEP drift between local dev server and Cloudflare → OPFS-backed Room persistence breaks silently on one surface | Unknown unknowns | M | H | Configure identical COOP/COEP on **both** surfaces (`webApp/webpack.config.d/` + Cloudflare `_headers`); hard rule in `lessons.md`; verify with a cross-browser OPFS persistence test on a preview deploy, not by assumption |
| `COEP: require-corp` blocks cross-origin subresources (Supabase Storage objects, fonts, analytics) lacking CORP | Devil's advocate | M | M | Audit every cross-origin load; serve `Cross-Origin-Resource-Policy: cross-origin`, proxy through same origin, or self-host the asset |
| Pages-vs-Workers ambiguity → agent fetches wrong-product docs, config drifts | Devil's advocate | L | M | **Resolved 2026-05-30**: committed to Workers Static Assets in `tech-stack.md`, `lessons.md`, and this file; agent reads only Workers docs. Residual: Workers Static Assets is the newer surface — pin `wrangler.toml` `[assets]` and confirm fetched docs are Workers, not Pages |
| Supabase service-role key (or any secret) leaks into the public static bundle | Unknown unknowns | L | H | Only the Supabase anon key may be built in; CI check that no service-role key pattern appears in `dist/`; hard rule in `lessons.md` |
| Large Kotlin/Wasm `.wasm` near 25 MiB/file cap and slow first parse on mobile | Research finding | L | M | Ship the minified production build; track bundle size in CI; if it nears the cap, split or serve the `.wasm` from R2; lazy-init heavy code paths |
| No KMP build on Cloudflare → must build in GitHub Actions | Research finding | L | L | Build the bundle in GH Actions, deploy the artifact via `wrangler deploy`; do not use Cloudflare Git-connected builds for this stack |
| `wrangler rollback` only covers retained versions | Devil's advocate | L | M | For older reverts, redeploy from the target commit; keep CI artifacts/tags so any commit is rebuildable |
| `*.workers.dev` preview URLs are public/indexable | Pre-mortem | L | L | Data stays gated by Supabase auth + RLS; gate sensitive previews via Cloudflare Access (free Zero Trust); add `X-Robots-Tag: noindex` via `_headers` if needed |
| Web target (a nice-to-have, FR-020) over-consumes debugging time | Pre-mortem | M | M | Timebox web-deploy debugging; invoke PRD Open Question #6 escape hatch — freeze web if it ever blocks a must-have |

## Getting Started

Concrete first steps for this exact stack. (The actual first deploy is the M1L5 Plan Mode step — this is the recipe that step follows, not a license to deploy now.)

1. **Build the bundle** (Android SDK path is needed because the multi-module Gradle config configures `:androidApp`):
   ```bash
   cd SmartChessboard
   ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :webApp:wasmJsBrowserDistribution --no-daemon --console=plain
   # output → webApp/build/dist/wasmJs/productionExecutable/
   ```
2. **Make local dev match production** — add COOP/COEP to the Kotlin/Wasm dev server so `:webApp:wasmJsBrowserDevelopmentRun` behaves like the deployed site. The version-robust mechanism is a webpack-config fragment (verify the DSL against the Kotlin version in `gradle/libs.versions.toml`):
   ```js
   // SmartChessboard/webApp/webpack.config.d/devServerHeaders.js
   config.devServer = config.devServer || {};
   config.devServer.headers = {
     "Cross-Origin-Opener-Policy": "same-origin",
     "Cross-Origin-Embedder-Policy": "require-corp",
   };
   ```
3. **Make production match local dev** — ensure a `_headers` file lands in the published directory (source it as a static resource the build copies into `productionExecutable/`, or write it in CI before deploy):
   ```
   /*
     Cross-Origin-Opener-Policy: same-origin
     Cross-Origin-Embedder-Policy: require-corp
   ```
   (`.wasm` MIME is auto-set by Cloudflare — no rule needed.)
4. **Configure an assets-only Worker** at the repo root or in `SmartChessboard/`:
   ```toml
   # wrangler.toml
   name = "smart-chessboard-web"
   compatibility_date = "2026-05-30"

   [assets]
   directory = "./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable"
   ```
5. **Authenticate and deploy** — locally `npx wrangler login` (interactive, human-gated first time); in CI use the scoped `CLOUDFLARE_API_TOKEN` + `CLOUDFLARE_ACCOUNT_ID`. Then:
   ```bash
   npx wrangler versions upload   # preview URL
   npx wrangler deploy            # production
   npx wrangler deployments list  # inspect
   npx wrangler rollback          # revert within retained versions
   ```
6. **Verify, don't assume** — open a preview on a real browser, sign in, play and save a game, reload, and confirm the game persists (OPFS working = COOP/COEP correct). Repeat on the production URL after the first `wrangler deploy`.

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration (not applicable to a static bundle).
- CI/CD pipeline authoring — the GitHub Actions `web-deploy.yml` workflow is tracked separately in [`docs/vacation-workflow-todo.md`](../../docs/vacation-workflow-todo.md).
- Production-scale architecture (multi-region HA, DR, SLA tiers).
- Mobile (Android/iOS) distribution and the Supabase backend platform — both already decided elsewhere (see "Scope of this decision").
