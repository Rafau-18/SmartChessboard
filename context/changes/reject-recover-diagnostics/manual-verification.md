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

## Pending (rolled up at end-of-slice)

Phase 2 (2.5–2.7) and Phase 3 (3.4–3.6) manual rows will be appended here as those phases land. All
`#### Manual` rows stay `- [ ]` in `plan.md` by design — they are this single end-of-slice pass. Run
them on a real Android device (and a browser for the web-exclusion check), tick them here and in
`plan.md`, then `/10x-impl-review` and `/10x-archive`.
