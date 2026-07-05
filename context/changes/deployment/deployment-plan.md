# First Production Deploy — WasmJS Web Target → Cloudflare, with a live Supabase connection

> Status: **EXECUTED 2026-06-01.** Live at https://smart-chessboard-web.<subdomain>.workers.dev (version `04ecd258`, 100% traffic). Browser-confirmed "Connected to Supabase ✓". Two literal sub-steps were superseded rather than run verbatim — see the annotations on **Phase B4** (local dev-server check) and **Phase C4** (preview URL): both were covered by the stronger production verification in **Phase C5**.

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
1. **Scope = manual first deploy only.** Local `wrangler`, human-gated. CI (`web-deploy.yml`) stays out of scope — tracked in the `github-ci-and-distribution` change.
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

> **TL;DR for THIS deploy:** Node ≥ 20; `wrangler` installed globally + `wrangler login`; a free Cloudflare account with a `*.workers.dev` subdomain; a free **Supabase project** (West EU / Ireland — see note) with its URL + anon key captured; the **Supabase CLI** installed + linked. GitHub: nothing.

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
2. **Create a project** (browser): organization → **New project**. Name e.g. `smart-chessboard`; **Region = West EU (Ireland)** (provisioned 2026-05-31; `tech-stack.md` named Frankfurt, but Ireland was chosen at creation — functionally equivalent for MVP, accepted by user 2026-06-01); set a strong DB password (store it in your password manager). Wait for provisioning (~2 min).
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

Manual deploy was chosen → no GitHub Actions, repo secrets, or push required. CI (`web-deploy.yml`) is tracked in the `github-ci-and-distribution` change.

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
- [x] All **§F** commands succeed; both browser checks pass. *(JDK note: system default is JBR 25; the build runs on Android Studio's JBR 21.0.10 via inline `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"` to avoid Kotlin 2.3.21 × JDK 25 risk.)*
- [x] `wrangler` global (4.95.0) + `wrangler login` confirmed (**§C**) — `<redacted>`.
- [x] Supabase project created, credentials captured, `supabase link` done (**§D**) — ref `meivdixbetoeqhcjnovs`.

---

### Track A — Supabase backend (production)

#### Phase A1 — Project & credentials (done in Prerequisites §D)
- [x] Hosted project live in West EU (Ireland); `SUPABASE_URL` + anon key captured; CLI linked. *(Gate-check only — work happened in §D.)*

#### Phase A2 — Minimal domain-aligned schema migration
- [x] Create the migration file: `supabase migration new position_evals` → produced `supabase/migrations/20260531233302_position_evals.sql`.
- [x] Fill it with the smallest real contract slice (matches `docs/reference/contract-surfaces.md`):
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
- [x] Push to the hosted DB: `supabase db push --linked` (applied to ref `meivdixbetoeqhcjnovs`; dry-run confirmed first).
- [x] **Edge case (append-only):** never edit a pushed migration; any change is a **new** migration (`supabase/AGENTS.md`). This table is domain-real, so no teardown is planned. *(Respected — pushed migration left untouched.)*
- [x] **Edge case (no `games` yet):** the probe deliberately reads `position_evals`, not `games` — `games` needs auth to be meaningful and lands in Module 2. *(Followed.)*

#### Phase A3 — Verify the backend independently of the app
- [x] Confirm the table + RLS via a raw PostgREST check that mimics the anon client (Studio check optional):
  ```bash
  curl -s "$SUPABASE_URL/rest/v1/position_evals?select=*" \
    -H "apikey: $SUPABASE_ANON_KEY" -H "Authorization: Bearer $SUPABASE_ANON_KEY" -i | head -n 20
  ```
  Expect **HTTP 200** with body `[]` (RLS denies anon rows but the request succeeds → exactly the probe's success condition).
- [x] **Verified**: `curl` returned `HTTP/2 200` + `[]` (content-length 2) as `anon`.

---

### Track B — Web app ↔ Supabase wiring (`SmartChessboard/`)

#### Phase B1 — Dependencies
- [x] In `gradle/libs.versions.toml` added versions + libraries: **supabase-kt BOM 3.6.0** + `postgrest-kt`, **Ktor 3.5.0** engines (`ktor-client-js` wasmJs; `ktor-client-okhttp` android; `ktor-client-darwin` ios), **kotlinx-serialization-json 1.11.0**, **kotlinx-coroutines-core 1.11.0**, plus the **BuildKonfig 0.21.2** + **kotlin-serialization** plugins.
- [x] In `shared/build.gradle.kts`:
  - applied `org.jetbrains.kotlin.plugin.serialization` and `com.codingfeline.buildkonfig`.
  - `commonMain`: supabase BOM platform + `postgrest-kt`, `kotlinx-serialization-json`, `kotlinx-coroutines-core`.
  - per-target Ktor engine: `wasmJsMain` → `ktor-client-js`; `androidMain` → `ktor-client-okhttp`; `iosMain` → `ktor-client-darwin`.
- [x] **Edge case (version alignment):** supabase-kt 3.6.0 ships against Ktor 3.4.3; the catalog pins Ktor 3.5.0 (one minor ahead, binary-compatible within 3.x). Build is green; if a Ktor ABI issue ever surfaces, drop Ktor to 3.4.3.

#### Phase B2 — Build-time secret injection (BuildKonfig)
- [x] Configured BuildKonfig in `shared/build.gradle.kts`. *(Deviation: `packageName = "org.rurbaniak.smartchessboard"` (no `.shared`) so `BuildKonfig` sits in the module's main package; generated + verified.)*
  ```kotlin
  buildkonfig {
    packageName = "org.rurbaniak.smartchessboard.shared"
    defaultConfigs {
      buildConfigField(STRING, "SUPABASE_URL", supabaseUrl)
      buildConfigField(STRING, "SUPABASE_ANON_KEY", supabaseAnonKey)
    }
  }
  ```
- [x] Sourced the values without committing them — explicit `local.properties` reader with a `-P`/env fallback. *(DSL gotcha hit & fixed: `java.util.Properties()` collides with the Gradle `java` accessor → use `import java.util.Properties` + `Properties()`. Recorded in `lessons.md`.)*
  ```kotlin
  val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
  }
  val supabaseUrl = (localProps.getProperty("SUPABASE_URL")
      ?: project.findProperty("SUPABASE_URL") as String?).orEmpty()
  val supabaseAnonKey = (localProps.getProperty("SUPABASE_ANON_KEY")
      ?: project.findProperty("SUPABASE_ANON_KEY") as String?).orEmpty()
  ```
- [x] For **local dev**: `SUPABASE_URL` + `SUPABASE_ANON_KEY` added to `SmartChessboard/local.properties` (gitignored — confirmed via `git check-ignore`).
- [x] For the **production build**: *(deviation — used the `local.properties` path for this manual local deploy rather than `-P` flags; identical result since the anon key is public. The `-P` path is reserved for CI, where there is no `local.properties`. Bundle verified to carry the anon key + URL.)*
- [x] **Edge case (empty config):** `probeSupabase()` guards on empty URL/key and returns a clear "missing SUPABASE_URL / SUPABASE_ANON_KEY" error instead of shipping a blank client.

#### Phase B3 — Client + probe (shared) and status UI (webApp)
- [x] `shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/SupabaseProbe.kt` — client (lazy) + one-shot probe; verified against supabase-kt 3.6.0 DSL.
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
- [x] Status UI: `App()` (shared, rendered by `webApp`) runs the probe in a `LaunchedEffect` and renders "Connected to Supabase ✓ (anon sees N rows — RLS enforced)" or the error. *(Placed in shared `App()` rather than a webApp-only screen — `App()` is exactly what `webApp/main.kt` mounts.)*
- [x] **Edge case (suspend on wasmJs):** probe called from `LaunchedEffect` (Compose coroutine scope); coroutines + Ktor `Js` engine resolved at runtime — **browser-confirmed "Connected ✓"** on the live URL.

#### Phase B4 — COOP/COEP on the dev server + local verification (surface 1 of 2)
- [x] Created `SmartChessboard/webApp/webpack.config.d/devServerHeaders.js` (COOP/COEP object form, for future local dev).
  ```js
  config.devServer = config.devServer || {};
  config.devServer.headers = {
    "Cross-Origin-Opener-Policy": "same-origin",
    "Cross-Origin-Embedder-Policy": "require-corp",
  };
  ```
- [ ] ~~Run the dev server at `http://localhost:8080`~~ **SUPERSEDED — not run this session.** The dev-server check exists only to prove COOP/COEP + the probe before shipping; that proof was obtained on the **production** surface instead (Phase C5: browser "Connected ✓", `crossOriginIsolated`, headers via curl), which is strictly stronger (real cross-network). The header file above stays for future local dev. *Re-run `./gradlew :webApp:wasmJsBrowserDevelopmentRun` later if you want the localhost check too.*
- [ ] ~~Edge case (DSL drift)~~ — N/A this session (dev server not run); object form retained as the documented default for dev-server v4/v5.
- [x] **Edge case (probe fails locally):** the 200/`[]` vs. error distinction was validated **on production** — the probe rendered "Connected ✓", confirming anon key + URL + pushed migration are all consistent.

---

### Track C — Build, production headers & deploy

#### Phase C1 — COOP/COEP on production via `_headers` (surface 2 of 2)
- [x] Created `SmartChessboard/webApp/src/webMain/resources/_headers` (build copies it into the dist beside `index.html`).
- [x] **Edge case (not copied):** confirmed `_headers` **did** land in `productionExecutable/` after the build — no fallback copy needed.
- [x] **(optional)** added `X-Robots-Tag: noindex` under `/*` — confirmed served on the live document.

#### Phase C2 — Production build (with injected creds)
- [x] Built from `SmartChessboard/` with `JAVA_HOME` → JBR 21 (creds read from `local.properties`; no `-P` needed for this local build):
  ```bash
  JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :webApp:wasmJsBrowserDistribution \
    --no-daemon --console=plain
  ```
  *(First build failed on `:kotlinWasmStoreYarnLock` after adding `ktor-client-js` — fixed with `./gradlew kotlinWasmUpgradeYarnLock`, then rebuilt green. Recorded in `lessons.md`.)*
- [x] Confirmed `productionExecutable/` contains `index.html`, `webApp.js`, two `*.wasm`, `styles.css`, **and `_headers`**.
- [x] `.wasm` sizes: 8.3 MiB + 2.6 MiB — far under the 25 MiB per-file cap.
- [x] **Edge case (secret leak check):** grepped `productionExecutable/` — **no `service_role` pattern**; only the public anon key + URL present (the latter fragmented across wasm data segments).

#### Phase C3 — Worker config
- [x] Created `wrangler.toml` at the **repo root** (assets-only, `preview_urls = true`, `compatibility_date = "2026-06-01"`):
  ```toml
  name = "smart-chessboard-web"
  compatibility_date = "2026-06-01"
  preview_urls = true

  [assets]
  directory = "./SmartChessboard/webApp/build/dist/wasmJs/productionExecutable"
  ```
  No `main`, no `binding` (assets-only). `directory` is relative to repo root, matching `infrastructure.md`.

#### Phase C4 — Preview deploy + verify over the network
- [x] Ran `wrangler versions upload` from repo root → uploaded version `b480c8cc` successfully.
- [ ] ~~Capture + open the versioned preview URL~~ **SUPERSEDED.** No version preview URL was emitted: on a Worker that has **never been deployed**, the `workers.dev` route isn't active yet, so there is no reachable preview host pre-first-deploy. Rather than force-enable it, we went straight to the human-gated production deploy (Phase C5), which is the stronger cross-network proof. The upload itself confirmed the asset pipeline works end-to-end.
- [x] **Edge case (no preview URL):** root cause understood (see above) — `preview_urls = true` is set; version preview hosts simply require the script to be deployed once first. Not a config error.
- [x] **Edge case (probe 200 but 0 rows):** confirmed success on production — the live app shows "Connected ✓ (anon sees 0 rows — RLS enforced)", i.e. no CORS/URL/key drift.

#### Phase C5 — Production deploy (human gate)
- [x] **(human-confirmed 2026-06-01)** `wrangler deploy` from repo root → published to **https://smart-chessboard-web.<subdomain>.workers.dev** (workers.dev auto-enabled on first deploy).
- [x] Production checks: browser **"Connected ✓"** (user-confirmed); `curl -I /` → COOP `same-origin` + COEP `require-corp` + `X-Robots-Tag: noindex`; `curl -I /<file>.wasm` → `Content-Type: application/wasm`.
- [x] `wrangler deployments list` → active deployment is version `04ecd258` at 100% (the `wrangler rollback` target).

#### Phase C6 — Record follow-ups (Module 2 / CI)
- [x] Out-of-scope follow-ups recorded (here + the `github-ci-and-distribution` change): CI `web-deploy.yml` (injects creds + scoped CF token); custom domain; **Google OAuth** (Google Cloud OAuth client + Supabase provider + redirect-URL allowlist incl. the workers.dev URL); the **`games` table + owner RLS + index + trigger** and reading own games; **Room/OPFS** persistence (then the COOP/COEP "save a game, reload" test becomes real); the **`lichess-eval`** edge function; and a CI secret-scan that no service-role key appears in `productionExecutable/` (`lessons.md` #3/#4).
- [ ] **Open (housekeeping):** `tech-stack.md` + `supabase/AGENTS.md` still say region "Frankfurt"; actual project is West EU (Ireland). Update when convenient (not deploy-blocking).

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
- **Supabase** — **now live**: hosted Postgres project (West EU / Ireland), one migration (`position_evals` + RLS), anon key baked into the bundle, web client reads via PostgREST. Service-role key stays out of the client. Google OAuth / `games` / edge function deferred to Module 2.
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