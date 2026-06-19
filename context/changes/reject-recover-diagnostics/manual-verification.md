# Reject, Recover & Diagnostics (S-07) — Manual Verification Checklist

Deferred manual checks for the whole slice, to run **at the end of S-07 before archiving**. The
automated success criteria are gated per phase in `plan.md` → `## Progress`; this file collects the
*human* checks (code reviews, on-device walkthroughs) those automated gates cannot cover. Phases
append their manual items here as they land, so the final pass is a single run-through.

Convention: `- [ ]` pending, `- [x]` done. Each item mirrors a `#### Manual` row in `plan.md`.

---

## Phase 1 — Headless MVI core (pure reducer)

Headless logic, no UI/device — the one manual check is a **code read**, not an app walkthrough.

### 1.5 — Reducer remains pure / IO-free

Open `presentation/physical/PhysicalPlayReducer.kt` and confirm `reduce` (and its helpers, incl. the
new `effectsForModeChange`) call **no** repository / journal / board-connection / coroutine APIs —
only the pure engine functions:

- [ ] No `gamesRepository`, `autoSaver`, `journal`, or `boardConnection` reference anywhere in the reducer.
- [ ] The only chess/board calls are pure: `resolvePhysicalMove`, `status`, `gameResultFor`, `toOccupancy`.
- [ ] The new S-07 transitions emit only `PhysicalEffect`s (`Send(SetMode / RequestSnapshot)`); the
      diagnostic-mode IO is reached **only** via those effects the `PhysicalPlayViewModel` interprets.

**Code-read performed 2026-06-19 (passed; formal tick deferred to the end-of-slice pass):** the
reducer's imports are `domain.board.*` (pure), `domain.chess.*` (pure), `domain.games.gameResultFor`
(pure), and `presentation.play.{EndGamePrompt, PendingPromotion}` — no `data/`, repository, journal,
`BoardConnection`, or `kotlinx.coroutines` import. The new diagnostics / restore transitions add only
`BoardMode` (a pure enum) and the `effectsForModeChange` helper, which returns `PhysicalEffect`s and
touches no IO. The §6.2 gate stays effect-only (`CommitMove` / `FinishGame`).

**Adaptations to note during review (not defects):**
1. **Compile-criterion task name.** `plan.md` 1.1 names `:shared:compileKotlinAndroid` (pre-AGP-9
   naming, absent in this build). The actual task is `:shared:compileAndroidMain`; verified green
   alongside `:shared:testAndroidHostTest`. (Title left verbatim per the no-rename Progress rule.)
2. **`INCONSISTENT` forced a minimal Phase-2 file touch.** Adding `RejectionReason.INCONSISTENT` (a
   Phase-1 contract change) breaks the exhaustive `when` in `PhysicalPlayScreen.rejectionText()`
   (commonMain), so a one-line stub arm was added in Phase 1 to keep the build green. Phase 2 refines
   the copy and adds the "Show diagnostics" CTA. (User-approved adaptation.)

---

## Phase 2 — Diagnostics UI + screen wiring

UI + wiring landed (`ReedDiagnosticsGrid`, the banner "Show diagnostics" CTA + refined `INCONSISTENT`
copy, the grid under the board, the `showDiagnostics()` / `hideDiagnostics()` intents). Automated
2.1–2.4 green. The three `#### Manual` rows are interactive / visual and stay `- [ ]` in `plan.md` for
the end-of-slice device pass; their non-device parts are discharged below.

### 2.5 — Illegal sequence pauses, banner shows reason + "Show diagnostics", tap renders the grid

- [ ] On Android: an illegal confirm pauses the game (a second confirm is a no-op), the error banner
      shows the reason and a "Show diagnostics" button, and tapping it renders the live reed grid.

Interactive device check — **deferred to the end-of-slice pass** (Phase 3). Headless backing already
proven: the reducer sets `recovering` + `rejection` on an illegal confirm and blocks acceptance
(`PhysicalPlayReducerTest`, Phase 1), and `ShowDiagnostics` flips `manualDiagnostics` + emits
`SetMode(DIAGNOSTIC)`. Phase 3's `PhysicalRecoverEndToEndTest` drives the full loop against the emulator.

### 2.6 — Grid highlights exactly the squares that differ (incl. the h8 corner)

- [ ] On Android: the grid tints exactly the squares whose occupancy differs from the on-screen
      position, including an h8-corner mismatch.

Bit-math **proven headless now** by `presentation/board/ReedDiagnosticsGridTest.kt`: `isOccupied` and
`occupancyDiffers` read a1 (bit 0) and h8 (bit 63, the sign bit) correctly — a signed `> 0` regression
would misread exactly h8. The on-device *visual* confirmation is deferred to the end-of-slice pass.

### 2.7 — Web build still excludes the physical / diagnostics route

- [ ] In a browser: no physical-game / diagnostics route is reachable on the web (wasm) target.

Code-read **discharged now** (the web-is-digital-only lesson): Phase 2 added the grid only in
`commonMain` and touched **no** routing, DI, or capability gate — `PlatformCapabilities.wasmJs.kt`
keeps `supportsPhysicalBoard = false`, `App.kt` still routes a physical game to Replay on web, and
there is no wasm DI binding for the physical screen. `:webApp:compileKotlinWasmJs` is green, so the
shared composable compiles for wasm yet stays unreachable. The browser click-through is still run at
end-of-slice for full confidence.

**Phase-2 adaptation to note (not a defect):** `plan.md` 2.1 names `:shared:compileKotlinAndroid` (the
same pre-AGP-9 label as 1.1, absent in this build); Android compilation is covered by
`:androidApp:assembleDebug` + `:shared:testAndroidHostTest`, both green. Title left verbatim per the
no-rename Progress rule.

---

## Phase 3 — Emulator-driven end-to-end + manual verification

`PhysicalRecoverEndToEndTest` (new, `presentation/physical/`) drives the real `PhysicalPlayViewModel`
+ `EmulatedBoard` through reject → paused gate → restore → verified → retry → accept, for **both**
`ILLEGAL` and `INCONSISTENT`. Automated 3.1–3.3 green on all three targets. The test was hardened
after an adversarial multi-agent review (see the note below).

### 3.4 — Full reject→recover→retry loop verified by hand on Android for `ILLEGAL` and `INCONSISTENT`

- [ ] On a real Android device, with the emulator/board connected: make an illegal move → game pauses,
      banner + grid appear; restore the previous position guided by the grid → game un-pauses; make a
      legal move → it's accepted. Repeat with an inconsistent board (extra/missing piece) → `INCONSISTENT`.

Interactive device check — **the slice's one genuine human walkthrough.** The full state machine is
proven headless end-to-end by `PhysicalRecoverEndToEndTest` (both categories, on JVM + iOS + wasm);
this device pass confirms the on-screen UX (banner copy, CTA, live grid guiding restoration).

### 3.5 — No accepted move is ever saved from a rejected or unrestored board (journal inspected)

- [ ] Confirm (device + journal) that no move is persisted from a rejected/unrestored board.

**Discharged by the automated E2E now.** `PhysicalRecoverEndToEndTest` asserts the §6.2 invariant
directly on the journal: `acceptedMoveCount() == 0` after the reject, after a second in-recovery
confirm, and across the entire restore window, then exactly `== 1` only after the retried legal move —
for both categories. (`acceptMove`/`finishGame` are the only `dirty = true` journal writers; the
reconcile seed is `dirty = false`, so the count cleanly isolates accepted moves.) The device pass is a
belt-and-braces re-confirmation.

### 3.6 — `manual-verification.md` completed and checked in

- [ ] Tick every Manual row across Phases 1–3 here and in `plan.md` after the device pass, then
      `/10x-impl-review` + `/10x-archive`.

This file (you are reading it) is complete and committed with Phase 3. The remaining tick is the
human end-of-slice sign-off.

**Phase-3 adaptation to note (not a defect):** Kotlin/Native (iOS) **rejects backtick test-method
names containing `,` `(` `)`** — `compileTestKotlinIosSimulatorArm64` failed with *"Name contains
illegal characters"* on names the JVM/Android target had happily compiled (this surfaced both the new
`PhysicalRecoverEndToEndTest` names and the Phase-2 `ReedDiagnosticsGridTest` name, which `2.3` had only
exercised on Android). Renamed all four to comma/paren-free phrases; no behaviour change. This is a
sibling of the existing lesson *"a commonMain … is not green until it passes on a Native target"* —
worth a `/10x-lesson` capture.

---

## End-of-slice human pass — single consolidated checklist

All `#### Manual` rows below stay `- [ ]` in `plan.md` by design until this one pass. Most are already
discharged by code-read / automated tests (noted per row above); the only irreducibly-interactive item
is **3.4** (the on-device walkthrough). Run it on a real Android device with the board/emulator, plus a
browser for the web-exclusion spot-check (2.7), then tick every row here and in `plan.md`:

- [ ] 1.5 — reducer purity (code-read; already confirmed above)
- [ ] 2.5 — illegal pause + banner + "Show diagnostics" + live grid (device)
- [ ] 2.6 — grid highlights exactly the differing squares incl. h8 (device; bit-math unit-proven)
- [ ] 2.7 — web excludes the physical/diagnostics route (browser; code-read confirmed)
- [ ] 3.4 — full reject→recover→retry by hand for `ILLEGAL` and `INCONSISTENT` (device)
- [ ] 3.5 — no save from a rejected/unrestored board (E2E-proven; re-confirm on device)
- [ ] 3.6 — this file completed + every row ticked

After the pass: `/10x-impl-review reject-recover-diagnostics` → `/10x-archive reject-recover-diagnostics`.
