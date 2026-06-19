# Physical-Mode Capture (S-06) — Manual Verification Checklist

Deferred manual checks for the whole slice, to run **at the end of S-06 before archiving**. The
automated success criteria are gated per phase in `plan.md` → `## Progress`; this file collects the
*human* checks (code reviews, on-device walkthroughs) those automated gates cannot cover. Phases
append their manual items here as they land, so the final pass is a single run-through.

Convention: `- [ ]` pending, `- [x]` done. Each item mirrors a `#### Manual` row in `plan.md`.

---

## Phase 1 — Sequence Interpreter (pure domain)

Pure logic, no UI/device — both checks are a **code read**, not an app walkthrough.

### 1.5 — Corpus coverage review

Open `SmartChessboard/shared/src/commonTest/.../domain/board/SequenceInterpreterTest.kt` and confirm
every move shape + rejection is covered, and that the **expected squares are chess-correct** and the
**lift/place orderings are genuinely distinct** (not a copy):

- [ ] Quiet move — pawn + piece: `quietMoveResolvesToTheLiftPlacePair`, `quietPieceMoveResolves`
- [ ] Capture **CAPTURED_FIRST**: `pawnCaptureCapturedFirstResolves`
- [ ] Capture **MOVER_FIRST**: `pieceCaptureMoverFirstResolves`
- [ ] Castling **KING_FIRST / ROOK_FIRST / INTERLEAVED**: `whiteKingsideCastle{KingFirst,RookFirst,Interleaved}Resolves`
- [ ] Castling **both wings + both colours**: `whiteQueensideCastleResolves`, `blackKingsideCastleResolves`, `blackQueensideCastleInterleavedResolves`
- [ ] En passant **both orders**: `enPassantCapturedFirstResolves`, `enPassantMoverFirstResolves`
- [ ] Promotion push + capture → `NeedsPromotion`: `promotionPushNeedsPromotion`, `promotionCaptureNeedsPromotion`
- [ ] j'adoube ignored / no-move → Incomplete: `jadoubeBeforeARealMoveIsIgnored`, `jadoubeWithNoMoveIsIncomplete`
- [ ] Rejections — Incomplete (lone lift, empty), Illegal (off-board, illegal capture): `aLoneLiftIsIncomplete`, `noEventsAreIncomplete`, `anOffBoardLandingIsIllegal`, `anIllegalCaptureIsIllegal`
- [ ] Near-ambiguity resolves uniquely from the origin lift: `twoKnightsToTheSameSquareResolveByTheLiftedOriginNotAmbiguous`

### 1.6 — Only `legalMoves`-sourced moves (never hand-built)

- [ ] Test side: `assertResolved` asserts `resolution.move in legalMoves(position)`.
- [ ] Interpreter side (`SequenceInterpreter.kt`): the only `Move` returned is `moves.single()` where
      `moves ⊆ legalMoves(position)`; no `Move(...)` built from scratch on any return path;
      `NeedsPromotion` returns `from`/`to` ints only.

**Adaptations to note during review (not defects):**
1. `Resolution` lives in its own `Resolution.kt` (the engine's `Move.kt`-vs-`ChessRules.kt` pattern)
   to satisfy the enforced ktlint `standard:filename` rule; `resolvePhysicalMove` stays in
   `SequenceInterpreter.kt` per the plan.
2. `Resolution.Ambiguous` is a **defensive, in-practice-unreachable** branch — under a full lift/place
   stream no two distinct legal moves share a footprint (origin lift identifies the piece, destination
   lift distinguishes capture from quiet). Candidate `lessons.md` entry in Phase 5.

---

## Phase 2+ — appended as phases land
