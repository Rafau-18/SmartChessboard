# Lessons Learned

Recurring rules and pitfalls for the Smart Chessboard project. New lessons are appended at the end of this file. Format follows the convention introduced by `/10x-lesson` (M1L4): one heading per lesson, four bullets (Context / Problem / Rule / Applies to).

This file is not sorted, deduplicated, or reorganized when new entries land — it grows append-only so the chronology of discoveries is preserved.

## Skip /10x-tech-stack-selector and /10x-bootstrapper for the KMP + ESP32 + Supabase stack

- **Context**: Smart Chessboard greenfield project. Stack = Kotlin Multiplatform + Compose Multiplatform (Android / iOS / WasmJS) + ESP32 firmware in C++ via PlatformIO + ESP-IDF + Supabase as managed backend (no bespoke server). The decision was settled 2026-05-27 alongside `context/foundation/tech-stack.md`.

- **Problem**: The `/10x-tech-stack-selector` starter registry (`.agents/skills/10x-tech-stack-selector/references/starter-registry.yaml`) has no entry for this stack combination — mobile starters cover only Expo (React Native, JS) and Flutter (Dart). Running the selector force-fits to one of them. `/10x-bootstrapper` has a registry-sync validator (`scripts/validate-starter-registry-sync.mjs`) that refuses any `starter_id` not present in the registry. A new session's AI agent may attempt these skills without realizing they will fail or produce wrong scaffolding.

- **Rule**: For the bootstrap phase of this project, **do not invoke `/10x-tech-stack-selector` or `/10x-bootstrapper`**. `tech-stack.md` was hand-written at `context/foundation/tech-stack.md` with frontmatter compliant to `handoff-schema.md` plus extension sections. Bootstrap each sub-project manually via its official wizard: [kmp.jetbrains.com](https://kmp.jetbrains.com/) for `composeApp/`, `pio project init --project-option "framework=espidf"` for `firmware/`, `supabase init` for `supabase/`. Write `context/changes/bootstrap-verification/verification.md` by hand after scaffolding.

- **Applies to**: M1L2 (`/10x-tech-stack-selector`), M1L3 (`/10x-bootstrapper`). Other 10x-* skills (research, frame, plan, implement, impl-review from Module 2 onwards) work normally because they consume PRD + `tech-stack.md` as plain files and do not depend on the registry.

## Web target is digital-only — no BLE, no physical-board flow on web

- **Context**: Smart Chessboard mobile app has Android + iOS + WasmJS targets enabled in the KMP wizard from day 1 (`composeApp/`). FR-020 in `context/foundation/prd.md` is nice-to-have for web.

- **Problem**: Web Bluetooth has inconsistent cross-browser support — Chromium-only on desktop, no Safari on iOS, mobile browsers limited. Attempting to share the BLE board adapter between mobile and web targets would force expect/actual or extra abstractions for a feature the MVP does not need on web.

- **Rule**: Web target supports only the **digital subset**: pass-and-play (FR-003, FR-004), game history list (FR-015), replay (FR-016), post-game analysis (FR-017), and end-of-game marking (FR-018). It explicitly excludes physical-board mode (FR-008–FR-013), BLE transport, and reed-switch diagnostics (FR-011). The BLE library (Kable or expect/actual) is added only to `androidMain` + `iosMain`, never to `wasmJsMain`. This is documented in `prd.md` FR-020, Non-Goals, Implementation Decisions, and `tech-stack.md` mobile sub-project table.

- **Applies to**: any work on the mobile sub-project that touches BLE, physical-board mode, or reed-switch diagnostics. Also applies to architecture decisions (DI, Navigation routes) — web target should never have a route to "Physical game" screens.
