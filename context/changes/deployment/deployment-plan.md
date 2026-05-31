# First Production Deploy — WasmJS Web Target → Cloudflare, with a live Supabase connection

> Status: **planned, not executed.** This document is the approved recipe; execution is a separate, human-gated step.

## Context

`context/foundation/infrastructure.md` locked **Cloudflare Workers Static Assets** as the host for the WasmJS web bundle (`SmartChessboard/webApp`). This plan executes the **M1L5 deploy step** *and* — at the user's request — stands up the **production Supabase backend** and wires a **real, auth-free Supabase connectivity probe** into the web app, so the first deploy ships something that genuinely talks to Postgres.

**Reality before this plan.** Exploration found a **bare Compose scaffold** plus an empty Supabase project-as-code:

- `webApp/build.gradle.kts`: only `wasmJs { browser(); binaries.executable() }`, depends on `:shared` + `compose.ui`. No webpack/devServer config.
- **No** `webpack.config.d/`, **no** `_headers`, **no** `wrangler.toml`, **no** `.github/workflows/`.
- **No** Supabase client in the KMP code; **no** build-time secret injection (no BuildKonfig, no `local.properties` reader).
- `supabase/`: `config.toml` (`project_id = "claude"`, region EU Frankfurt) but **no migrations, no functions, no seed** yet.
- KMP modules: `:androidApp`, `:shared`, `:webApp`. Shared targets: `androidLibrary`, `iosArm64/iosSimulatorArm64`, `wasmJs { browser() }`. Shared networking belongs in `shared/src/commonMain`. Kotlin 2.3.21, Compose MP 1.11.0.

**What this plan changes.** It closes part of the gap: a production Supabase project with a minimal, domain-real schema; `supabase-kt` wired into `commonMain`; the anon key injected into the bundle via BuildKonfig; and the web app performing a real PostgREST read that proves connectivity. Room/OPFS persistence and Google OAuth remain **Module 2**.

**Decisions confirmed with the user:**
1. **Scope = manual first deploy only.** Local `wrangler`, human-gated. CI (`web-deploy.yml`) stays out of scope — tracked in `docs/vacation-workflow-todo.md`.
2. **COOP/COEP = set up now**, on both surfaces. Safe today: the shell + Supabase `fetch` calls don't trip `COEP: require-corp` (COEP blocks embedded cross-origin subresources, **not** `fetch`/XHR).
3. **Address = `*.workers.dev`.** Custom domain is a later human-gated step.
4. **`wrangler` installed globally** (`npm i -g wrangler`) and authenticated via **`wrangler login`** (OAuth), not `npx`.
5. **Supabase connection = auth-free connectivity probe** against a **minimal, domain-aligned** schema: create only `public.position_evals` (+ RLS) now. The probe runs as `anon`, gets HTTP 200 with 0 rows (RLS enforced), and the app renders "Connected ✓". *Rationale for choosing the real `position_evals` over a throwaway table: Supabase migrations are append-only (`supabase/AGENTS.md`), so a temporary table would need a second migration to drop it — instead we ship the smallest real contract slice that never needs teardown.* Full `games` schema + Google OAuth + reading own games = **Module 2**.

## Goal

A reproducible end-to-end production deploy of the WasmJS bundle to `smart-chessboard-web.<subdomain>.workers.dev` that, on load, **successfully reaches the production Supabase project** (West EU / Ireland) (PostgREST `position_evals` read → 200, 0 rows as anon) and renders a connection status — with COOP/COEP served on the host and matched on the local dev server, all verified in a real browser.

## Verified technical facts (checked 2026-06-01)

**Cloudflare (docs):**
- **Assets-only Worker**: `wrangler.toml` with `name` + `compatibility_date` + `[assets] directory`, **no** `main`, **no** `binding`.
- **`_headers`** (plain file, no extension, in the assets dir) overrides default response headers; with no Worker script it applies to every response.
- **`application/wasm`** MIME is auto-detected and auto-compressed.
- **Preview URLs**: `wrangler versions upload` returns a versioned preview URL; set `preview_urls = true` (since wrangler v4.44.0+ it defaults to the `workers.dev` setting) and register a `workers.dev` subdomain.
- **SPA fallback** (`not_found_handling`) not needed — Compose/Wasm serves a single `/`.

**Supabase + KMP (docs + web):**
- **`supabase-kt`** has **full WASM-JS support from v3.0.0+** (group `io.github.jan-tennert.supabase`, **BOM** + modules `postgrest-kt`, `auth-kt`, `functions-kt`, …). Project badge targets Kotlin 2.3.21 — matches our toolchain.
- **Ktor** client runs on wasmJs; supabase-kt needs a **Ktor engine per target** (wasmJs/js → `ktor-client-js`; android → `ktor-client-okhttp`; ios → `ktor-client-darwin`). Use a Ktor **3.x** aligned with the chosen supabase-kt release.
- **BuildKonfig** (`com.codingfeline.buildkonfig`, latest **0.21.2**) generates a `BuildKonfig` object across KMP targets **including wasmJs** — our secret-injection mechanism for `SUPABASE_URL` + anon key.
- **CORS / keys**: the Supabase REST/Auth API is CORS-open to the anon key, so calls from `*.workers.dev` need no extra config. The **anon key is public by design** (RLS-protected) and may live in the bundle; the **service-role key never** does (`lessons.md` #4).

---

## Prerequisites — manual setup you do yourself (before any implementation)

Human-only, done **once**. Nothing destructive; **no paid plan required** (Cloudflare Free + Supabase Free). Work top to bottom; **§F** is the readiness gate.

> **TL;DR for THIS deploy:** Node ≥ 20; `wrangler` installed globally + `wrangler login`; a free Cloudflare account with a `*.workers.dev` subdomain; a free **Supabase project** (EU Frankfurt) with its URL + anon key captured; the **Supabase CLI** installed + linked. GitHub: nothing.

### A. Local tooling (your machine)

| Tool | Why it's needed | Check / install (macOS) |
|---|---|---|
| **Node.js ≥ 20 (LTS)** | Runtime for the global `wrangler`. The Gradle/Kotlin build downloads its own Node, so system Node is for wrangler only. | `node -v` → if missing/<20: `brew install node` or `nvm install --lts` |
| **wrangler (global)** | Cloudflare CLI — deploy + rollback. | `npm i -g wrangler` then `wrangler --version` |
| **Supabase CLI** | Link the hosted project + push migrations. | `brew install supabase/tap/supabase` then `supabase --version` |
| **JDK 21** | Gradle build of the wasm bundle. | `java -version` |
| **Android SDK** | The multi-module Gradle config touches `:androidApp`, so even the wasm task needs `ANDROID_HOME`. | `ls "$HOME/Library/Android/sdk"` |
| **Chromium browser** | Verifying `crossOriginIsolated` + headers + the probe in DevTools. | — |

### B. Cloudflare account & dashboard (browser)

1. **Create / sign in** at `dash.cloudflare.com`. **Free** plan suffices (unmetered static bandwidth; **no credit card**).
2. **Register a `workers.dev` subdomain — one-time, REQUIRED.** Dashboard → **Workers & Pages** → pick a subdomain (e.g. `<subdomain>.workers.dev`). Your site becomes `smart-chessboard-web.<subdomain>.workers.dev`. *Skip it and `wrangler deploy` fails with "register a workers.dev subdomain."*
3. **Note your Account ID** (Workers & Pages → right sidebar). Needed if your login spans multiple accounts.
4. You do **NOT** create the Worker by hand — `wrangler deploy` creates `smart-chessboard-web` on first run.

### C. Install & authenticate the Cloudflare CLI (global `wrangler`)

```bash
npm i -g wrangler          # global install (per your choice)
wrangler --version         # confirm it's on PATH
wrangler login             # opens browser OAuth → click "Allow"
wrangler whoami            # confirms account + lists account IDs
```
- Headless/SSH: `wrangler login` prints a URL to paste into a browser.
- Credentials are stored locally by wrangler; nothing is committed.
- In this Claude Code session you can run the login yourself with `! wrangler login`.
- *(Future CI only — not now)* CI will instead use a scoped `CLOUDFLARE_API_TOKEN` (template **Edit Cloudflare Workers**, this account only) + `CLOUDFLARE_ACCOUNT_ID` in GH Secrets — never committed (`lessons.md` #4).

### D. Supabase — account, hosted project & CLI (browser + CLI)

1. **Create / sign in** at `supabase.com` (Free tier).
2. **Create a project** (browser): organization → **New project**. Name e.g. `smart-chessboard`; **Region = Central EU (Frankfurt)** per `tech-stack.md`; set a strong DB password (store it in your password manager). Wait for provisioning (~2 min).
3. **Capture credentials** — Project → **Settings → API**:
   - **Project URL** → this is `SUPABASE_URL` (e.g. `https://<ref>.supabase.co`).
   - **`anon` / public key** → this is `SUPABASE_ANON_KEY` (public, RLS-protected — safe for the bundle).
   - **Do NOT** copy the **service_role** key anywhere near the web app.
   - Note the **project ref** (the `<ref>` subdomain) for linking.
4. **Authenticate the CLI**: `supabase login` (opens browser → generates an access token).
5. **Link the repo to the hosted project** (from repo root, where `supabase/config.toml` lives):
   ```bash
   supabase link --project-ref <ref>
   ```
   - This connects the local `supabase/` project-as-code to your hosted project so `supabase db push` targets it.
6. **(Do NOT run migrations yet)** — the migration is authored in **Phase A2** and pushed there. This section only stands up and links the project.

> Anonymous sign-ins, Google OAuth, Storage, Edge Functions: **not configured now** (out of scope for the auth-free probe). Listed in Phase C6 as Module-2 follow-ups.

### E. GitHub — not needed for this deploy

Manual deploy was chosen → no GitHub Actions, repo secrets, or push required. CI (`web-deploy.yml`) is tracked in `docs/vacation-workflow-todo.md`.

### F. Preflight — confirm you're ready

```bash
node -v                                                   # ≥ v20
wrangler whoami                                           # Cloudflare account resolves
supabase --version && supabase projects list             # CLI works; your project is listed/linked
java -version                                             # JDK 21
ls "$HOME/Library/Android/sdk" >/dev/null && echo "Android SDK OK"
```
Browser checks: Cloudflare **Workers & Pages** shows your `*.workers.dev` subdomain; Supabase **Settings → API** shows your URL + anon key. When all pass, start Phase 0.

---

## Implementation & deployment phases

Tracked with checkboxes. Order: **Backend (A) → Web wiring (B) → Build/headers/deploy (C)**. Backend first so the probe has a real endpoint to hit.

### Phase 0 — Preflight gate
- [ ] All **§F** commands succeed; both browser checks pass.
- [ ] `wrangler` global + `wrangler login` confirmed (**§C**).
- [ ] Supabase project created, credentials captured, `supabase link` done (**§D**).

---

### Track A — Supabase backend (production)

#### Phase A1 — Project & credentials (done in Prerequisites §D)
- [ ] Hosted project live in EU Frankfurt; `SUPABASE_URL` + anon key captured; CLI linked. *(Gate-check only — work happened in §D.)*

#### Phase A2 — Minimal domain-aligned schema migration
- [ ] Create the migration file: `supabase migration new position_evals` → produces `supabase/migrations/<timestamp>_position_evals.sql`.
- [ ] Fill it with the smallest real contract slice (matches `docs/reference/contract-surfaces.md`):
  ```sql
  create table public.position_evals (
    fen        text primary key,
    eval_cp    integer,
    best_move  text,
    depth      integer,
    source     text not null default 'unknown' check (source in ('lichess','unknown')),
    fetched_at timestamptz not null default now()
  );

  alter table public.position_evals enable row level security;

  -- Global eval cache: readable by any authenticated user; writes only via service_role (edge fn), which bypasses RLS.
  create policy position_evals_select_authenticated
    on public.position_evals
    for select
    to authenticated
    using (true);
  ```
- [ ] Push to the hosted DB: `supabase db push` (applies the migration to the linked project).
- [ ] **Edge case (append-only):** never edit a pushed migration; any change is a **new** migration (`supabase/AGENTS.md`). This table is domain-real, so no teardown is planned.
- [ ] **Edge case (no `games` yet):** the probe deliberately reads `position_evals`, not `games` — `games` needs auth to be meaningful and lands in Module 2.

#### Phase A3 — Verify the backend independently of the app
- [ ] Confirm the table + RLS in **Supabase Studio → Table editor / Authentication → Policies**, or via a raw PostgREST check that mimics the anon client:
  ```bash
  curl -s "$SUPABASE_URL/rest/v1/position_evals?select=*" \
    -H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $SUPABASE_ANON_KEY" -i | head -n 20
  ```
  Expect **HTTP 200** with body `[]` (RLS denies anon rows but the request succeeds → exactly the probe's success condition).

---

### Track B — Web app ↔ Supabase wiring (`SmartChessboard/`)

#### Phase B1 — Dependencies
- [ ] In `gradle/libs.versions.toml` add versions + libraries for: **supabase-kt BOM** + `postgrest-kt`, **Ktor 3.x** engines (`ktor-client-js` for wasmJs; `ktor-client-okhttp` android; `ktor-client-darwin` ios), **kotlinx-serialization-json**, **kotlinx-coroutines-core**, and the **BuildKonfig** + **kotlin-serialization** Gradle plugins.
- [ ] In `shared/build.gradle.kts`:
  - apply `org.jetbrains.kotlin.plugin.serialization` and `com.codingfeline.buildkonfig`.
  - `commonMain`: `implementation(project.dependencies.platform(libs.supabase.bom))`, `implementation(libs.supabase.postgrest)`, `implementation(libs.kotlinx.serialization.json)`, `implementation(libs.kotlinx.coroutines.core)`.
  - per-target Ktor engine: `wasmJsMain` → `ktor-client-js`; `androidMain` → `ktor-client-okhttp`; `iosMain` → `ktor-client-darwin` (so all targets keep compiling — only the wasmJs path is exercised now).
- [ ] **Edge case (version alignment):** supabase-kt's BOM governs its own modules, **not** Ktor — pin a Ktor 3.x version compatible with the chosen supabase-kt release (check its release notes). Mismatched Ktor is the most likely build failure here.

#### Phase B2 — Build-time secret injection (BuildKonfig)
- [ ] Configure BuildKonfig in `shared/build.gradle.kts`:
  ```kotlin
  buildkonfig {
    packageName = "org.rurbaniak.smartchessboard.shared"
    defaultConfigs {
      buildConfigField(STRING, "SUPABASE_URL", supabaseUrl)
      buildConfigField(STRING, "SUPABASE_ANON_KEY", supabaseAnonKey)
    }
  }
  ```
- [ ] Source the values without committing them. Gradle does **not** auto-read `local.properties` for arbitrary keys, so load it explicitly, with a `-P`/env override for the production build:
  ```kotlin
  val localProps = java.util.Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
  }
  val supabaseUrl = (localProps.getProperty("SUPABASE_URL")
      ?: project.findProperty("SUPABASE_URL") as String?).orEmpty()
  val supabaseAnonKey = (localProps.getProperty("SUPABASE_ANON_KEY")
      ?: project.findProperty("SUPABASE_ANON_KEY") as String?).orEmpty()
  ```
- [ ] For **local dev**: put `SUPABASE_URL=…` and `SUPABASE_ANON_KEY=…` in `SmartChessboard/local.properties` (already gitignored per `AGENTS.md`).
- [ ] For the **production build**: pass `-PSUPABASE_URL=… -PSUPABASE_ANON_KEY=…` (anon key is public-safe). **Never** the service-role key.
- [ ] **Edge case (empty config):** if both sources are empty, the probe will fail fast with a clear "missing SUPABASE_URL" — surface that rather than silently shipping a blank client.

#### Phase B3 — Client + probe (shared) and status UI (webApp)
- [ ] `shared/src/commonMain/.../data/SupabaseProbe.kt` — create the client and a one-shot probe (representative; verify the exact DSL against the supabase-kt version):
  ```kotlin
  val supabase = createSupabaseClient(
    supabaseUrl = BuildKonfig.SUPABASE_URL,
    supabaseKey = BuildKonfig.SUPABASE_ANON_KEY,
  ) { install(Postgrest) }

  sealed interface ProbeResult {
    data class Ok(val visibleRows: Int) : ProbeResult      // 0 as anon → RLS enforced
    data class Error(val message: String) : ProbeResult
  }

  suspend fun probeSupabase(): ProbeResult = try {
    val rows = supabase.from("position_evals").select().decodeList<JsonObject>()
    ProbeResult.Ok(rows.size)
  } catch (t: Throwable) {
    ProbeResult.Error(t.message ?: "unknown error")
  }
  ```
- [ ] `webApp` UI: a minimal Compose screen that calls `probeSupabase()` and renders "Connected to Supabase ✓ (anon sees N rows — RLS enforced)" or the error. This is the visible proof the pipe works.
- [ ] **Edge case (suspend on wasmJs):** call the probe from a coroutine scope tied to the Compose UI; confirm coroutines + Ktor `Js` engine resolve on wasmJs at runtime (a missing engine surfaces as a runtime "no engine" error, not a compile error).

#### Phase B4 — COOP/COEP on the dev server + local verification (surface 1 of 2)
- [ ] Create `SmartChessboard/webApp/webpack.config.d/devServerHeaders.js`:
  ```js
  config.devServer = config.devServer || {};
  config.devServer.headers = {
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp",
  };
  ```
- [ ] Run the dev server with the local creds present:
  ```bash
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :webApp:wasmJsBrowserDevelopmentRun --no-daemon --console=plain
  ```
  At `http://localhost:8080`: the app renders **"Connected ✓"** (probe hit the real Supabase), and DevTools console shows `crossOriginIsolated === true` + `SharedArrayBuffer` defined. Network tab: the `position_evals` request is **200**, and the document carries both COOP+COEP headers.
- [ ] **Edge case (DSL drift):** if headers don't appear, verify the webpack-dev-server version and fall back to the function form (`config.devServer.headers = () => ({...})`) or `setupMiddlewares`. Object form is correct for dev-server v4/v5.
- [ ] **Edge case (probe fails locally):** 401/empty-but-error vs. 200/`[]` — re-check the anon key, the URL, and that the migration was pushed (Phase A2/A3).

---

### Track C — Build, production headers & deploy

#### Phase C1 — COOP/COEP on production via `_headers` (surface 2 of 2)
- [ ] Create `SmartChessboard/webApp/src/webMain/resources/_headers` (so the build copies it into the dist beside `index.html`):
  ```
  /*
    Cross-Origin-Opener-Policy: same-origin
    Cross-Origin-Embedder-Policy: require-corp
  ```
- [ ] **Edge case (not copied):** if `_headers` doesn't land in `productionExecutable/` after the build, fall back to a Gradle `doLast` copy on `wasmJsBrowserDistribution`, or write it into `productionExecutable/` in the shell right before `wrangler deploy`. Keep it byte-identical to the dev-server headers (`lessons.md` #3).
- [ ] **(optional)** add `X-Robots-Tag: noindex` under `/*` to keep the public `*.workers.dev` URL out of search indexes.

#### Phase C2 — Production build (with injected creds)
- [ ] From `SmartChessboard/`:
  ```bash
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :webApp:wasmJsBrowserDistribution \
    -PSUPABASE_URL="$SUPABASE_URL" -PSUPABASE_ANON_KEY="$SUPABASE_ANON_KEY" \
    --no-daemon --console=plain
  ```
- [ ] Confirm `SmartChessboard/webApp/build/dist/wasmJs/productionExecutable/` contains `index.html`, `webApp.js`, a `*.wasm`, `styles.css`, **and `_headers`**.
- [ ] Note the `.wasm` size (edge case: 25 MiB per-file cap — minified prod build is far under).
- [ ] **Edge case (secret leak check):** grep `productionExecutable/` to confirm **no service-role-key pattern** is present (only URL + anon key are expected) — `lessons.md` #4.

#### Phase C3 — Worker config
- [ ] Create `wrangler.toml` at the **repo root** (`/Users/rurbaniak/Projects/Private/10xDevs/claude/wrangler.toml`):
  ```toml
  name = "smart-chessboard-web"
  compatibility_date = "2026-06-01"
  preview_urls = true

  [assets]
  directory = "./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable"
  ```
  No `main`, no `binding` (assets-only). `directory` is relative to repo root, matching `infrastructure.md`.

#### Phase C4 — Preview deploy + verify over the network
- [ ] From repo root: `wrangler versions upload` → capture the versioned **preview URL**.
- [ ] Open it: app renders **"Connected ✓"** — this is the real cross-network proof (`*.workers.dev` browser → Supabase PostgREST). Console: `crossOriginIsolated === true`.
- [ ] `curl -I https://<preview-url>/` → COOP+COEP present; `curl -I https://<preview-url>/<file>.wasm` → `Content-Type: application/wasm`.
- [ ] **Edge case (no preview URL):** confirm `preview_urls = true` and the workers.dev subdomain (§B2). Previews are public — fine for the shell/probe (no private data).
- [ ] **Edge case (probe 200 but 0 rows):** that's success (RLS as anon). An error state instead means CORS/URL/key drift between the build args and the live project.

#### Phase C5 — Production deploy (human gate)
- [ ] **(human-confirmed)** From repo root: `wrangler deploy` → publishes to `smart-chessboard-web.<subdomain>.workers.dev`.
- [ ] Repeat the Phase C4 checks against the production URL (probe Connected ✓, headers, wasm MIME).
- [ ] `wrangler deployments list` → confirm the active deployment (the list `wrangler rollback` targets).

#### Phase C6 — Record follow-ups (Module 2 / CI)
- [ ] Note explicit out-of-scope follow-ups: CI `web-deploy.yml` (`vacation-workflow-todo.md`, injects creds + scoped CF token); custom domain; **Google OAuth** (Google Cloud OAuth client + Supabase provider + redirect-URL allowlist incl. the workers.dev URL); the **`games` table + owner RLS + index + trigger** and reading own games; **Room/OPFS** persistence (then the COOP/COEP "save a game, reload" test becomes real); the **`lichess-eval`** edge function; and a CI secret-scan that no service-role key appears in `productionExecutable/` (`lessons.md` #3/#4).

---

## Files to create / modify

| Path | New/Mod | Purpose |
|---|---|---|
| `supabase/migrations/<timestamp>_position_evals.sql` | new | Minimal domain-real schema + RLS |
| `SmartChessboard/gradle/libs.versions.toml` | mod | supabase-kt, Ktor engines, serialization, coroutines, BuildKonfig versions/plugins |
| `SmartChessboard/shared/build.gradle.kts` | mod | apply serialization + BuildKonfig; deps; per-target Ktor engines; secret injection |
| `SmartChessboard/shared/src/commonMain/.../data/SupabaseProbe.kt` | new | Supabase client + connectivity probe |
| `SmartChessboard/webApp/src/.../` (Compose status UI) | mod | Render probe result |
| `SmartChessboard/webApp/webpack.config.d/devServerHeaders.js` | new | COOP/COEP on the dev server |
| `SmartChessboard/webApp/src/webMain/resources/_headers` | new | COOP/COEP on the production host |
| `SmartChessboard/local.properties` | mod (local, gitignored) | Local `SUPABASE_URL` + anon key for dev |
| `wrangler.toml` (repo root) | new | Assets-only Worker config |

## External integrations touched

- **Cloudflare** — host. Global `wrangler` + OAuth login (human-gated); `wrangler deploy` creates/updates the Worker.
- **Supabase** — **now live**: hosted Postgres project (EU Frankfurt), one migration (`position_evals` + RLS), anon key baked into the bundle, web client reads via PostgREST. Service-role key stays out of the client. Google OAuth / `games` / edge function deferred to Module 2.
- **GitHub Actions** — out of scope (manual deploy).

## End-to-end verification (summary)

1. Backend: `curl` PostgREST `position_evals` as anon → **200 `[]`** (Phase A3).
2. Dev server: app shows **Connected ✓**, `crossOriginIsolated === true`, both headers on the document (Phase B4).
3. Build: `_headers` present in `productionExecutable/`; no service-role key in the bundle (Phase C2).
4. Preview URL: **Connected ✓** over the network, headers + wasm MIME correct (Phase C4).
5. Production URL: same checks pass; `deployments list` shows the active deployment (Phase C5).

## Rollback

- **Web**: `wrangler rollback [<version-id>]` within retained versions; for older targets rebuild from the commit and redeploy. The client is stateless (no local persistence yet), so a frontend rollback is data-safe.
- **Database**: migrations are **append-only** (`supabase/AGENTS.md`) — to change schema, add a new migration; do not edit/revert the pushed one. `position_evals` is domain-real and intended to stay.
- Destructive actions (delete Worker, drop a table, rotate a key) stay **human-only**.