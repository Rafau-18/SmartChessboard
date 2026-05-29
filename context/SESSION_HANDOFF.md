# Smart Chessboard — Session Handoff (updated 2026-05-28)

## Status

Module 1 lessons **L1–L3 complete**. Next session continues at **L4 — Agent
Onboarding**. The KMP + ESP32 + Supabase stack has no starter-registry entry, so
`/10x-tech-stack-selector` and `/10x-bootstrapper` were intentionally skipped;
all three sub-projects were bootstrapped manually via official tooling and
verified. See `context/foundation/lessons.md` lesson #1 for the rationale.

## Module 1 progress

- **L1** PRD ✓
- **L2** tech-stack ✓ (hand-written, no selector)
- **L3** bootstrap ✓ — **DONE this session** (manual path): three sub-project
  scaffolds + verification + permission policy. All three gates satisfied.
- **L4** agent onboarding → **NEXT**
- **L5** infra/deploy — later

## Monorepo layout (actual, as built)

Three sub-projects live as siblings **under `claude/`** (subdir layout chosen
over root-merge to keep the docs repo and code separate):

```
claude/
├── AGENTS.md, CLAUDE.md           # CLAUDE.md is still the M1L1 lesson text — replace in L4
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

## Next step — L4 Agent Onboarding

1. **`/10x-agents-md`** — generate a real `AGENTS.md` (the current `claude/CLAUDE.md`
   is still the M1L1 lesson text, not project onboarding). Consider per-sub-project
   rules given the three very different stacks (KMP / ESP-IDF / Supabase).
2. **`/10x-rule-review`** — score the rules file(s).
3. **`/10x-lesson`** — start appending recurring rules to `lessons.md` as they surface.
4. Decisions tagged for L4: **DI library** (Koin KMP vs hand-rolled service locator);
   keep **MVVM vs MVI** as a 2–3 screen spike for feature work.

## Open decisions deferred

Full list in `tech-stack.md` § "Open / Deferred decisions": ESP32 variant (FW-1),
firmware power (FW-2), reed-matrix wiring topology (FW-3), GATT UUIDs (FW-5),
mobile distribution path, Compose-Web viability, MVVM vs MVI, DI library, Maestro E2E.

## How to resume

Auto-memory loads the deviation + firmware-parked summaries automatically. For
full context:
1. Read this file.
2. Skim `context/foundation/lessons.md` and `docs/bootstrap-verification.md`.
3. Start L4: run `/10x-agents-md`. Stack and bootstrap are settled — the first
   question is "what does the agent need to know to work safely in each
   sub-project?", not "what stack?" or "is it scaffolded?".
