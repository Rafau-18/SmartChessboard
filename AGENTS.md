# AGENTS.md

Guidance for AI coding agents working in this repository. This file is the source of truth; CLAUDE.md is a thin import of it. Product overview + feature list: [`README.md`](README.md).

## What this repo is

Smart Chessboard — a docs-and-context layer over three sibling sub-projects:

- `SmartChessboard/` — Kotlin Multiplatform app (Android + iOS + WasmJS). Package `org.rurbaniak.smartchessboard`. Setup/build/run/test: [`SmartChessboard/README.md`](SmartChessboard/README.md); module rules: [`SmartChessboard/AGENTS.md`](SmartChessboard/AGENTS.md).
- `firmware/` — ESP32 reed-matrix firmware (C++, PlatformIO + ESP-IDF). Software implemented & on-hardware-verified (F-03); board repaired 2026-06-28. **Don't resume unless the task asks for firmware work.** Build/flash/test: [`firmware/README.md`](firmware/README.md); rules & pin-map gotcha: [`firmware/AGENTS.md`](firmware/AGENTS.md).
- `supabase/` — Supabase project defined as code: `config.toml` + `migrations/*.sql` + one Edge Function `lichess-eval`. **Supabase Cloud is the backend** — this dir is *not* a separate server. Overview & commands: [`supabase/README.md`](supabase/README.md); rules: [`supabase/AGENTS.md`](supabase/AGENTS.md).

## Canonical context (read in this order)

Cross-change ground truth lives in `context/foundation/` and `docs/`. Ordered most load-bearing first — read the top two before any research, planning, or implementation:

1. [`docs/reference/contract-surfaces.md`](docs/reference/contract-surfaces.md) — interface contract between firmware, mobile, and backend (BLE §1, Supabase schema/RLS/API §2–4, PGN/FEN data model §5, cross-cutting failure modes §6). **Edit the contract first, then mirror code.** Doubles as the backend spec (there is no separate `prd-backend.md`).
2. [`context/foundation/lessons.md`](context/foundation/lessons.md) — recurring rules and pitfalls learned on this project; append-only. Consult before you change anything.
3. [`context/foundation/prd.md`](context/foundation/prd.md) — mobile + system product requirements (FR registry, guardrails, dated implementation decisions).
4. [`context/foundation/prd-firmware.md`](context/foundation/prd-firmware.md) — firmware-only requirements.
5. [`context/foundation/tech-stack.md`](context/foundation/tech-stack.md) — stack rationale + the Clean Architecture overview the mobile app follows.
6. [`context/foundation/roadmap.md`](context/foundation/roadmap.md) — vertical slices and their status (MVP complete; BLE hardening S-10 outstanding).
7. [`context/foundation/test-plan.md`](context/foundation/test-plan.md) — risk map, test layers, and quality gates.
8. [`context/foundation/infrastructure.md`](context/foundation/infrastructure.md) — MVP deploy platform decision (Cloudflare Workers static assets).

Occasional / historical: [`context/foundation/shape-notes.md`](context/foundation/shape-notes.md) (pre-PRD discovery), [`docs/bootstrap-verification.md`](docs/bootstrap-verification.md) (scaffolding log), [`docs/reference/change-md.md`](docs/reference/change-md.md) (change-folder schema).

## Build & test

Commands live in each sub-project's README (linked above). The two mobile gotchas that bite every session:

- The Gradle wrapper needs the Android SDK path, but `local.properties` is gitignored — pass it inline:

  ```bash
  cd SmartChessboard
  ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew <task> --console=plain --no-daemon
  ```

- Plain `test` does not cover the KMP targets — use the per-target tasks: `:shared:testAndroidHostTest` (JVM / Android host), `:shared:iosSimulatorArm64Test` (iOS simulator, Apple Silicon), `:shared:wasmJsTest` (web, headless browser).

CI (`.github/workflows/`) runs the same commands: `tests.yml` gates PRs/`main` (JVM + wasm suites); `build.yml` is a manual-dispatch workflow that additionally builds firmware (`pio run -e esp32dev` + `pio test -e native`) and runs the Supabase suites (`deno test`, `supabase test db`) — the one place all three sub-projects are exercised together. Per-workflow detail: [`SmartChessboard/AGENTS.md`](SmartChessboard/AGENTS.md).

## Kotlin formatting

ktlint is the formatter; rules live in `SmartChessboard/.editorconfig`. Format manually with `ktlint -F` from `SmartChessboard/`. (In Claude Code a `PostToolUse` hook also auto-formats `*.kt`/`*.kts` on edit.)

## Conventions & gotchas

- **Commits**: Conventional Commits with a scope, e.g. `feat(firmware): …`, `docs(bootstrap-verification): …`.
- **KMP module rules** (web is WasmJS-only; BLE / physical-board code only in `androidMain` + `iosMain`) are canonical in [`SmartChessboard/AGENTS.md`](SmartChessboard/AGENTS.md).
- **Skills**: the only skill tracked in this repo is `handoff` (`.claude/skills/handoff/`, project-specific). Workflow and vendor skills live at the user level (`~/.claude/skills`) so every session and worktree sees them; `.agents/` and `.kiro/` are local-only tool mirrors (untracked). Install new shared skills at user level, not into the repo.
- Local files agents won't see (all gitignored): `SmartChessboard/local.properties`, `firmware/sdkconfig`, `supabase/.env.local`.
