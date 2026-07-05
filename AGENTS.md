# AGENTS.md

Guidance for AI coding agents working in this repository. This file is the source of truth; CLAUDE.md is a thin import of it. Product overview + feature list: [`README.md`](README.md).

## What this repo is

Smart Chessboard — a docs-and-context layer over three sibling sub-projects:

- `SmartChessboard/` — Kotlin Multiplatform app (Android + iOS + WasmJS). Package `org.rurbaniak.smartchessboard`. Setup/build/run/test: [`SmartChessboard/README.md`](SmartChessboard/README.md); module rules: [`SmartChessboard/AGENTS.md`](SmartChessboard/AGENTS.md).
- `firmware/` — ESP32 reed-matrix firmware (C++, PlatformIO + ESP-IDF). Software implemented & on-hardware-verified (F-03); board repaired 2026-06-28. **Don't resume unless the task asks for firmware work.** Build/flash/test: [`firmware/README.md`](firmware/README.md); rules & pin-map gotcha: [`firmware/AGENTS.md`](firmware/AGENTS.md).
- `supabase/` — Supabase project defined as code: `config.toml` + `migrations/*.sql` + one Edge Function `lichess-eval`. **Supabase Cloud is the backend** — this dir is *not* a separate server. Overview & commands: [`supabase/README.md`](supabase/README.md); rules: [`supabase/AGENTS.md`](supabase/AGENTS.md).

Canonical context: `context/foundation/` (`prd.md`, `prd-firmware.md`, `tech-stack.md`) and `docs/` (`bootstrap-verification.md`, `reference/contract-surfaces.md`). Recurring rules live in `context/foundation/lessons.md` — consult it before research, planning, or implementation.

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
- **Skills are mirrored** across `.claude/`, `.agents/`, `.kiro/` (multi-tool repo). When adding a skill, install it into the matching dir per tool — don't leave it in only one.
- Local files agents won't see (all gitignored): `SmartChessboard/local.properties`, `firmware/sdkconfig`, `supabase/.env.local`.

