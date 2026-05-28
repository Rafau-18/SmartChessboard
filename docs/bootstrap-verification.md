---
project: "Smart Chessboard"
document: bootstrap-verification
version: 1
status: draft
created: 2026-05-28
updated: 2026-05-28
---

## Purpose

Records the bootstrap of each Smart Chessboard sub-project performed by hand,
because `/10x-tech-stack-selector` and `/10x-bootstrapper` were intentionally
skipped (see `context/foundation/tech-stack.md` § "Why … were skipped").

For each sub-project this document captures: the wizard or tool used, exact
commands run with their exit codes, environment assumptions, and any audit
findings worth remembering. This is a **verification log**, not a runbook —
re-runnable instructions live in each sub-project's own README.

> **Path note** — `tech-stack.md` referenced `context/changes/bootstrap-verification/verification.md`
> as the intended location. This draft lives at `docs/bootstrap-verification.md`
> at the user's request; reconcile before moving to `status: accepted`.

## Status by sub-project

| Sub-project | Status | Last check |
|---|---|---|
| Mobile (`SmartChessboard/`) | ✅ Verified | 2026-05-28 — `./gradlew tasks` exit 0 |
| Firmware (`firmware/`) | ⏭️ Pending | — |
| Backend (`supabase/`) | ⏭️ Pending | — |

---

## 1. Mobile (`SmartChessboard/`)

### 1.1 Scaffold source

- **Wizard**: [kmp.jetbrains.com](https://kmp.jetbrains.com/)
- **Output**: `SmartChessboard.zip` (229 KB, 60 files), generated 2026-05-28
- **Targets enabled in wizard**: Android, iOS, Web (Compose Multiplatform).
  The wizard's single "Web" option emits **both** a `wasmJs` and a `js`
  (Kotlin/JS) target; the `js` target was removed post-scaffold (see § 1.7).
- **Package**: `org.rurbaniak.smartchessboard`

The ZIP itself is not committed; the unpacked scaffold lives in
`claude/SmartChessboard/`.

### 1.2 Layout decision

Scaffold placed in `claude/SmartChessboard/` (subdirectory) rather than
merged into `claude/` root. Reasoning:

- `claude/` is a docs repo (`AGENTS.md`, `CLAUDE.md`, `context/`, `docs/`).
  A root-level merge would clash on `.gitignore`, `.idea/`, and `README.md`
  and muddy the separation between docs and code.
- Firmware will land alongside as `claude/firmware/`; backend as
  `claude/supabase/`. Three sibling sub-projects under one documentation
  layer.

```
claude/
├── AGENTS.md, CLAUDE.md
├── context/, docs/
├── SmartChessboard/         ← KMP scaffold (this section)
├── firmware/                ← pending
└── supabase/                ← pending
```

### 1.3 Environment

| Tool | Version / location |
|---|---|
| JDK | OpenJDK 25.0.2 (Homebrew), on `PATH`; `JAVA_HOME` not set |
| Android SDK | `~/Library/Android/sdk` (Android Studio install) |
| `adb` | `/opt/homebrew/bin/adb` |
| Gradle wrapper | pinned to 9.1.0 (`gradle/wrapper/gradle-wrapper.properties`) |
| AGP | 9.0.1 (set by KMP wizard) |
| `local.properties` | `sdk.dir=` empty as shipped; `.gitignore`'d, filled per machine |

For ad-hoc CLI runs, `ANDROID_HOME` is passed inline rather than persisted
to `local.properties`:

```bash
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew <task>
```

### 1.4 Verification: `./gradlew tasks`

Command (2026-05-28):

```bash
cd claude/SmartChessboard
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew tasks --console=plain --no-daemon
```

Result:

- **Exit code**: 0
- **Duration**: ~6 s — distribution and KMP dependencies already in
  `~/.gradle/caches/` from prior Android Studio sync
- **`Configuration cache entry stored`** — config cache enabled, subsequent
  runs benefit

All three KMP targets confirmed present in the task listing:

| Target | Representative tasks observed |
|---|---|
| Android | `signingReport`, `lintDebug`, `testDebugUnitTest`, `connectedAndroidTest` |
| iOS | `iosSimulatorArm64Test`, `checkXcodeProjectConfiguration`, `convertPbxprojToJson` |
| Web — Wasm | `wasmJsBrowserDevelopmentRun`, `wasmJsTest`, `kotlinWasmBinaryenSetup` |
| Web — JS | `jsBrowserDevelopmentRun`, `jsTest`, `kotlinNodeJsSetup` *(removed in § 1.7)* |
| Shared | `testAndroidHostTest`, `allTests`, `checkKotlinAbi` |

#### Tests (smoke)

The wizard ships one trivial smoke test (`assertEquals(3, 1 + 2)`) per test
source-set: `commonTest`, `androidHostTest`, `iosTest`. `commonTest` runs on
every target. All ran green (2026-05-28):

| Command | Classes × tests | Result |
|---|---|---|
| `./gradlew testAndroidHostTest` | `SharedCommonTest` + `SharedLogicAndroidHostTest` (2×1) | ✅ exit 0, 9 s |
| `./gradlew iosSimulatorArm64Test` | `SharedCommonTest` + `SharedLogicIOSTest` (2×1) | ✅ exit 0 |
| `./gradlew wasmJsTest` | `SharedCommonTest` (1) — ran headless via `wasmJsBrowserTest` | ✅ exit 0 |

`jsTest` was intentionally skipped (JS target later removed, § 1.7). iOS run
used the iOS Simulator (Apple Silicon, `iosSimulatorArm64`) after the user
installed it in Xcode. These placeholder tests are to be deleted once the
real chess engine lands; they only prove the test pipeline is wired per
target.

> **Warning observed (non-blocking)**: `This version only understands SDK XML
> versions up to 3 but an SDK XML file of version 4 was encountered` — a skew
> between `cmdline-tools` and the newer Android Studio. Builds and tests pass
> regardless. Fix via Android Studio → SDK Manager → SDK Tools → update
> command-line tools, if desired.

### 1.5 IDE compatibility findings

#### Android Studio — works

Android and Wasm apps both launch from Android Studio (user-confirmed
2026-05-28). iOS run is blocked only on installing the iOS Simulator inside
Xcode (deferred to the user).

#### IntelliJ IDEA — AGP version mismatch

Importing the project in IntelliJ IDEA fails with:

> The project is using an incompatible version (AGP 9.0.1) of the Android
> Gradle plugin. Latest supported version is AGP 9.0.0-alpha06.

Root cause: IntelliJ ships its Android plugin as part of the IDE
distribution; it cannot be updated independently. As of May 2026 the
**highest AGP supported in any IntelliJ build (including 2026.1 EAP) is
AGP 9.0.0-alpha06**. Android Studio Otter 3 Feature Drop 2025.2.3+ already
supports AGP 9.0.0 stable; 9.0.1 is one step further. IntelliJ traditionally
catches up to a new AGP one to two quarters after Android Studio.

**Resolution: split per-IDE responsibility (JetBrains' own KMP recommendation).**

| Module | Edit in |
|---|---|
| `androidApp/` | Android Studio (AGP-aware) |
| `shared/`, `webApp/` | IntelliJ IDEA |
| `iosApp/` | Xcode |

Do not load the `androidApp` module into IntelliJ IDEA. There is no
production-grade fix until IntelliJ catches up to AGP 9.0.1.

References consulted:

- [JetBrains: Update your Kotlin projects for AGP 9.0 (January 2026)](https://blog.jetbrains.com/kotlin/2026/01/update-your-projects-for-agp9/)
- [KMP docs: Updating multiplatform projects to AGP 9](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html)
- [YouTrack IDEA-385007: AGP 9 compatibility](https://youtrack.jetbrains.com/issue/IDEA-385007/AGP-9-compatibility)
- [Community AGP ↔ IntelliJ compatibility matrix](https://github.com/rafaeltonholo/agp-intellij-compatibility-matrix)

### 1.6 Android skills installation

Five Android-team skills installed via `android skills add <name> --project=<profile>`:
`adaptive`, `android-cli`, `edge-to-edge`, `navigation-3`, `testing-setup`.

Per-profile canonical install location:

| Profile | Canonical path | Committed |
|---|---|---|
| `claude/` | `.claude/skills/` | ✅ `728573d` |
| `codex/` | `.agents/skills/` | ✅ `5c01794` |
| `antigravity/` | `.agents/skills/` | ✅ `084f99e` |
| `kiro-experiments/` | `.kiro/skills/` | ✅ `906fc94` |

**Audit finding**: `android skills add --project=<dir>` copies the skill into
**every** agent directory it detects under `<dir>` (`.claude/`, `.agents/`,
`.kiro/`, plus a default `skills/` at the project root). For three of four
profiles this created a rogue duplicate `<profile>/skills/` alongside the
canonical location. Rogue copies were removed manually before commit;
canonical locations were committed.

Future installs: after `android skills add`, verify both the canonical agent
directory **and** the rogue `<profile>/skills/` and delete the latter.

### 1.7 Web target trimmed to WasmJS-only

The wizard emits both a `wasmJs` and a `js` (Kotlin/JS) web target — its
single "Web" checkbox has no wasm-only option. For this project `js` is dead
weight: `tech-stack.md` already scopes web to WasmJS, web is pass-and-play /
replay / analysis for a small modern-browser audience, and Compose
Multiplatform UI runs primarily on `wasmJs`. The `js` target (universal
old-browser compatibility, slower runtime) buys nothing here, so it was
removed to cut a web compilation, a browser test, and a dependency.

Removed (2026-05-28):

| File | Change |
|---|---|
| `shared/build.gradle.kts` | dropped `js { browser() }` target + `jsMain.dependencies` block |
| `webApp/build.gradle.kts` | dropped `js { browser(); binaries.executable() }` target |
| `gradle/libs.versions.toml` | dropped `wrappers-browser` library + `kotlin-wrappers` version (JS-only) |
| `shared/src/jsMain/` | deleted (`Platform.js.kt` — `actual` not needed without a `js` target) |

`webApp/src/webMain/` is retained — it is the shared parent source-set for
the web target(s) and now serves `wasmJs` alone.

Re-verification after removal:

| Command | Result |
|---|---|
| `./gradlew tasks wasmJsTest` | ✅ BUILD SUCCESSFUL, 30 s |
| Plain-`js` tasks (`jsBrowserDevelopmentRun`, `jsTest`, …) | gone — 0 in task listing |
| `wasmJs*` tasks | present (`wasmJsBrowserDevelopmentRun`, `wasmJsBrowserProductionRun`, `wasmJsTest`) |
| `wasmJsTest` (`SharedCommonTest`) | ✅ 1 test, 0 fail |

---

## 2. Firmware (`firmware/`)

⏭️ Not yet bootstrapped. Planned per `tech-stack.md`:

```bash
pio project init --board <variant> --project-option "framework=espidf"
```

Blocked on Open Question FW-1 (ESP32 variant) in `tech-stack.md`.

---

## 3. Backend (`supabase/`)

⏭️ Not yet bootstrapped. Planned per `tech-stack.md`:

- `supabase init` (creates local project skeleton + migrations directory)
- `supabase start` (Docker stack: Postgres + GoTrue + Studio)
- Cloud project created in EU Frankfurt (`eu-central-1`), Free tier

---

## References

- `context/foundation/tech-stack.md` — sub-project decomposition and stack choices
- `context/foundation/prd.md` — product PRD
- `context/foundation/prd-firmware.md` — firmware PRD
- `docs/reference/contract-surfaces.md` — cross-sub-project contracts
