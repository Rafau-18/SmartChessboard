# F-02 Reed-Board Emulator — Akceptacja manualna na koniec slice'a (PL)

> Polski odpowiednik `manual-acceptance.md`. Źródłem prawdy pozostaje wersja angielska oraz
> `docs/reference/contract-surfaces.md` §1.3–§1.7. Wartości bajtów, nazwy plików i identyfikatory
> kodu są nieprzetłumaczone — to literały techniczne.

Uruchom tę checklistę **raz, po zaimplementowaniu wszystkich czterech faz i zazielenieniu całego
pakietu na trzech targetach**. Konsoliduje pozycje manualne per-faza z `plan.md` w jeden,
end-to-endowy przebieg akceptacyjny, tak by wierność kontraktowi potwierdzić całościowo, a nie tylko
faza-po-fazie. Zaznacz pole dopiero po obejrzeniu realnego kodu/wyniku — zielony test z błędnie
wyprowadzonym wektorem niczego nie dowodzi.

Źródło prawdy dla formatu wire: `docs/reference/contract-surfaces.md` §1.3–§1.7.

## 1. Port domenowy (Faza 1)

- [ ] Publiczne typy `domain/board/` czytają się jak konsumowalny kontrakt: konsument może
      zasubskrybować `events`, sterować planszą i asertować — a ta sama powierzchnia jest sensownym
      celem dla adaptera BLE z S-09. Nie przecieka słownictwo szachowe (tylko occupancy/square, nigdy
      piece/move).
- [ ] Słownik zdarzeń/komend mapuje się 1:1 na §1.3/§1.4: cztery board→mobile
      (`BoardSnapshot`, `SquareEvent`, `ButtonEvent`, `DeviceStatus`), trzy mobile→board
      (`SetMode`, `RequestSnapshot`, `RequestStatus`). Żadnej brakującej wiadomości, żadnej nadmiarowej.

## 2. Kodek wire & golden frames (Faza 2)

- [ ] Sprawdź 3–4 wektory golden względem §1.3 **ręcznie**, w tym:
      - bajty snapshotu pozycji startowej `[0x01, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF]`
      - place na e4 → square 28 → `[0x02, 0x5C]`; lift e2 → `[0x02, 0x0C]`
- [ ] Bit-packing snapshotu jest zablokowany i spójny wszędzie: byte `i` bit `j` (LSB-first) =
      square `i*8 + j` (byte 0 = rank 1, a1 = byte 0 bit 0).
- [ ] Edycja kontraktu w `contract-surfaces.md` §1.3 to minimalne, jednozdaniowe doprecyzowanie, a
      frontmatter `updated` jest bumpnięty.
- [ ] Obie linie lustrzane w PRD obecne i datowane: `prd-firmware.md` (bit-packing snapshotu) oraz
      `prd.md` Implementation-Decisions (wskazówka „no user-facing impact").

## 3. Rdzeń emulatora (Faza 3)

- [ ] Przeczytaj pipeline emisji: każde emitowane zdarzenie naprawdę przechodzi
      typed event → `encodeEvent` → bajty → `decodeEvent` → emit. **Nie ma** skrótowej ścieżki na
      typed-event, która omija kodek.
- [ ] Semantyka rozłączenia zgodna z §1.7: offline `lift`/`place` zmieniają occupancy, ale nic nie
      emitują; rozjazd ujawnia się **tylko** w snapshocie emitowanym przy reconnekcie.
- [ ] `send` gdy rozłączony rzuca `IllegalStateException`; `pressButton` gdy rozłączony to cichy
      no-op; gwardy spójności (lift-empty / place-occupied / setOccupancy-while-connected) rzucają
      natychmiast.
- [ ] Tryb resetuje się do GAME przy każdym (re)connekcie.

## 4. Helpery scenariuszy & demo end-to-end (Faza 4)

- [ ] Przeczytaj demo end-to-end tak, jakbyś pisał S-06: subskrypcja, skryptowanie i asercje są w
      całości dostępne z publicznego API; scenariusz czyta się jak żywa dokumentacja, bez sięgania do
      wnętrza.
- [ ] Oba porządki bicia (`CAPTURED_FIRST`, `MOVER_FIRST`) i co najmniej jedna przeplatana roszada
      faktycznie pojawiają się w asertowanych, uporządkowanych strumieniach zdarzeń (warianty wymagane
      przez research są ćwiczone, nie tylko dostępne).
- [ ] Demo asertuje kształt S-08: disconnect → offline lift/place → snapshot reconnectu odzwierciedla
      zmianę offline.

## 5. Zielone cross-target (finał)

- [ ] `:shared:testAndroidHostTest` zielony
- [ ] `:shared:iosSimulatorArm64Test` zielony (Kotlin/Native to najbardziej prawdopodobny punkt
      rozjazdu dla obsługi bitów signed-`Byte`/`Long` i wirtualnego czasu coroutines-test)
- [ ] `:shared:wasmJsTest` zielony (emulator to czysty Kotlin; web nigdy nie podpina trybu
      fizycznego, ale musi się tam skompilować i wykonać)
- [ ] `ktlint -F` nie zgłasza naruszeń
