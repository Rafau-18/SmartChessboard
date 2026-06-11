---
starter_id: custom-kmp-compose
package_manager: gradle
project_name: smart-chessboard
hints:
  language_family: multi
  team_size: solo
  deployment_target: cloudflare-workers-static-assets
  ci_provider: github-actions
  ci_default_flow: manual-promotion
  bootstrapper_confidence: best-effort
  path_taken: custom
  quality_override: true
  self_check_answers:
    typed: true
    from_official_starter: true
    conventions: true
    docs_current: true
    can_judge_agent: true
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: false
---

## Why this stack

Solo developer shipping a multi-platform smart chessboard MVP: Compose Multiplatform mobile app (Android + iOS + WasmJS), ESP32 firmware in C++, and Supabase used as managed configuration rather than custom server code. The standard tech-stack-selector registry has no entry for the KMP + ESP32 + Supabase combination, so this file was written manually and `/10x-bootstrapper` was skipped — scaffolding runs through official wizards (kmp.jetbrains.com, PlatformIO + ESP-IDF, supabase CLI). KMP + Compose was picked for shared chess logic and UI across mobile and web from one Kotlin codebase; ESP-IDF over Arduino because matrix scan rate, custom GATT, and BLE latency budgets in `prd-firmware.md` exceed Arduino's comfortable abstraction; Supabase eliminates a bespoke server while keeping managed auth, RLS-scoped Postgres, and one Edge Function proxying chess-evaluation APIs (Lichess Cloud Eval with a Chess-API.com Stockfish fallback) per `contract-surfaces.md` §3.3. Web is intentionally scoped to pass-and-play, replay, and analysis only — no BLE on web. Room 3.0 alpha and Navigation 3 are conscious bets on alpha tooling for unified Android/iOS/Wasm code, accepting upgrade churn as the price of one persistence and navigation layer.

---

# Extension beyond bootstrapper schema

The sections below are **not** consumed by `/10x-bootstrapper` — that skill was intentionally skipped because `references/starter-registry.yaml` has no entry for the KMP + ESP32 + Supabase combination. They document per-sub-project decisions for future maintainers and AI agents reading this file as project context.

## Frontmatter notes (why some flags look counterintuitive)

- **`has_ai: false`** despite using the Lichess Cloud Eval API. The flag's schema semantics are "LLM/embedding/AI features in scope". Lichess Cloud Eval is a deterministic chess engine (Stockfish-style centipawn evaluation + best-move), not an LLM call — so the flag is false even though the project does call an external evaluation API. The integration is described in the backend sub-project table and `contract-surfaces.md` §3.3.
- **`has_realtime: false`** despite BLE being a realtime transport. The flag's schema semantics are backend-focused — "websockets/live-update/presence in scope" — driving Supabase Realtime / websocket scaffolding decisions. The MVP backend has no such surface (game sync is plain CRUD over REST). BLE realtime event handling lives entirely in the mobile sub-project and is described in the BLE row of that table.

## Sub-project decomposition

The project is a monorepo with three sub-projects, kept under one git history because cross-cutting changes (BLE protocol revisions, schema changes touching firmware + mobile + backend at once) are frequent in MVP and atomic commits across sub-projects are valuable:

- `SmartChessboard/` — Compose Multiplatform app (Android/iOS/WasmJS); Gradle multi-module: `:androidApp`, `:shared`, `:webApp` (mobile). *(Wizard output landed here; the old plan said `composeApp/` — superseded.)*
- `firmware/` — ESP32 firmware project (PlatformIO + ESP-IDF)
- `supabase/` — Postgres migrations + one Edge Function (no bespoke server)
- `docs/reference/contract-surfaces.md` — protocol contract between sub-projects (BLE GATT, REST/Edge Function shapes, RLS scoping)

Splitting into separate repos later is supported by `git subtree split --prefix=<dir>` and remains an option if firmware develops its own release cadence or outside contributors join one sub-project only.

## Architecture overview

The mobile app follows **Clean Architecture** layering, with boundaries enforced by Kotlin module visibility and explicit interfaces. All three layers live primarily in `commonMain` so they ship to Android, iOS, and WasmJS targets identically:

- **`domain/`** — pure Kotlin: entities (`Game`, `Move`, `Position`, `Player`), use cases (`StartGameUseCase`, `SubmitMoveUseCase`, `ListUserGamesUseCase`), repository **interfaces**. Zero dependencies on Compose, Supabase, Room, BLE — testable as pure logic with no platform context. The chess rules engine lives here.
- **`data/`** — implementations of repository interfaces: Supabase clients, Room DAOs, the BLE board adapter, the Lichess Cloud Eval client wrapper. Mostly `commonMain` (where supabase-kt and Room 3.0 are KMP), with `expect`/`actual` for BLE platform-specific bits.
- **`presentation/`** — ViewModels (state holders) + Compose UI. Reads `domain/` use cases, exposes UI state. Never imports `data/` directly.

**UI architecture pattern: MVVM by default** (decided 2026-06-10, S-01) — ViewModels expose UI state as `StateFlow<UiState>` and take intents as methods. MVI is permitted only for screens with a genuinely complex, event-heavy state machine (live game board, BLE flows) and must be justified in that change's plan. The Clean Architecture layering above is independent of that choice: both patterns live entirely in `presentation/` and consume `domain/` use cases identically. Rationale and the full rule are recorded in `lessons.md`.

**Dependency injection: Koin KMP** (decided 2026-06-10, S-01) — a single `initKoin()` bootstrap is called from each platform entry point (Android `Application`, iOS app start, web `main()`); clients, repositories, and ViewModels register through Koin modules. No parallel service locators or ad-hoc singletons. Recorded in `lessons.md`.

Firmware (`firmware/`) and backend (`supabase/`) do not follow Clean Architecture — they are too small and too tightly bound to their respective platforms. Firmware is structured around FreeRTOS tasks (matrix-scan task, BLE-server task, button-event task). Backend is SQL migrations + RLS policies + one Edge Function, no application layer.

## Mobile sub-project (`SmartChessboard/`)

| Concern | Choice | Notes |
|---|---|---|
| Language / UI | Kotlin Multiplatform + Compose Multiplatform | shared `commonMain` across all targets |
| Targets | `androidMain`, `iosMain`, `wasmJsMain` | Web enabled from day 1 in KMP wizard; may be frozen post-MVP if it blocks any must-have |
| Build | Gradle Kotlin DSL | standard with KMP wizard |
| Bootstrap source | [kmp.jetbrains.com](https://kmp.jetbrains.com/) wizard | downloads .zip with the three targets pre-configured |
| Chess rules engine | own implementation in `commonMain` | full FR-005 legality (check, pinned-piece, castling, en passant, promotion); checkmate/stalemate from legality engine (FR-007); OSS chess libs as reference only, no fork |
| Local DB | Room 3.0 alpha | KMP + WasmJS support via `androidx.sqlite:sqlite-web` (Web Worker + OPFS); accepting alpha API churn for unified persistence layer |
| Networking | Ktor Client (transitively via supabase-kt) | KMP-native |
| Supabase SDK | `supabase-kt` (official KMP SDK) | Auth + Postgrest + Edge Functions |
| Serialization | `kotlinx.serialization` | JSON, PGN parsing helpers |
| Date / time | `kotlinx.datetime` | KMP-native |
| BLE (realtime transport) | Kable as first try; fallback to `expect`/`actual` with platform-native APIs (Android BluetoothLe + iOS CoreBluetooth) if Kable becomes blocking | realtime event stream from board to app (`SQUARE_EVENT`, `BUTTON_EVENT`, `BOARD_SNAPSHOT`) per `contract-surfaces.md` §1.2. **Not used on WasmJS target** — web has no board connection |
| Navigation | Navigation 3 (`androidx.navigation3:navigation3-ui` ≥ 1.0.0-alpha05) | requires polymorphic `kotlinx.serialization` sealed-key hierarchy for iOS + WasmJS destinations |
| ViewModel | `lifecycle-viewmodel-compose` KMP edition | |
| Architecture pattern | Clean Architecture layers (`domain/` / `data/` / `presentation/`); **MVVM by default, MVI only for genuinely event-heavy screens with written justification** | decided 2026-06-10 (S-01); see "Architecture overview" section and `lessons.md` |
| Dependency injection | **Koin KMP** | decided 2026-06-10 (S-01); one `initKoin()` bootstrap per platform, no parallel service locators — see `lessons.md` |
| Testing — unit | `kotlin-test` + `kotlinx-coroutines-test` + Turbine | tests in `commonTest`; pure-logic tests fastest on `jvm()` target |
| Testing — UI | `runComposeUiTest` (Compose Multiplatform UI Test, `compose.ui:ui-test`) | one Compose UI test suite runs across Android + iOS + desktop + web from `commonTest`; **replaces Espresso** (which is XML-View-only, not applicable to Compose) |
| Mocking | **MocKMP** (KMP-native, compile-time generated) + fakes-first discipline | MocKMP avoids reflection-based gotchas on iOS that MockK has; prefer hand-written fakes for `domain/` boundaries — cleaner test reads, easier to maintain in pure-logic-heavy chess code |
| E2E (mobile cross-platform) | **deferred to post-MVP** — Maestro is the leading candidate | YAML flows running same scenario on Android + iOS; nice-to-have, not in MVP unless course requires |
| Code quality | ktlint + detekt | CI-only, no local pre-commit hook |

## Firmware sub-project (`firmware/`)

| Concern | Choice | Notes |
|---|---|---|
| Language | C++ | per `prd-firmware.md` |
| Toolchain | **PlatformIO + ESP-IDF framework** | chosen over Arduino because matrix scan + custom GATT + BLE latency budget exceed Arduino's comfortable abstraction; PlatformIO gives VS Code integration, lockfile (`platformio.ini`), and profile builds (debug/release/diag) |
| BLE stack | NimBLE (IDF 5.x default) | smaller footprint than Bluedroid, BLE 5.0, actively maintained |
| Editor | VS Code with PlatformIO extension | shares the agent-friendly env with mobile dev |
| Bootstrap source | `pio project init --board <tbd> --project-option "framework=espidf"` | board flag resolved when ESP32 variant is decided |
| ESP32 variant | **TBD** — carry-over from `prd-firmware.md` Open Question FW-1 | depends on existing physical prototype |
| Power source | **TBD** — carry-over from FW-2; PRD recommends USB-only for MVP | battery support is post-MVP |
| Reed-switch matrix wiring | **TBD** — carry-over from FW-3 | document existing prototype topology |
| GATT UUIDs | **TBD** — carry-over from FW-5 | assigned during firmware implementation, reflected back into `contract-surfaces.md` §1.2 |
| Testing — unit | **Catch2** (host-side C++ unit tests) | tests of pure logic (debouncing, square indexing, BLE message encoding) run on dev host without hardware via separate CMake target; full firmware-on-hardware integration tests are manual per `prd-firmware.md` |
| Testing — emulator | programmatic reed-switch emulator (PRD OQ1 resolution) | mobile-side test harness that produces the same `board_event` stream as a real board — drives the full physical-mode flow end-to-end without hardware |
| Distribution | manual flash via `pio run -t upload` | OTA over BLE is post-MVP per `prd-firmware.md` |

## Backend sub-project (`supabase/`)

| Concern | Choice | Notes |
|---|---|---|
| Platform | Supabase | Auth + Postgres + Edge Functions; no bespoke server in MVP |
| Region | EU Frankfurt (`eu-central-1`) | closest to Polish users, GDPR-friendly jurisdiction |
| Tier | Free | project pauses after 7 days of inactivity (acceptable for small-circle MVP); reactive upgrade to Pro ($25/mo) if pauses become disruptive |
| Identity provider | Google OAuth via Supabase Auth | auto-create account on first sign-in per PRD FR-001; Apple Sign In and others deferred (no App Store target in MVP) |
| Schema management | Supabase CLI + `supabase/migrations/*.sql` in git | version-controlled, repeatable; agent edits SQL files directly |
| Local development | `supabase start` (Docker stack) | full Postgres + GoTrue + Storage + Studio locally; mirrors CI environment |
| Edge Function runtime | Deno + TypeScript | only option; one function in MVP: eval proxy — Lichess Cloud Eval with Chess-API.com fallback (Stockfish 18 NNUE, any FEN, no API key; community service without SLA, alternate: stockfish.online) per `contract-surfaces.md` §3.3 |
| Per-user data scoping | Postgres Row-Level Security (RLS) | every game/eval row carries `user_id`; policies enforced at the database, not in app code |
| Testing — DB / RLS | **pgTAP** via `supabase test db` | tests RLS policies, migration correctness, schema constraints; critical because RLS bugs cannot be caught by mocked tests |
| Testing — Edge Function | **Deno built-in test** (`deno test`) | tests Lichess proxy in `supabase/functions/lichess-eval/`; runs locally and in CI without network egress to real Lichess |
| Realtime / Storage | not used in MVP | game sync is plain CRUD over REST; presence/live-updates explicitly out of scope |

## CI/CD

| Concern | Choice | Notes |
|---|---|---|
| Primary CI | GitHub Actions | macOS runner for iOS, ubuntu-latest for Android + firmware + Supabase E2E |
| Supabase in CI | `supabase/setup-cli@v1` + `supabase start` in runner | enables real RLS / migration / Edge Function tests (no mocks) |
| Default deployment flow | manual-promotion | nothing auto-deploys on merge in MVP — even the web publish is gated by a manual workflow trigger or release tag |
| Web hosting | **Cloudflare — Workers Static Assets** (`wrangler deploy`, assets-only Worker) | static WasmJS bundle from `SmartChessboard/webApp/build/dist/wasmJs/productionExecutable/`; built in GitHub Actions, then `wrangler deploy` (Cloudflare cannot run the Gradle/KMP build). **COOP/COEP headers are mandatory** on both the local dev server and the host for Room OPFS — see `lessons.md` and `infrastructure.md`. Chosen over Cloudflare Pages: unmetered free static bandwidth, auto `application/wasm` MIME + compression, and a real `wrangler rollback`. Full decision + risk register: `context/foundation/infrastructure.md`. Preview deploys per branch; manual prod promotion |
| Mobile distribution | **TBD** — debug build only in MVP CI | TestFlight (Apple Dev $99/yr) and/or Play Internal Testing ($25 one-time) is a post-MVP decision once debug build is green |
| Code quality | ktlint + detekt jobs in GH Actions | CI is the only gate; no local pre-commit hook |
| Secondary CI (learning) | Bitbucket Pipelines | mirror from GitHub via `pixta-dev/repository-mirroring-action`; reduced scope: Android debug build + ktlint + detekt only (no iOS / no Supabase / no firmware); meant for learning the BB ecosystem, not redundant production CI |

## Open / Deferred decisions

Carry-overs from `prd-firmware.md` and decisions consciously postponed to later phases. Track resolution in `context/foundation/lessons.md` or revise this file when resolved.

1. **ESP32 variant** (FW-1) — depends on physical prototype hardware
2. **Power source** for firmware (FW-2) — recommend USB-only for MVP; battery is post-MVP enhancement
3. **Reed-switch matrix wiring topology** (FW-3) — document existing prototype wiring
4. **GATT service / characteristic UUIDs** (FW-5) — assigned during firmware implementation, mirror into `contract-surfaces.md` §1.2
5. **Mobile distribution path** for MVP friends — TestFlight + Play Internal vs APK sideload + iOS-on-dev-machine only
6. **Compose Multiplatform on Web viability** — if a must-have feature is blocked on web (BLE was already excluded), freeze web development and re-evaluate post-MVP
7. ~~**UI architecture pattern** — MVVM vs MVI~~ — **RESOLVED 2026-06-10 (S-01, `google-signin-own-history`)**: MVVM by default, MVI only for genuinely event-heavy screens with written justification. See "Architecture overview" and `lessons.md`.
8. ~~**Dependency injection library** — Koin KMP vs hand-rolled service locator~~ — **RESOLVED 2026-06-10 (S-01, `google-signin-own-history`)**: Koin KMP, one `initKoin()` bootstrap per platform, no parallel service locators. See `lessons.md`.
9. **Maestro E2E** — deferred to post-MVP; add to CI as separate milestone once UI stabilizes (unless a later course lesson requires E2E earlier)

## Why `/10x-tech-stack-selector` and `/10x-bootstrapper` were skipped

`references/starter-registry.yaml` in `/10x-tech-stack-selector` has no entry for the language family or product shape of this project — mobile starters cover Expo (RN, JS) and Flutter (Dart) only; there is no Kotlin Multiplatform card, no ESP32 / embedded category, and no managed-Supabase-only backend variant. Running the selector would force-fit one of the existing mobile starters or land on `bootstrapper_confidence: best-effort` with no real starter behind it.

`/10x-bootstrapper`'s registry-sync validator (`scripts/validate-starter-registry-sync.mjs`) refuses any `starter_id` not present in the registry, so even a hand-crafted `tech-stack.md` would not unblock bootstrapping.

The substitute: scaffold each sub-project through its official wizard, then write `context/changes/bootstrap-verification/verification.md` by hand recording the commands run, exit codes, and any audit findings.

## References

- PRD: `context/foundation/prd.md`
- Firmware PRD: `context/foundation/prd-firmware.md`
- Infrastructure decision (web hosting): `context/foundation/infrastructure.md`
- Cross-sub-project contracts: `docs/reference/contract-surfaces.md`
- Archived pre-split PRD: `context/archive/prd-pre-firmware-split.md`
