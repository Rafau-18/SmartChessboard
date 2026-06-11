<!-- PLAN-REVIEW-REPORT -->
# Plan Review: Chess Rules Engine (F-01)

- **Plan**: context/changes/chess-rules-engine/plan.md
- **Mode**: Deep
- **Date**: 2026-06-11
- **Verdict**: SOUND (po triażu — wszystkie 5 findings naniesione; wyjściowo REVISE)
- **Findings**: 0 critical · 3 warnings · 2 observations — wszystkie FIXED

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| End-State Alignment | PASS |
| Lean Execution | PASS |
| Architectural Fitness | PASS |
| Blind Spots | WARNING → PASS (po fixach F2, F3) |
| Plan Completeness | WARNING → PASS (po fixach F1, F4, F5) |

## Grounding

6/6 ścieżek ✓ (lessons.md, contract-surfaces.md, domain/ layout, GameSummary.kt, AppModules.kt, `domain/chess/` greenfield zgodnie z planem), konwencja pól §1.3 `index = file + 8*rank`, a1=0 ✓, surfaces §5 PGN/FEN + §1.5 promotion + FR-007 mate/stalemate ✓, Progress↔Phase mechaniczny kontrakt ✓ (5 faz, nagłówki i numeracja N.M zgodne), brief↔plan ✓.

## Findings

### F1 — validate() nie wyprodukuje bogatego IllegalReason z samego "legal set membership"

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — realny trade-off; zatrzymaj się na decyzję
- **Dimension**: Plan Completeness
- **Location**: Phase 3 §1 (ChessRules.validate) + Phase 1 §3 (Move.kt IllegalReason)
- **Detail**: Move.kt definiuje 7 powodów odrzucenia (NOT_YOUR_PIECE, BLOCKED, LEAVES_KING_IN_CHECK, ILLEGAL_CASTLE, BAD_EN_PASSANT, PROMOTION_PIECE_REQUIRED, NO_SUCH_MOVE), ale jedyny opisany mechanizm to "resolves the attempt against the legal set". Sprawdzenie przynależności do legalMoves() daje w praktyce tylko NO_SUCH_MOVE (+ specjalny PROMOTION_PIECE_REQUIRED). Bogatsze powody wymagają osobnej ścieżki klasyfikującej powód odrzucenia, której plan nie opisuje. Kontrakt obiecuje powody, których opisany mechanizm nie umie wygenerować. S-07 (diagnostyka) to deklarowany konsument tych powodów.
- **Fix A ⭐ Recommended**: Zawęź IllegalReason dla MVP do tego, co membership faktycznie produkuje (NO_SUCH_MOVE + PROMOTION_PIECE_REQUIRED); bogatą diagnostykę przypisz jawnie do S-07.
  - Strength: Mechanizm i kontrakt spójne; F-01 zostaje "legality core" zgodnie z deklarowaną granicą zakresu.
  - Tradeoff: S-07 dostanie na razie zgrubną informację „ruch nielegalny”.
  - Confidence: HIGH — plan i tak mówi „set finalized as Phase 3 implements”.
  - Blind spot: Potwierdzić, że S-07 nie zakłada już 7 powodów.
- **Fix B**: Dodaj w Phase 3 jawny krok klasyfikacji odrzucenia (po pseudo-legal / po blokadzie / po king-safety) zwracający konkretny powód.
  - Strength: Spełnia bogatszy kontrakt dla S-07 od razu.
  - Tradeoff: Więcej kodu i testów w najcięższej fazie; ryzyko rozjazdu z legalMoves().
  - Confidence: MED — wykonalne, ale rozszerza zakres Phase 3.
  - Blind spot: Większa powierzchnia testów reason-by-reason.
- **Decision**: FIXED (Fix A) — IllegalReason zawężony do PROMOTION_PIECE_REQUIRED + NO_SUCH_MOVE (Phase 1 §3), validate dostrojony (Phase 3 §1), dodano pozycję w „What We're NOT Doing”.

### F2 — Kiwipete budowane „ręcznie” (explicit board setup) to samodzielne źródło błędów

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — realny trade-off; zatrzymaj się na decyzję
- **Dimension**: Blind Spots
- **Location**: Phase 5 §2 (PerftTest — „no FEN parser in scope”)
- **Detail**: Plan każe konstruować startpos i Kiwipete „via explicit board setup helpers (no FEN parser in scope)”. Kiwipete to ~24 bierki plus prawa do roszady KQkq i pole en passant — ręczne ustawianie pole-po-polu jest żmudne i samo błędogenne. Gdy perft nie zgodzi się z referencją, nie będzie wiadomo, czy zawiódł silnik, czy złe ustawienie pozycji testowej.
- **Fix**: Dodaj minimalny helper FEN→Position tylko w commonTest (nie w main). Nie łamie zakresu „No FEN parsing” (ten dotyczy shipowanego API), a daje pewne konstruowanie pozycji referencyjnych ze stringa z Chess Wiki 1:1.
  - Strength: Eliminuje klasę „błąd w teście, nie w silniku”.
  - Tradeoff: Drobny test-only kod do utrzymania.
  - Confidence: HIGH — standardowy wzorzec w testach silników szachowych.
  - Blind spot: Dopisać 2-3 testy samego helpera, by sam nie kłamał.
- **Decision**: FIXED (Fix) — Phase 5 §2 wymaga test-only `fen()` helpera w commonTest (nie w main), z 2-3 testami samego helpera; zakres „No FEN parsing” zachowany.

### F3 — Koszt perft na WasmJS/iOS + pętli dev; „cap depth” jest reaktywny i nie-przypisany per-target

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — szybka decyzja; poprawka oczywista i wąska
- **Dimension**: Blind Spots
- **Location**: Phase 5 §1/§4 + Performance Considerations
- **Detail**: Immutable mailbox + king-safety filter (każdy pseudo-legal aplikowany na próbną planszę + pełny skan ataków) to miliony alokacji 64-listy przy perft(5)≈4,87M liści — na WasmJS/iOS-sim potencjalnie dziesiątki sekund. Plan słusznie mówi „depth może być mniejszy na wolniejszych targetach”, ale konkretne głębokości per-target nie są ustalone z góry; decyzja zapada reaktywnie.
- **Fix**: Ustal teraz docelowe głębokości (np. host = startpos perft(4) + Kiwipete perft(3); wolniejsze targety = perft(3)/perft(2)) i polegaj na curated edge-suite jako głównym dowodzie reguł specjalnych cross-target. Zapisz to w Phase 5 jako wybór, nie jako „jeśli wolne”.
- **Decision**: FIXED (Fix) — Phase 5 §2/§4 i Performance Considerations: głębokości ustalone per-target (host perft(4)/(3); iOS+WasmJS perft(3)/(2)), edge-suite jako główny dowód cross-target, udokumentowane jako wybór.

### F4 — Błędny odnośnik: eval cache keyed by FEN to §5.4, nie §4.4

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — poprawka oczywista i wąska
- **Dimension**: Plan Completeness
- **Location**: Current State Analysis → „Contract constraints”
- **Detail**: Plan: „the eval cache is keyed by FEN (§4.4)”. W contract-surfaces.md §4.4 to „Sign-out” (OAuth). Cache keyed by FEN jest w §5.4 (i tabela §2.3). Czytelnik podążający za cytatem trafia w złe miejsce.
- **Fix**: Zmień (§4.4) → (§5.4).
- **Decision**: FIXED (Fix) — odnośnik poprawiony na (§5.4) w Current State Analysis.

### F5 — Phase 1 mówi „compiles on all targets”, a uruchamia tylko compile WasmJS

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — poprawka oczywista i wąska
- **Dimension**: Plan Completeness
- **Location**: Phase 1 → Automated Verification (bullet 1)
- **Detail**: Treść mówi „Module compiles on all targets” przez compileKotlinWasmJs (tylko wasm). Progress 1.1 jest już dokładniejszy („compiles on WasmJS”). Dla czystego commonMain wasm-compile to dobry proxy, a pełny cross-target dowód daje Phase 5 — więc to tylko nieścisłość sformułowania.
- **Fix**: Ujednolić treść z Progress („compiles on WasmJS”), albo dodać compileKotlinIosArm64 jeśli dosłownie „all targets”.
- **Decision**: FIXED (Fix differently) — wybrano realne „all targets”: Phase 1 bullet 1 i Progress 1.1 kompilują wszystkie trzy targety (`compileKotlinWasmJs` + `compileKotlinIosSimulatorArm64` + `compileAndroidMain`).
