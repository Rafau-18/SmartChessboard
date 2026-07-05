# Przegląd implementacji: Game End and Result (S-05)

> Polski duplikat raportu do czytania. **Plikiem kanonicznym do wznowienia triage jest
> [`impl-review.md`](impl-review.md)** (zawiera marker `IMPL-REVIEW-REPORT` i pola `Decision`).
> Wznowienie: `/10x-impl-review context/changes/game-end-and-result/reviews/impl-review.md`.

- **Plan**: context/changes/game-end-and-result/plan.md
- **Zakres**: Fazy 1–5 z 5 (wszystkie fazy ukończone; Faza 5 = trzypowierzchniowe E2E, które wniosło 4 poprawki produkcyjne + write-backi dokumentacji)
- **Data**: 2026-06-16 (Fazy 1–4); ponowny przegląd po Fazie 5: 2026-06-17
- **Werdykt**: APPROVED (zatwierdzone)
- **Znaleziska**: 0 krytycznych · 0 ostrzeżeń · 4 obserwacje

## Weryfikacja powtórzona w trakcie przeglądu

| Sprawdzenie | Wynik |
|-------------|-------|
| `:shared:testAndroidHostTest` | BUILD SUCCESSFUL na headzie Fazy 5 (eadd326); nowe przypadki GameAutoSaverTest + HistoryViewModelTest zielone |
| ktlint (pliki źródłowe) | czysto — zgłoszone 115 trafień było wyłącznie w `build/generated/*` |
| `:shared:iosSimulatorArm64Test` / `:shared:wasmJsTest` | nie powtarzane; utrwalone na zielono w SHA commitów (a7a14e1, 82c63ec, 5759078, a37a9dd, następnie przez Fazę 5: f0e7b04, 1b1c6a5, 7fcc3ff, 803cdc3) |

## Werdykty wymiarów

| Wymiar | Werdykt |
|--------|---------|
| Zgodność z planem (Plan Adherence) | PASS |
| Dyscyplina zakresu (Scope Discipline) | PASS |
| Bezpieczeństwo i jakość (Safety & Quality) | PASS (3 obserwacje) |
| Architektura (Architecture) | PASS |
| Spójność wzorców (Pattern Consistency) | PASS (1 obserwacja) |
| Kryteria sukcesu (Success Criteria) | PASS (trzypowierzchniowe E2E Fazy 5 wykonane; przeniesione checki 2.6/2.7, 4.4–4.6 zweryfikowane) |

### Dlaczego APPROVED

Wszystkie pięć krytycznych punktów planu zweryfikowano jako poprawne:

1. **Single-fire auto-finish** — strażnik `autoResult != null && state.result == null` plus wczesny `return` (`PlayViewModel.kt:284`). `result` jest zapisywany do stanu synchronicznie, przed jakimkolwiek zawieszeniem coroutine, więc drugie tapnięcie nie wejdzie ponownie. Brak luki re-entrancy.
2. **Bezpieczne zakończenie offline (kolejność)** — zapis do journala → flush → `journal.clear()` dopiero po pomyślnym powrocie `finishGame` i potwierdzeniu, że przeładowane PGN nadal się zgadza (`GameAutoSaver.kt:76-79`); `reconcile` re-flushuje zżurnalizowane, niezsynchronizowane zakończenie przez `sync` (`GameAutoSaver.kt:100`). Wynik finished nie ginie offline.
3. **Serializacja DTO w finishGame** — `toResultColumn()` zwraca `white|black|draw`, dokładną odwrotność `parseResult` (`SupabaseGamesRepository.kt:174-179`).
4. **Ścieżka reconcile journal-ahead FINISHED** — poprawnie domyka kopię w chmurze; gałąź cloud-wins bez zmian (LWW wg §3.4).
5. **Guardraile "What We're NOT Doing"** — bez naruszeń (brak migracji/schematu/pgTAP, brak auto-detekcji remisu z reguły, brak un-finish/wznowienia, brak tagu rezygnacji/zakończenia, brak kodu trybu fizycznego, brak usuwania/cofania/animacji). Do Fazy 4 jedynym dotknięciem międzyekranowym był routing w `App.kt`; trzypowierzchniowe E2E Fazy 5 wniosło następnie ograniczone, pokryte testami poprawki (plan z góry to dopuszczał: „fixes land where they belong if found") w History, Auth, NewGame, Play, Replay i wasm BrowserNavigation — żaden z nich nie narusza granicy NOT-doing (patrz podsekcja Fazy 5).

`finishGame`/`updatePgn` wołają `update()` z supabase-kt 3.6.0, które samo jest `suspend` i rzuca przy niepowodzeniu — więc brak końcowego `.decode*()` **nie** jest cichym zapisem. Od Fazy 5 (7fcc3ff, 803cdc3) `sync` najpierw rethrowuje `CancellationException`, a potem łapie `Throwable` (awaria fetch w wasm to `kotlin.Error`, nie `Exception`). Przy niepowodzeniu wpis **w toku** retryuje ograniczone okno, po czym rezygnuje i pozostaje dirty do sync następnego ruchu; wpis **zakończony** retryuje dalej (backoff przycięty do ostatniego opóźnienia) aż się powiedzie lub ekran się zamknie (patrz podsekcja Fazy 5 / Znalezisko F3).

Checki manualne 2.6/2.7 oraz 4.4–4.6 przeniesiono do trójpowierzchniowego E2E Fazy 5, które już się wykonało (patrz niżej).

## Faza 5 — poprawki z trójpowierzchniowego E2E (dodane 2026-06-17)

Trójpowierzchniowe E2E Fazy 5 (Android, iOS, web) nie było pustym przejściem po dokumentacji: ujawniło cztery realne defekty wieloplatformowe w ścieżce zamknięcia gry, każdy naprawiony tam, gdzie należy. Ponowny przegląd w 5 wymiarach (po jednym recenzencie na poprawkę + recenzent zakresu/dokumentacji, każdy adwersaryjnie zweryfikowany) zwrócił **PASS we wszystkich pięciu, zero CRITICAL/WARNING** — każde znalezisko Fazy 5 jest OBSERVATION/LOW. Poprawki są z góry dopuszczone klauzulą planu „fixes land where they belong if found" i żadna nie narusza granicy „What We're NOT Doing".

| Commit | Poprawka | Werdykt |
|--------|----------|---------|
| `f0e7b04` | Push-driven odświeżanie History przez nową `GamesRepository.changes: SharedFlow<Unit>` (emitowane na create/finish, **nie** updatePgn), zbierane przez zachowany History ViewModel — zastępuje efekt kompozycji, który iOS pomijał. Brak wyścigu utraty sygnału: repo to singleton Koin, a `HistoryKey` to nigdy niezdejmowany korzeń back-stacka, więc kolektor pozostaje zasubskrybowany, gdy Play/NewGame są na wierzchu. | PASS |
| `1b1c6a5` | Nawigacja przeglądarki wasm `Chronological → Hierarchical`, by Back przeglądarki zdejmował żywy stos (wcześniej Back lądował na zastąpionych ekranach). Jednorazowy `BrowserHistoryIsInUse` i „brak URL→state restore po reloadzie" bez zmian, zgodne z Non-Goals. | PASS |
| `7fcc3ff` | Rozszerzenie `catch (Exception)` → `catch (Throwable)` w 5 ViewModelach (awaria fetch w wasm to `kotlin.Error`, nie `Exception`), by offline nie crashował aplikacji — patrz **F4**. | PASS |
| `803cdc3` | Flush zakończonej gry retryuje (przycięty backoff) aż do reconnectu/zamknięcia ekranu, zamiast rezygnować po ~7s — patrz **F3**. | PASS |

Write-backi dokumentacji (`60a16b5`) zweryfikowane jako trafne: roadmap S-05 → implemented (+ nota Stream B); contract-surfaces §3.2 „Mark finished" niesie teraz `pgn` (atomowy status+result+pgn), z datą; cztery poprawnie sformułowane wpisy lessons.md; Progress planu 5.1–5.5 + przeniesione 2.6/2.7/4.4–4.6 odhaczone z SHA. Nota o zakresie: nowe `GamesRepository.changes` to powierzchnia wewnętrzna mobilna, słusznie nieobecna w contract-surfaces.md (które wyłącza architekturę wewnętrzną mobilną).

## Znaleziska

### F1 — Asymetryczna normalizacja movetext w isAhead/reconcile

- **Waga**: 🔭 OBSERVATION
- **Wpływ**: 🏃 LOW — szybka decyzja; poprawka oczywista i wąsko zakresowa
- **Wymiar**: Bezpieczeństwo i jakość (Niezawodność)
- **Lokalizacja**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:136 (`movetext`); `isAhead` na :125. (Numery linii odświeżone dla Fazy 5 — samo znalezisko bez zmian; 803cdc3 nie ruszył żadnej z tych funkcji.)
- **Opis**: `movetext()` usuwa wyłącznie terminator gry w toku (`removeSuffix("*")`), nigdy tokenów zakończenia (`1-0` / `0-1` / `1/2-1/2`). Dlatego w `reconcile`/`isAhead` zżurnalizowane zakończenie (`"1. e4 e5 0-1"`) jest porównywane z dokumentem chmury w toku (`"1. e4 e5"`) i uznane za "ahead" tylko dlatego, że `journalMoves.startsWith("$cloudMoves ")` akurat zachodzi. Działa dla każdej osiągalnej dziś ścieżki (`finishGame` czyści journal na jedynej ścieżce zapisującej zakończony wiersz w chmurze, więc porównanie "zakończony journal vs zakończona chmura, te same ruchy, inny terminator" nigdy nie występuje). To utajona kruchość: niezmiennik prefiksu zależy po cichu od tego, który terminator jest obecny, a nie od ruchów. Gdyby przyszła ścieżka kiedykolwiek reconcile'owała dwa zakończone dokumenty, `isAhead` zwróciłby `false` i odrzucił kopię z journala.
- **Poprawka**: Usuwaj wszystkie cztery tokeny wyniku w `movetext()` (albo porównuj na sparsowanej liście ruchów), żeby sprawdzenie prefiksu zależało tylko od ruchów; dodaj test reconcile dla "journal zakończony, chmura zakończona, te same ruchy, inny terminator".
- **Decyzja**: FIXED (2026-06-17) — `movetext()` usuwa teraz wszystkie terminatory, a `isAhead` dostał jawny check `isFinished()`: dwa zakończone dokumenty o tych samych ruchach rozstrzygane są przez status (LWW, chmura wygrywa), nie przez przypadkowy prefiks tokenu; przy tym „zakończony journal vs chmura w toku" przy tych samych ruchach nadal się re-flushuje (kluczowy przypadek, który naiwne „strip all" by zepsuło). Dodano test regresji `reconcileWithTwoFinishedDocsSameMovesPrefersCloud`. Zielone na Android host + iOS Native + wasm.

### F2 — Plik nazwany EndGamePicker.kt, eksportowany composable to EndGameDialog

- **Waga**: 🔭 OBSERVATION
- **Wpływ**: 🏃 LOW — szybka decyzja; poprawka oczywista i wąsko zakresowa
- **Wymiar**: Spójność wzorców
- **Lokalizacja**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/play/EndGamePicker.kt
- **Opis**: Bliźniaczy plik bazowy `PromotionPicker.kt` eksportuje composable o nazwie `PromotionPicker` — nazwa pliku == publiczny composable. Nowy plik `EndGamePicker.kt` eksportuje natomiast `EndGameDialog`, więc czytelnik szukający "EndGamePicker" znajdzie plik, ale nie composable wejściowy. Zachowanie jest poprawne; to wyłącznie drobiazg odkrywalności.
- **Poprawka**: Zmień nazwę pliku na `EndGameDialog.kt` (albo composable na `EndGamePicker`), żeby plik i symbol pasowały do konwencji `PromotionPicker`.
- **Decyzja**: FIXED (2026-06-17) — zmieniono composable `EndGameDialog` → `EndGamePicker` i wewnętrzny surface `EndGameDialogSurface` → `EndGamePickerSurface` (plik zostaje `EndGamePicker.kt`), zaktualizowano wywołanie w `PlayScreen.kt`. Plik == composable, zgodnie z `PromotionPicker` i nazwą z planu. Moduł shared kompiluje się; testy Android host zielone.

### F3 — Flush zakończonej gry retryuje teraz nieograniczenie aż do reconnectu (wolny od wyścigu z reguły domenowej)

- **Waga**: 🔭 OBSERVATION
- **Wpływ**: 🏃 LOW — informacyjne; brak akcji
- **Wymiar**: Bezpieczeństwo i jakość (Niezawodność)
- **Lokalizacja**: SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/domain/games/GameAutoSaver.kt:69-98 (strażnik rezygnacji ~:94, backoff ~:95); commit 803cdc3
- **Opis**: Strażnik rezygnacji zmienił się z `if (failures == retryDelaysMs.size) return` na `if (entry.result == null && failures == retryDelaysMs.size) return`. Wpis **w toku** nadal rezygnuje po `retryDelaysMs.size` (3) niepowodzeniach (= 4 próby w chmurze) i zostaje dirty do sync następnego ruchu; wpis **zakończony** nigdy nie spełnia lewego członu, więc retryuje w nieskończoność z backoffem przyciętym przez `delay(retryDelaysMs[minOf(failures, retryDelaysMs.lastIndex)])` (bez wyjścia poza zakres) aż się powiedzie lub ekran się zamknie. Powód: zakończona gra nie ma kolejnego ruchu, który ponownie wyzwoliłby sync, więc wolny reconnect wcześniej zostawiał „Saving…" kręcące się do reconcile przy następnym ładowaniu. **Bezpieczne**: `catch (CancellationException) { throw }` poprzedza rozszerzony catch `Throwable`, więc anulowanie viewModelScope przy zamknięciu ekranu przerywa pętlę w punkcie zawieszenia (wywołanie chmury lub `delay()`) — brak nieskończonego kręcenia na martwym ekranie. **Wolne od wyścigu**: zakończona gra nie przyjmuje kolejnych ruchów, więc istnieje tylko jedno zakończone PGN i nic nowszego do nadpisania. Pokryte testami `offlineFinishKeepsRetryingPastTheBoundedWindow…` (6 wywołań finishGame) i `offlineInProgressSaveStillGivesUpAtTheBoundedWindow` (4 wywołania updatePgn, potem dirty). Zastępuje opis „bounded 4 attempts / keep the entry dirty" z faz 1–4 dla ścieżki finished.
- **Poprawka**: Brak — zachowanie poprawne, zamierzone i pokryte testami.
- **Decyzja**: ACCEPTED (informacyjne)

### F4 — catch sieci wasm rozszerzony z Exception na Throwable w warstwach data/presentation

- **Waga**: 🔭 OBSERVATION
- **Wpływ**: 🏃 LOW — informacyjne; brak akcji
- **Wymiar**: Bezpieczeństwo i jakość (Niezawodność) / Spójność wzorców
- **Lokalizacja**: commity 7fcc3ff (AuthViewModel sign-in+sign-out, HistoryViewModel load+refresh, PlayViewModel load, ReplayViewModel load, NewGameViewModel create) i 803cdc3 (flush GameAutoSaver.sync) — 8 miejsc wywołań
- **Opis**: Awaria sieci Ktor/Supabase w wasm objawia się jako `kotlin.Error` (`Throwable`), nie `Exception`, więc `catch (Exception)` ją pomijał i uciekała jako nieobsłużony wyjątek coroutine — offline każde kliknięcie sieciowe crashowało aplikację. Każde miejsce wywołania sieci łapie teraz `Throwable`. **Zweryfikowane poprawnie**: każdy catch `Throwable` jest bezpośrednio poprzedzony `catch (CancellationException) { throw }`, więc anulowanie strukturalnej współbieżności nigdy nie jest połykane; każdy mapuje na spójny, atomowo przypisany stan (load→Error, create→failed) lub zamierzone, udokumentowane połknięcie (refresh zachowuje załadowaną listę; signOut polega na `sessionState`). Grep całego repo nie znajduje już `catch (Exception)` w `presentation/` ani `domain/games/`, a dodano test regresji (`HistoryViewModelTest.loadMapsANonExceptionThrowableToError`, rzucający `kotlin.Error`). Zgodne z regułą lessons.md „awaria fetch w wasm to Throwable".
- **Poprawka**: Brak — poprawne i spójne. (Tylko kosmetyka: ViewModele importują `kotlin.coroutines.cancellation.CancellationException`, a GameAutoSaver typealias `kotlinx.coroutines` — ten sam typ, niewart zmiany.)
- **Decyzja**: ACCEPTED (informacyjne)
