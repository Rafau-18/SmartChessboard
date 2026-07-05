<!-- IMPL-REVIEW-REPORT:PL -->
# Przegląd implementacji: tryb fizyczny — przechwytywanie na emulatorze (S-06)

- **Plan**: context/changes/physical-capture-emulated/plan.md
- **Zakres**: Fazy 1–5 z 5 (pełny plan)
- **Commity objęte przeglądem**: 3dbf1cf..c9442c7 (p1–p5 + epilog)
- **Data**: 2026-06-19
- **Werdykt**: ZATWIERDZONY
- **Znaleziska**: 0 krytycznych · 1 ostrzeżenie · 0 obserwacji
- **Triażowanie**: F1 NAPRAWIONE (2026-06-19 — wyłącznie komentarz `TODO(S-09)` na obu powiązaniach `BoardConnection`)

> Faza 1 (3dbf1cf) została zrecenzowana oddzielnie w `impl-review-phase-1.md`
> (ZATWIERDZONO; F1/F2/F3 wszystkie NAPRAWIONE — wyłącznie komentarze/doc).
> Niniejszy pełny przegląd obejmuje fazy 1–5 całościowo i nie otwiera ponownie
> rozwiązanych znalezisk z fazy 1.

## Werdykty wymiarów

| Wymiar | Werdykt |
|--------|---------|
| Zgodność z planem | PASS |
| Dyscyplina zakresu | PASS |
| Bezpieczeństwo i jakość | WARNING (1 znalezisko — F1, niski/perspektywiczny) |
| Architektura | PASS |
| Spójność wzorców | PASS |
| Kryteria sukcesu | PASS |

**Ogólnie: ZATWIERDZONY** — wszystkie wymiary PASS, jedno niskopoziomowe ostrzeżenie perspektywiczne (naprawa to wyłącznie komentarz).

### Weryfikacja na żywo (niniejszy przegląd, drzewo przy c9442c7)

- `:shared:testAndroidHostTest` — BUILD SUCCESSFUL (pokrywa interpreter, reducer, VM i E2E — wszystkie żyją w `commonTest`).
- ktlint — czysty na wszystkich nowych plikach S-06 (`presentation/physical/**`, `domain/board/{Occupancy,Resolution,SequenceInterpreter}.kt`).
- iOS / wasm — bazuję na zarejestrowanym zielonym z fazy 5 (`f8903b5` uruchomił pełny `:shared` suite na `:shared:iosSimulatorArm64Test` i `:shared:wasmJsTest`); jedyny commit od tamtego czasu to epilog wyłącznie dokumentacyjny (`c9442c7`).

## Zweryfikowane obszary fokusowe (zgodnie z żądaniem)

1. **Bramka §6.2 żyje w efekcie `CommitMove`, nie w reducerze — POPRAWNIE.** `reduce` nie wykonuje żadnego I/O; `confirm()` (PhysicalPlayReducer.kt:249-254) jedynie emituje `PhysicalEffect.CommitMove`; stan przesuwa się wyłącznie w `commit()` po `MoveCommitted`. VM (PhysicalPlayViewModel.kt:152-171) wykonuje łańcuch `validate → sanForMove → writePgn → autoSaver.acceptMove`, po czym dispatchuje `MoveCommitted`; rzucony wyjątek dispatchuje `MoveRejected(SAVE_FAILED)`. Potwierdzone przez `aForcedJournalWriteFailureRejectsTheMoveAndDoesNotAdvance`. Warstwa DI Androida ustawia też `Settings(commit = true)` (PlatformModule.android.kt:28), co zapewnia trwały zapis dziennika przed zaliczeniem ruchu.

2. **Dwukrokowy auto-close (reducer emituje `FinishGame` po `MoveCommitted`) — POPRAWNIE.** `commit()` (PhysicalPlayReducer.kt:298-322) przelicza `status` i gdy `gameResultFor(...) != null` emituje `FinishGame`; `finishGame()` w VM zapisuje gotowe PGN i nadpisuje lokalny wpis (adaptacja #1). E2E `aMatingMoveAutoClosesTheGame` jest zielony.

3. **Brak efektu `Connect` (port nie ma `connect()`) — POPRAWNIE.** `BoardConnection` eksponuje wyłącznie `connectionState` / `events` / `send`; `connect()` to metoda konkretnej klasy `EmulatedBoard`, sterowana przez warstwę DI. VM subskrybuje wszystkie strumienie w `init` przed `load()` (PhysicalPlayViewModel.kt:60-78); `paused` jest wartością pochodną (`connectionState == DISCONNECTED`). Spełnia regułę subscribe-before-connect dla gorącego strumienia bez replay.

4. **Adaptacje opisane w `manual-verification.md` — wszystkie rzetelne i uzasadnione** po weryfikacji w kodzie: usunięcie `Connect`, `LoadGame` jako `data object`, `FinishGame(sanMoves)`, `MoveRejected` na enumie `RejectionReason`, tint `highlightedSquares`, `onGameCreated(String, GameMode)`, connect-on-bind dla emulatora oraz E2E asertujące PGN z repozytorium (chmury), bo `GameAutoSaver` czyści gotowy wpis dziennika po potwierdzonym flushu końcowym.

## Weryfikacja zakresu

- **W planie I w diffie**: każdy plik z faz 1–5 obecny i zaimplementowany zgodnie z zamierzeniem (sprawdzone przez podrodzaj drift, pozycja po pozycji).
- **Granice „Czego NIE robimy" — wszystkie dotrzymane**: brak prawdziwego BLE (powiązany tylko `EmulatedBoard`); brak UI diagnostyki/odtwarzania (odrzucenie = wyłącznie komunikat); brak reconcile-po-reconnect / wznowienia (rozłączenie → wyłącznie `paused`); brak symulatora GUI planszy (`interaction = null`); brak migracji DB (`git diff -- supabase/` pusty); brak zmian w `GameJournal`/`GameAutoSaver` (nieobecne w diffie).
- **Odchylenia to udokumentowane adaptacje wymuszone przez API (nie pełzanie zakresu)**: usunięty efekt `Connect`, zmieniony kształt `LoadGame` / `FinishGame`, dodany `PhysicalMsg.SyncChanged` (kontrakt planu był świadomym szkicem — „exact fields finalized in code").
- **`StatusBanner`/`SyncIndicator` reimplementowane lokalnie** w `PhysicalPlayScreen` (PhysicalPlayScreen.kt:257,:298) zamiast importowane: cyfrowe wersje są `private fun` typowane na `PlayState`, więc dosłowne reużycie jest zablokowane — i prywatne composable w zasięgu ekranu *to* konwencja repozytorium (PlayScreen robi tak samo). Zbadano; nie jest defektem.
- **Faza 1 (F1/F2/F3)** zrecenzowana i NAPRAWIONA w `impl-review-phase-1.md`; nie otwierana ponownie w niniejszym przeglądzie.

## Znaleziska

### F1 — Zakres DI dla `BoardConnection` nigdy nie jest anulowany (brak hooka teardown pod swap w S-09)

- **Ciężkość**: ⚠️ OSTRZEŻENIE
- **Wpływ**: 🏃 NISKI — szybka decyzja; naprawa jest oczywista i wąsko ograniczona
- **Wymiar**: Bezpieczeństwo i jakość (Niezawodność)
- **Lokalizacja**: SmartChessboard/shared/src/androidMain/.../di/PlatformModule.android.kt:35-36 ; SmartChessboard/shared/src/iosMain/.../di/PlatformModule.ios.kt:24-25
- **Szczegóły**: `single<BoardConnection>` tworzy `CoroutineScope(SupervisorJob() + Dispatchers.Default)`, uruchamia na nim `connect()` i nigdy nie anuluje tego zakresu; `EmulatedBoard.disconnect()` nie jest wywoływane z produkcji. Dla emulatora jest to zgodne z planem (kontrakt DI z Fazy 4 zakładał „długożyjący zakres aplikacji") i nieszkodliwe — jeden singleton na czas procesu, izolowany przez `SupervisorJob`, jedynym kosztem jest bezczynna pętla statusowa (~30 s). Luka ma charakter perspektywiczny: gdy S-09 powiąże prawdziwy adapter BLE w dokładnie tym samym kształcie, „nigdy nie anulowany" staje się realnym wyciekiem połączenia/zasobu, a nic (ani kompilator, ani komentarz) tego nie sygnalizuje. Istniejący komentarz wyjaśnia connect-on-bind, lecz nie brakujący teardown — a kultura tego repo (reguły SYNC-comment + terminal-flush w lessons.md) jest właśnie po to, by flagować dokładnie takie przyszłe sprzężenia.
- **Naprawa**: Dodaj jednolinijkowy komentarz SYNC/TODO na obu blokach `single<BoardConnection>`, wiążący anulowanie zakresu / `disconnect()` ze swapem adaptera BLE w S-09 (opcjonalnie zarejestruj Koin `onClose { (it as? EmulatedBoard)?.disconnect() }`). Wyłącznie komentarz; brak zmiany zachowania, trójcelowa zieleń pozostaje.
- **Decyzja**: NAPRAWIONE (2026-06-19) — dodano blok `TODO(S-09)` nad oboma powiązaniami `single<BoardConnection>`
  (PlatformModule.android.kt / PlatformModule.ios.kt), wiążący anulowanie zakresu / `disconnect()` ze swapem
  adaptera BLE i wzajemnie odsyłający oba moduły. Wyłącznie komentarz; `:shared:testAndroidHostTest` + ktlint zielone.

## Uwagi

- Slice jest naprawdę wysokiej jakości: bramka §6.2, bit-math (bit znaku na pozycji 63 obsługiwany przez `and … != 0L`), kolejność subscribe-before-connect, dyscyplina `Throwable` na wasm, zabezpieczenie single-fire dla zakończenia gry i dwukierunkowe komentarze SYNC (`footprintOf` ↔ `applyMove`) — wszystko poprawne i pokryte testami.
- Kryteria sukcesu: zautomatyzowane wszystkie 5 faz zielone (zarejestrowane SHAsy + live re-run na JVM-host + ktlint czysty). Wszystkie wiersze `#### Manual` pozostają `[ ]` zgodnie z udokumentowaną konwencją projektu (pojedyncze przejście na koniec slice'a zebrane w `manual-verification.md`). Niniejszy przegląd merytorycznie potwierdza pozycje manualne dotyczące czytania kodu (pokrycie korpusu 1.5/1.6, wolność IO reducera 3.5, wartości `supportsPhysicalBoard` 2.4, bramkowanie DI 4.6, write-backs 5.4/5.5); walkthroughs na urządzeniu (4.4 / 4.5 / 5.6) pozostają prawdziwymi bramkami ludzkimi, niewykonanymi w tym przeglądzie.
