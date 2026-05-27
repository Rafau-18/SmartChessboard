# Smart Chessboard — Session Handoff (2026-05-28)

## Status

PRD chain + `tech-stack.md` complete. M1L2 (`/10x-tech-stack-selector`) and
M1L3 (`/10x-bootstrapper`) were skipped intentionally — the KMP + ESP32 +
Supabase stack has no entry in the starter registry. See
`context/foundation/lessons.md` lesson #1 for the full rationale.

## Canonical files (all under `claude/`)

- `context/foundation/prd.md` — mobile + system PRD (v1, updated 2026-05-27)
- `context/foundation/prd-firmware.md` — ESP32 firmware PRD
- `context/foundation/tech-stack.md` — hand-written, extends bootstrapper
  schema with Architecture Overview, per-sub-project tables, CI/CD, and
  Open / Deferred decisions
- `context/foundation/lessons.md` — recurring rules (M1L4 `/10x-lesson` format)
  - Lesson 1: skip selector + bootstrapper for this stack
  - Lesson 2: web target is digital-only (no BLE on web)
- `docs/reference/contract-surfaces.md` — BLE GATT protocol, REST shapes,
  RLS scoping (contract between mobile / firmware / backend)
- `context/archive/prd-pre-firmware-split.md` — pre-split PRD for history

## Persistence layers (already active)

- Auto-memory in `~/.claude/projects/-Users-rurbaniak-Projects-Private-10xDevs/memory/`:
  - `project_smart-chessboard-workflow-deviation.md` (project memory)
  - `reference_smart-chessboard-lessons-md.md` (reference pointer)
  - Both indexed in `MEMORY.md` and always-loaded in new sessions
- `lessons.md` ready for `/10x-lesson` appends once M1L4 runs

## Next step — manual bootstrap of three sub-projects (monorepo)

1. **Mobile (`composeApp/`)**
   - KMP wizard at <https://kmp.jetbrains.com>
   - Targets: Android + iOS + WasmJS
   - Project name: `smart-chessboard`
   - Download .zip, unpack into repo root → produces `composeApp/` + `iosApp/`

2. **Firmware (`firmware/`)**
   - `pio project init -d firmware --board <TBD> --project-option "framework=espidf"`
   - ESP32 variant TBD until physical prototype is checked (FW-1)
   - Will use NimBLE (IDF 5.x default)

3. **Backend (`supabase/`)**
   - `supabase init`
   - `supabase start` to verify Docker stack runs locally
   - Region (Frankfurt) configured later via CLI / dashboard when a remote
     project is created

After scaffolding completes, write
`context/changes/bootstrap-verification/verification.md` by hand:
three sections (mobile / firmware / backend), each with command run,
exit code, audit findings.

## Open decisions deliberately deferred

Full list in `context/foundation/tech-stack.md` § "Open / Deferred decisions":

1. ESP32 variant (FW-1)
2. Power source for firmware (FW-2)
3. Reed-switch matrix wiring topology (FW-3)
4. GATT service / characteristic UUIDs (FW-5)
5. Mobile distribution path (TestFlight + Play Internal vs APK sideload)
6. Compose Multiplatform Web viability (freeze if blocking)
7. UI architecture pattern — MVVM vs MVI (spike 2–3 screens first)
8. DI library — Koin KMP vs hand-rolled service locator (decide in M1L4)
9. Maestro E2E — post-MVP unless a later course lesson requires it earlier

## Course context

Module 1 progress:

- **L1** PRD ✓
- **L2** tech-stack ✓ (manual)
- **L3** bootstrap → **NEXT** (manual path)
- **L4** agent onboarding — CLAUDE.md per sub-project, `/10x-rule-review`,
  `/10x-lesson` to start appending to `lessons.md`
- **L5** infra/deploy — `/10x-infra-research` (re-profiled for Supabase +
  Cloudflare Pages + mobile distribution), Plan Mode

## How to resume in a fresh session

Auto-memory loads the workflow-deviation summary automatically (no action
needed). For full context in the new session:

1. Read `claude/context/foundation/lessons.md` (≤ 2 minutes)
2. Skim `claude/context/foundation/tech-stack.md` — frontmatter +
   extension table sections
3. Pick one sub-project to bootstrap. They are independent — any order or
   in parallel works.

Stack and per-sub-project library choices are settled. The first session
question should be "which sub-project starts?", not "what tech stack?".
