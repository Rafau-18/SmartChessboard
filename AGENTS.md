# AGENTS.md

Guidance for AI coding agents working in this repository. This file is the source of truth; CLAUDE.md is a thin import of it.

## What this repo is

Smart Chessboard — a docs-and-context layer over three sibling sub-projects:

- `SmartChessboard/` — Kotlin Multiplatform app (Android + iOS + WasmJS). Package `org.rurbaniak.smartchessboard`.
- `firmware/` — ESP32 reed-matrix firmware (C++, PlatformIO + ESP-IDF). **Parked** — don't resume unless asked.
- `supabase/` — Supabase project defined as code: `config.toml` + (incoming) `migrations/*.sql` + one Edge Function `lichess-eval`. **Supabase Cloud is the backend** — this dir is *not* a separate server. Canon & conventions: [`supabase/AGENTS.md`](supabase/AGENTS.md).

Canonical context: `context/foundation/` (`prd.md`, `prd-firmware.md`, `tech-stack.md`) and `docs/` (`bootstrap-verification.md`, `reference/contract-surfaces.md`). Recurring rules live in `context/foundation/lessons.md` — consult it before research, planning, or implementation.

## Build & test

**Mobile** (`SmartChessboard/`) — the Gradle wrapper needs the Android SDK path, but `local.properties` is gitignored, so pass it inline:

```bash
cd SmartChessboard
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew <task> --console=plain --no-daemon
```

Use the per-target test tasks (plain `test` does not cover the KMP targets):
- `testAndroidHostTest` — JVM / Android host
- `iosSimulatorArm64Test` — iOS simulator (Apple Silicon)
- `wasmJsTest` — web (headless browser)

**Firmware** (`firmware/`) — build `pio run`, flash `pio run -t upload`. PlatformIO manages the ESP-IDF toolchain, so no manual `IDF_PATH`. Full build/flash/pin-map detail: [`firmware/AGENTS.md`](firmware/AGENTS.md).

## Kotlin formatting

ktlint is the formatter; rules live in `SmartChessboard/.editorconfig`. Format manually with `ktlint -F` from `SmartChessboard/`. (In Claude Code a `PostToolUse` hook also auto-formats `*.kt`/`*.kts` on edit.)

## Conventions & gotchas

- **Commits**: Conventional Commits with a scope, e.g. `feat(firmware): …`, `docs(bootstrap-verification): …`.
- **KMP module rules** (web is WasmJS-only; BLE / physical-board code only in `androidMain` + `iosMain`) are canonical in [`SmartChessboard/AGENTS.md`](SmartChessboard/AGENTS.md).
- **Skills are mirrored** across `.claude/`, `.agents/`, `.kiro/` (multi-tool repo). When adding a skill, install it into the matching dir per tool — don't leave it in only one.
- Local files agents won't see (all gitignored): `SmartChessboard/local.properties`, `firmware/sdkconfig`, `supabase/.env.local`.


