<!-- PLAN-REVIEW-REPORT -->
# Przegląd planu: Firmware BLE GATT Service (F-03)

- **Plan**: `context/changes/firmware-ble-gatt-service/plan.md`
- **Tryb**: Głęboki
- **Data**: 2026-06-19
- **Werdykt**: REVISE (POPRAW)
- **Znaleziska**: 1 krytyczne · 0 ostrzeżeń · 3 obserwacje

## Werdykty

| Wymiar | Werdykt |
|--------|---------|
| Zgodność ze stanem końcowym | PASS |
| Lean Execution | PASS |
| Dopasowanie architektoniczne | PASS |
| Ślepe pola | WARNING |
| Kompletność planu | WARNING |

## Ugruntowanie

5/5 ścieżek ✓ (`firmware/src/main.cpp`, `firmware/src/pins.h`, `firmware/platformio.ini`, `firmware/sdkconfig.defaults`, `firmware/src/CMakeLists.txt`; `firmware/lib/` + `firmware/test/` poprawnie nieobecne) · symbole ✓ (`square_index = file + 8*rank`; debounce `agree[64]` / `kStableScans = 4`; GPIO22/23 nie kolidują z mapą pinów matrycy; złote wektory Fazy 1 zgadzają się z `BoardWireCodecTest.kt` bajt po bajcie, zarówno prawidłowe ramki jak i przypadki zniekształcone) · brief↔plan ✓ (fazy, decyzje, zakres spójne).

## Mocne strony (zweryfikowane, nie tylko przejrzane)

- **Złote wektory Fazy 1 zgadzają się z `BoardWireCodecTest.kt` bajt po bajcie** — każda ramka `SQUARE_EVENT` / `BUTTON_EVENT` / `BOARD_SNAPSHOT` / `DEVICE_STATUS` i każde odrzucenie zniekształconego/zarezerwowanego (`empty`, `05 00`, `01 FF`, 10-bajtowy snapshot, `02 85`, `02 C0`, `03 02`, `04 64`, `84`, `90`, `81 02`, `82 00`, `83 00`). Wielojęzykowa wyrocznia redukująca ryzyko dryfu kontraktu jest realna, nie aspiracyjna.
- Rozróżnienie **CCCD-subscribe vs gołe CONNECT** (notify bez subskrybenta jest porzucane, więc burst przy połączeniu musi odpala przy zapisie CCCD `board_event`) jest poprawnie uchwycone — klasyczna pułapka NimBLE.
- **31-bajtowy budżet reklamowania** (UUID serwisu w reklamie, nazwa w scan response) i **zastrzeżenie dotyczące symbolu `ble_gatts_notify_custom`** są oboje uprzedzone.
- Reuse-over-rewrite, mandat głupiej-planszy / brak-logiki-szachowej i reguła SYNC-comment z `lessons.md` są wszystkie honorowane. `firmware_version = 1.0.0` rzeczywiście pasuje do `EmulatedBoard.kt:43` (`FirmwareVersion(1, 0, 0)`).

## Znaleziska

### F1 — Kryteria sukcesu w treści fazy używają checkboxów `- [ ]` (składnia tylko dla Progress)

- **Ciężkość**: ❌ KRYTYCZNE — narusza mechaniczny kontrakt `## Progress`
- **Wpływ**: 🏃 NISKI — szybka decyzja; poprawka jest oczywista i wąsko zakrojona
- **Wymiar**: Kompletność planu
- **Lokalizacja**: Bloki "Success Criteria" Faz 1–4 — 25 linii (123–129, 179–188, 238–250, 292–298)
- **Szczegóły**: Cztery bloki `#### Automated / Manual Verification` w treści faz używają checkboxów `- [ ]`. Kontrakt formatu progress (`references/progress-format.md`) rezerwuje checkboxy wyłącznie dla sekcji `## Progress` — bloki faz muszą używać zwykłych punktorów `- `. Kanoniczny siostrzany plan `physical-capture-emulated` (prowadzony czysto przez `/10x-implement`, 5 commitów faz) stosuje to: zwykłe punktory `- ` w treściach faz, `- [ ]` tylko pod `## Progress`. Tu jest 25 błędnych checkboxów w treściach faz przed linią 350. Sama sekcja `## Progress` (358–409) jest dobrze sformowana i mapuje 1:1 do faz — defekt jest czysto duplikowanymi checkboxami w treściach, które dają parserowi dwa źródła prawdy `[ ]` ("następne oczekujące = pierwsze `- [ ]` w kolejności dokumentu" trafiałoby w linię 123, nie element Progress 1.1).
- **Poprawka**: W czterech blokach Success-Criteria treści faz, zamień każde `- [ ]` na zwykły punktor `- `. Pozostaw checkboxy `## Progress` (358–409) niezmienione — są kanonicznym trackerem.
- **Decyzja**: OCZEKUJE

### F2 — Wspólna bitmapa `stable` jest czytana cross-task dla snapshotów (wyścig torn-read)

- **Ciężkość**: ⚠️ OSTRZEŻENIE
- **Wpływ**: 🔎 ŚREDNI — realne kompromisy; zatrzymaj się by to przemyśleć
- **Wymiar**: Ślepe pola
- **Lokalizacja**: Faza 2 §4 (helpery źródła statusu/snapshotów) + Faza 3 §1 (potok producent/konsument)
- **Szczegóły**: Projekt producent/konsument starannie oddziela *dostarczanie* notify (task skanujący wysyła na kolejkę FreeRTOS; tylko kontekst hosta BLE wywołuje notify). Ale ścieżka snapshota omija kolejkę: Faza 2 §4 mówi że helper snapshota "czyta żywą zdebouncowaną bitmapę `stable`", a ten odczyt następuje w kontekście hosta BLE (burst przy subskrypcji, `REQUEST_SNAPSHOT`) i w timerze diagnostycznym — podczas gdy task skanujący pisze `stable`. `stable` jest `uint64_t`; na 32-bitowym dual-core ESP32 64-bitowy odczyt to dwa nie-atomowe załadowania słów, więc czytelnik na innym tasku/rdzeniu może obserwować połowicznie zaktualizowaną wartość → okazjonalnie uszkodzony `BOARD_SNAPSHOT`. To jest dokładnie ramka przy połączeniu/ponownym połączeniu na której polega wznowienie S-08, a zniekształcony snapshot jest bolesny do diagnozowania później. Plan strzeże strony zapisu ("nigdy nie wywołuj BLE z kontekstu skanowania") ale nie strony odczytu.
- **Poprawka A ⭐ Zalecana**: Pozyskuj snapshoty od producenta
  - Podejście: Przy subskrypcji / `REQUEST_SNAPSHOT` / tiku diagnostycznym ustaw żądanie obsługiwane przez task skanujący; task skanujący czyta własną `stable`, koduje `BOARD_SNAPSHOT` i wysyła na tę samą kolejkę zdarzeń.
  - Mocna strona: Task skanujący pozostaje jedynym właścicielem `stable`; brak blokady na gorącej ścieżce skanowania; zachowuje niezmiennik "każda ramka przepływa przez jedną kolejkę" który projekt już ma.
  - Kompromis: Dodaje mały kanał zwrotny żądania (flaga / druga kolejka) z kontekstu BLE + timerów do tasku skanującego.
  - Pewność: WYSOKA — szkielet producent/konsument już istnieje; to kieruje jeszcze jeden typ ramki przez niego.
  - Ślepe pole: Snapshot przy subskrypcji zyskuje ~jeden cykl skanowania (~20 ms) opóźnienia — komfortowe w ramach NFR ≤100 ms.
- **Poprawka B**: Chroń `stable` sekcją krytyczną / atomowym lustrem
  - Podejście: Task skanujący publikuje `stable` pod `portMUX` (`taskENTER_CRITICAL`) lub do atomicznie aktualizowanego lustra; każdy cross-task czytelnik stosuje tę samą ochronę.
  - Mocna strona: Minimalna, zlokalizowana; czytelnicy pozostają gdzie są.
  - Kompromis: Blokada przy zatwierdzaniu skanu; poprawność zależy od ochrony KAŻDEGO miejsca odczytu (włącznie z timerem diagnostycznym) — dokładnie ta dyscyplina którą łatwo później upuścić.
  - Pewność: ŚREDNIA — gołe `volatile uint64_t` NIE jest bezpieczne przed torn-read; wymaga prawdziwego `portMUX`, zweryfikowanego między rdzeniami.
- **Decyzja**: OCZEKUJE

### F3 — Write-back UUID §1.2 wywołuje change-control ale pomija lustro prd.md

- **Ciężkość**: 📝 OBSERWACJA
- **Wpływ**: 🏃 NISKI — szybka decyzja; poprawka jest oczywista i wąsko zakrojona
- **Wymiar**: Kompletność planu
- **Lokalizacja**: Faza 2 §2 (write-back UUID) + Faza 4 §2 (rozwiązania PRD)
- **Szczegóły**: Change-control `contract-surfaces.md` (linie 44–54) mówi że każda zmiana Sekcji 1 (BLE) musi być odzwierciedlona w BOTH `prd-firmware.md` ORAZ `prd.md`, z datowanym uzasadnieniem. Faza 2 §2 wypełnia §1.2 i explicite cytuje "własną regułę change-control dokumentu" dla aktualizacji `updated:`; Faza 4 §2 aktualizuje `prd-firmware.md` (OQ-5) — ale nic nie dotyka `prd.md`. Albo wypełnienie UUID to realna zmiana §1 (wtedy `prd.md` potrzebuje też jednolinijkowej notatki) albo wypełnienie zarezerwowanego placeholdera jest zwolnione (to powiedz to wprost). Jak napisano, plan stosuje regułę w połowie.
- **Poprawka**: Dodaj jednolinijkową datowaną notatkę do Decyzji Implementacyjnych `prd.md` dla przypisania UUID §1.2, LUB dodaj zdanie do Fazy 4 wyjaśniające dlaczego lustro `prd.md` jest N/A (UUID są wewnętrznym, niewidocznym dla produktu detalem). Zdecyduj świadomie.
- **Decyzja**: OCZEKUJE

### F4 — Głębokość kolejki / polityka backpressure nieokreślona vs niezmiennik "nigdy nie łącz"

- **Ciężkość**: 📝 OBSERWACJA
- **Wpływ**: 🏃 NISKI — szybka decyzja; poprawka jest oczywista i wąsko zakrojona
- **Wymiar**: Ślepe pola
- **Lokalizacja**: Faza 3 §1 (potok zdarzeń producent/konsument)
- **Szczegóły**: Plan definiuje kolejkę i mówi że zdarzenia game-mode bez subskrypcji są porzucane (poprawnie, zgodnie z §1.7), ale nigdy nie podaje głębokości kolejki ani co się dzieje gdy *zasubskrybowana* kolejka się zapełni. Kontrakt nakazuje `SQUARE_EVENT` per-przejście "nigdy nie łączone" — po cichu upuszczony `SQUARE_EVENT` pod backpressure korumpuje rozwiązywanie przez `SequenceInterpreter`. Przy ludzkich prędkościach szachowych przepełnienie jest mało prawdopodobne, ale diagnostyczne snapshoty 10 Hz + szybkie podniesienia to przypadek stresowy.
- **Poprawka**: Podaj głębokość kolejki i politykę pełnej kolejki w Fazie 3 §1: `SQUARE_EVENT` / `BUTTON_EVENT` nigdy nie mogą być po cichu upuszczone na żywym łączu; diagnostyczne `BOARD_SNAPSHOT` mogą być łączone/upuszczane (idempotentne — wygrywa najnowszy). Jedno zdanie to rozstrzyga.
- **Decyzja**: OCZEKUJE
