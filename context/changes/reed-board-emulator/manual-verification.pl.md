# Odroczona weryfikacja manualna — F-02 reed-board-emulator (PL)

> Polski odpowiednik `manual-verification.md`. Źródłem prawdy pozostaje wersja angielska oraz
> `docs/reference/contract-surfaces.md`. Wartości bajtów, nazwy plików i identyfikatory kodu są
> celowo nieprzetłumaczone — to literały techniczne, muszą zostać dokładnie takie.

**Status:** ODROCZONE. Weryfikacja automatyczna przeszła i implementacja toczyła się dalej; poniższe
kontrole „z człowiekiem w pętli" świadomie przełożono. Wróć do nich na końcu slice'a F-02 (lub przed
zarchiwizowaniem zmiany).

**Dlaczego to w ogóle musi być manualne:** testy golden-frame porównują kodek z bajtami, które
*wyprowadzono ręcznie z kontraktu*. Jeśli wektor policzono źle **i** kodek ma ten sam błąd, test
dalej świeci na zielono i niczego nie dowodzi (kodek po prostu zgadza się sam ze sobą). Tę lukę
zamyka tylko niezależne przeliczenie człowiekiem z `docs/reference/contract-surfaces.md` §1.3/§1.4.
To jedyna kontrola, której zielony pakiet testów nie zastąpi.

Po wykonaniu: zaznacz odpowiadające checkboxy w `plan.md` `## Progress` (2.3, 2.4, 3.3, 3.4) i usuń
komentarz `TODO(F-02, manual gate ...)` w `BoardWireCodecTest.kt`.

---

## [ ] 2.3 — Przelicz ręką 3–4 wektory golden i porównaj z kodekiem/testem

Źródło prawdy: `contract-surfaces.md` §1.3. Pliki z wartościami: `BoardWireCodec.kt` (kodek),
`BoardWireCodecTest.kt` (literały golden frames).

**Reguła — SQUARE_EVENT (1 bajt):** `byte = (event_code << 6) | square`, gdzie
`square = file + 8 * rank` (a=0..h=7, rank1=0..rank8=7) oraz `event_code`: `00`=lift, `01`=place.

- [ ] place na **e4** → oczekiwane `0x5C`. (e4: file 4, rank index 3 → square 28 = `011100`;
  place `01` na górze → `01 011100` = `0x5C`.)
- [ ] lift na **e2** → oczekiwane `0x0C`. (e2 = square 12; lift = `00` na górze.)

**Reguła — BOARD_SNAPSHOT (8 bajtów):** bajt `i` trzyma kwadraty `i*8 .. i*8+7`; bit `j` (LSB-first) =
square `i*8 + j`.

- [ ] **pozycja startowa** (zajęte rzędy 1,2,7,8) → oczekiwane `FF FF 00 00 00 00 FF FF`.
  (byte0=kwadraty 0–7=FF, byte1=8–15=FF, byte2–5=puste=00, byte6=48–55=FF, byte7=56–63=FF.)
- [ ] **a2** (square 8) zajęte samotnie → oczekiwane `00 01 00 00 00 00 00 00`. (bajt = 8÷8 = 1,
  bit = 8 mod 8 = 0 → tylko byte 1 = `0x01`. Przypina kolejność bajtów: zła kolejność wsadziłaby to
  do byte 0.)

**Opcjonalnie — DEVICE_STATUS uptime jest little-endian (4 bajty, najmłodszy pierwszy):**

- [ ] uptime `67_305_985` (= `0x04030201`) → bajty `01 02 03 04` (a nie `04 03 02 01`).

## [ ] 2.4 — Potwierdź, że edycje kontraktu + PRD są minimalne i datowane

Reguła change-control kontraktu kieruje każdą zmianę §1 (BLE) do obu PRD, z datą.

- [ ] `docs/reference/contract-surfaces.md` §1.3 — jedno dodane zdanie przypinające układ bajtów
  snapshotu (byte `i` bit `j` LSB-first = square `i*8+j`); frontmatter `updated: 2026-06-16`.
- [ ] `context/foundation/prd-firmware.md` — jedna datowana linia pod FR-FW-005 lustrząca
  doprecyzowanie (firmware musi pakować bajty tak samo).
- [ ] `context/foundation/prd.md` — jedna datowana linia w „Implementation Decisions" oznaczona
  **no user-facing impact** (to wewnętrzny detal wire'a; żadne zachowanie FR od niego nie zależy).

## [ ] 3.3 — Przeczytaj pipeline emisji; potwierdź, że żadne zdarzenie nie omija encode → decode

Gwarancja wierności: każde zdarzenie, które widzi konsument, przeszło rundę bajtów §1.3 — dzięki temu
weryfikacja na emulatorze przenosi się na prawdziwą planszę. Działa to tylko, jeśli istnieje
dokładnie jedna ścieżka emisji i zawsze idzie przez kodek. Plik: `EmulatedBoard.kt`.

- [ ] `_events.emit(...)` występuje w **dokładnie jednym** miejscu — wewnątrz `emitEvent(...)` — i
  nigdzie indziej (zgrepuj plik: jedyne trafienie jest w `emitEvent`). Żadna metoda sterująca nie
  emituje bezpośrednio.
- [ ] `emitEvent` zawsze robi `encodeEvent` → `decodeEvent` i emituje **zdekodowaną** wartość; wynik
  `Malformed` rzuca wyjątek (to błąd emulatora/kodeka, nigdy normalny przypadek).
- [ ] `send(...)` reaguje na **zdekodowaną** komendę (`encodeCommand` → `decodeCommand`), a nie na
  obiekt `BoardCommand` z pamięci — ta sama runda bajtów po stronie §1.4.

## [ ] 3.4 — Potwierdź, że semantyka rozłączenia zgadza się z §1.7

Prawdziwa plansza skanuje dalej, gdy łącze padnie, ale nic nie dostarcza; rozjazd ujawnia się dopiero
w snapshocie przy kolejnym połączeniu. Pliki: `EmulatedBoard.kt`, test
`EmulatedBoardTest.kt::offlineMutationSurfacesOnlyInReconnectSnapshot`.

- [ ] `lift`/`place` zmieniają `occupancy` bezwarunkowo, ale emitują `SQUARE_EVENT` tylko
  `if (isConnected)` — więc offline lift/place zmienia stan po cichu.
- [ ] `pressButton` to cichy no-op gdy rozłączony (zgubiony naciśnięcie nigdzie się nie buforuje);
  `send` gdy rozłączony rzuca `IllegalStateException` (mobile nie może pisać do martwego łącza).
- [ ] `connect()` emituje `BOARD_SNAPSHOT` (odzwierciedlający bieżące occupancy) a potem
  `DEVICE_STATUS` — więc zmiana offline między disconnect a reconnect staje się widoczna dopiero w
  tym snapshocie reconnectu, dokładnie tak, jak będzie na tym polegać reconcile-on-reconnect z S-08.

## Jak ponownie odpalić kontrole automatyczne (opcjonalnie, bez instalacji/deployu)

```bash
cd SmartChessboard
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --console=plain --no-daemon
ktlint -F
```
