# Smart Chessboard — Session Handoff (updated 2026-05-29)

## Status

Module 1 lessons **L1–L4 complete**. Next session continues at **L5 — infra/deploy**
(confirm exact scope from the lesson material in `10xDevsLekcje/Module1/`, now
un-ignored and readable from agent sessions). L4 (agent onboarding) was completed
this session: per-module `AGENTS.md` is the source of truth in each scope; each
`CLAUDE.md` is a thin `@AGENTS.md` import; `/10x-rule-review` scored all three
module `AGENTS.md` healthy after one WARN fix and one factual fix (see Decisions
below).

The earlier workflow deviation still holds — KMP + ESP32 + Supabase has no
starter-registry entry, so `/10x-tech-stack-selector` and `/10x-bootstrapper` are
intentionally skipped. See `context/foundation/lessons.md` lesson #1.

## Module 1 progress

- **L1** PRD ✓
- **L2** tech-stack ✓ (hand-written, no selector)
- **L3** bootstrap ✓ (manual path): three sub-project scaffolds + verification + permission policy
- **L4** agent onboarding ✓ — **DONE this session**: per-module `AGENTS.md`
  unified across root / SmartChessboard / firmware; each `CLAUDE.md` collapsed
  to a thin `@AGENTS.md` import; `/10x-rule-review` ran on all three module
  `AGENTS.md` — verdicts healthy after one WARN fix (SC version pins →
  `libs.versions.toml` reference) and one contradiction fix (root vs firmware
  `IDF_PATH`).
- **L5** infra/deploy → **NEXT**

## Monorepo layout (actual, as built)

Three sub-projects live as siblings **under `claude/`** (subdir layout chosen
over root-merge to keep the docs repo and code separate):

```
claude/
├── AGENTS.md, CLAUDE.md           # AGENTS.md = source of truth; CLAUDE.md = thin @AGENTS.md import (same pattern in each module)
├── context/foundation/            # prd.md, prd-firmware.md, tech-stack.md, lessons.md
├── docs/                          # bootstrap-verification.md, reference/contract-surfaces.md
├── SmartChessboard/               # mobile (KMP) — the wizard scaffold
├── firmware/                      # ESP32 diagnostic firmware (PlatformIO + ESP-IDF)
└── supabase/                      # backend skeleton (config.toml)
```

(The old plan said `composeApp/` at repo root — superseded; mobile is
`claude/SmartChessboard/`.)

## Sub-project state

| Sub-project | State | Verified |
|---|---|---|
| Mobile `SmartChessboard/` | ✅ scaffold, tests green | `./gradlew tasks` + host/iOS/wasm smoke tests |
| Firmware `firmware/` | ✅ bringup flashed on hardware, **parked** | reed-matrix scan live on DevKit V1 |
| Backend `supabase/` | 🟡 skeleton + tooling + Docker | `supabase init`; `supabase start` = Module 2 |

### Mobile (`SmartChessboard/`)
- KMP wizard (kmp.jetbrains.com); targets **Android + iOS + WasmJS**. The
  wizard's Kotlin/JS web target was **removed** — web is WasmJS-only.
- Tests pass on JVM host, iOS simulator (arm64), and wasm.
- **IDE split** (AGP 9.0.1 vs IntelliJ which tops out at 9.0.0-alpha06):
  edit `androidApp/` in **Android Studio**, `shared/`+`webApp/` in IntelliJ,
  `iosApp/` in Xcode. Do not load `androidApp` into IntelliJ.

### Firmware (`firmware/`) — parked
- Diagnostic reed-matrix bringup; **flashed & verified on the DevKit V1**
  (ESP32-D0WDQ6). Serial console renders the 8×8 board, change-driven + debounced.
- `src/pins.h` uses the **existing prototype wiring** (rows D32→D13, cols D19→D15);
  file-g column moved off GPIO2 (onboard LED) to **GPIO21**. GPIO12 (ROW6,
  flash-strapping) is a documented watch item. Migration to the hazard-free map
  in `firmware/PINOUT.md`/`WIRING.md` is a TODO.
- **4 reed switches found broken** — user repairing on the side. Firmware phase
  intentionally paused; don't resume unless the user asks.
- Hardware inventory + per-board pinouts + wiring sheet: `firmware/HARDWARE.md`,
  `PINOUT.md`, `WIRING.md`. Boards: DevKitC V4 (primary), DevKit V1 (backup),
  ESP-12E (ESP8266 — unsuitable, no BLE).

### Backend (`supabase/`)
- `supabase init` done (`config.toml` + `.gitignore`). Supabase CLI v2.101.0
  installed; Docker Desktop installed & running (v29.5.2).
- `supabase start` (local Postgres/Auth/PostgREST/Edge Runtime/Studio) and the
  schema / RLS / `lichess-eval` Edge Function are **Module 2** work.
- **No Supabase account needed** until a hosted project is created (Module 2).

## Canonical files (under `claude/`)

- `context/foundation/prd.md` — mobile + system PRD
- `context/foundation/prd-firmware.md` — ESP32 firmware PRD
- `context/foundation/tech-stack.md` — hand-written stack hand-off (architecture, per-sub-project tables, CI/CD, open decisions)
- `context/foundation/lessons.md` — recurring rules (`/10x-lesson` format); L4 will append more
- `docs/reference/contract-surfaces.md` — BLE GATT + REST + RLS contract between sub-projects
- `docs/bootstrap-verification.md` — **M1L3 verification log** (mobile/firmware/backend + §4 permission policy)
- `context/archive/prd-pre-firmware-split.md` — pre-split PRD (history)

## Permissions / harness (M1L3 in-execution gate)

Policy lives **globally** at `~/.claude/settings.json` (all four tool profiles):
allow = npm/npx/node + local-only git + Read/Edit/Write + `Bash(./gradlew *)` +
`Bash(pio *)`; ask = curl/wget/git push; deny = `rm -rf *`. Eval order
deny→ask→allow. Documented in `docs/bootstrap-verification.md` §4.

## Persistence (auto-loaded in new sessions)

Auto-memory at `~/.claude/projects/-Users-rurbaniak-Projects-Private-10xDevs/memory/`:
- `project_smart-chessboard-workflow-deviation.md` — why selector/bootstrapper skipped
- `project_smart-chessboard-firmware-parked.md` — firmware done & paused
- `reference_smart-chessboard-lessons-md.md` — pointer to lessons.md
- `feedback_subproject-skill-locations.md` / `feedback_android-cli-skills-install.md` — skill install rules (incl. `npx skills --agent <token>` clean per-profile install)
- All indexed in `MEMORY.md`, always loaded.

## Decisions this session (L4)

- **Agent docs across tools.** `AGENTS.md` is the source of truth in each scope
  (root + each module); `CLAUDE.md` is a thin `@AGENTS.md` import. Single edit
  point keeps `.claude/`, `.agents/`, `.kiro/`, `.codex/` consistent.
- **Root vs module canonicality.** Root `AGENTS.md` keeps only cross-cutting
  headlines + one-line pointers to module files; canonical rules live in the
  module's `AGENTS.md` (e.g., KMP `web-WasmJS-only` and BLE placement live in
  `SmartChessboard/AGENTS.md`, root just points there).
- **No prose version pins.** Library version numbers belong only in
  `gradle/libs.versions.toml`; prose files must not restate them — they drift.
- **PlatformIO manages ESP-IDF.** The `pio` flow downloads its own toolchain —
  do **not** set `IDF_PATH` manually (the earlier `IDF_PATH` note in root
  `AGENTS.md` was wrong and is now fixed).
- **`10xDevsLekcje/` un-ignored.** Course material is now visible to agents
  (still kept untracked by convention — never committed) so sessions can read
  lesson notes directly when planning.

## Next step — L5 infra/deploy

1. **Confirm L5 scope** from `10xDevsLekcje/Module1/` (now visible, not
   gitignored). The `infra/deploy` label in this handoff is a placeholder — the
   lesson notes are the source of truth for what the next assignment actually
   asks for.
2. **Candidate L5 content** per `tech-stack.md`: GitHub Actions for the three
   sub-projects (mobile build + KMP host/iOS/wasm tests, firmware build,
   supabase config validation); mobile distribution path (internal track vs
   PWA); Supabase environments (when the hosted project gets created in
   Module 2).
3. **Deferred from L4 (don't delay L5)**:
   - **DI library** — Koin KMP vs hand-rolled service locator.
   - **MVVM vs MVI** — 2–3 screen spike when feature work begins.
   - **`/10x-lesson`** — log new recurring rules into `lessons.md` as they
     surface, not only at lesson boundaries.

## Open decisions deferred

Full list in `tech-stack.md` § "Open / Deferred decisions": ESP32 variant (FW-1),
firmware power (FW-2), reed-matrix wiring topology (FW-3), GATT UUIDs (FW-5),
mobile distribution path, Compose-Web viability, MVVM vs MVI, DI library, Maestro E2E.

## How to resume

Auto-memory loads the deviation + firmware-parked summaries automatically. For
full context:
1. Read this file (esp. **Decisions this session** for the *why* behind
   agent-doc shape and the `IDF_PATH` / version-pin rules).
2. Skim `context/foundation/lessons.md` and `docs/bootstrap-verification.md`.
3. Browse `10xDevsLekcje/Module1/` to confirm what L5 actually wants (the
   `infra/deploy` label is a placeholder).
4. Start L5: plan CI/CD + deployment per `tech-stack.md` §CI/CD. Agent-doc
   shape is settled — don't re-litigate `AGENTS.md` structure; if you find a
   real issue, `/10x-rule-review` is the tool.
