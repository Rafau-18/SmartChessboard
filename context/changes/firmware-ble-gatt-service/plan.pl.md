# Plan implementacji: Firmware BLE GATT Service (F-03)

## Przegląd

Zbudowanie pełnego **firmware gry ESP32** realizującego kontrakt BLE §1 end-to-end, na bazie istniejącego, zweryfikowanego sprzętowo skanu/debouncingu matrycy reedowej. Firmware staje się peryferium NimBLE eksponującym jeden serwis GATT (`board_event` notify + `mobile_command` write), koduje `BOARD_SNAPSHOT` / `SQUARE_EVENT` / `BUTTON_EVENT` / `DEVICE_STATUS` bajt po bajcie zgodnie z `contract-surfaces.md` §1, obsługuje `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`, dodaje dwa fizyczne przyciski potwierdzenia i przeżywa cykle rozłączenia/ponownego połączenia.

Praca jest w przeważającej mierze **addytywna**: skan ~50 Hz, debouncing 4-skanowy (~80 ms) oraz konwencja indeksowania pól `index = file + 8*rank` już istnieją i zostały zweryfikowane sprzętowo (2026-05-28). F-03 dodaje BLE, koder bajtowy, przyciski, tryb diagnostyczny i cykl życia połączenia.

Jedyne realne ryzyko — dryf kontraktu między emulatorem mobilnym a prawdziwym firmware — jest atakowane bezpośrednio: czysta logika jest wyodrębniana do `firmware/lib/`, kompilowana w środowisku testów hostowych i weryfikowana przeciw **tym samym złotym wektorom bajtów**, których używa `BoardWireCodecTest.kt` w Kotlinie, czyniąc firmware bliźniakiem emulatora bajt po bajcie.

## Analiza obecnego stanu

**Zaimplementowane dziś** (tryb diagnostyczny, do ponownego użycia bez zmian):

- Skan matrycy → bitmapa zajętości `uint64_t`, multipleksowanie aktywnych wierszy, ~50 Hz (`scan_matrix()`, [main.cpp:65-76](firmware/src/main.cpp:65); kadencja `kScanIntervalMs=20`, [main.cpp:26](firmware/src/main.cpp:26)).
- Debouncing: liczniki zgodności per-pole, `kStableScans=4` (~80 ms) zatwierdzane do bitmapy `stable` ([main.cpp:114-145](firmware/src/main.cpp:114)).
- Indeks pola: `index = col + 8*row = file + 8*rank`, a1=0…h8=63, kalibrowalny programowo ([main.cpp:31-33](firmware/src/main.cpp:31)).
- Render szeregowy (tylko debug, ortogonalny do kontraktu) ([main.cpp:79-112](firmware/src/main.cpp:79)).
- Mapa pinów ([pins.h:37-58](firmware/src/pins.h:37)): wiersze `{32,33,25,26,27,14,12,13}`, kolumny `{19,18,5,17,16,4,21,15}` — 16 pinów. `pins.h` jest jedynym źródłem prawdy tego co jest wgrane; **nie "poprawiaj"** go by zgadzał się z README.
- Build: `pio run` / `pio run -t upload` (PlatformIO + ESP-IDF, [platformio.ini:7-10](firmware/platformio.ini:7)). `sdkconfig.defaults` jest zatwierdzonym ziarnem i jest **wolny od BLE** na dziś.

**Brakujące dla F-03 (luka)** — nic z tego jeszcze nie istnieje:

- Inicjalizacja peryferium BLE + reklamowanie; jeden serwis GATT z `board_event` (notify) + `mobile_command` (write).
- Enkodery bajtowe dla czterech wiadomości board→mobile; dekoder dla trzech komend mobile→board.
- Handler zapisu dispatchujący `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`.
- Dwa fizyczne przyciski potwierdzenia (FR-FW-007) — **żaden GPIO przycisku nie jest okablowany w `pins.h`**.
- Tryb diagnostyczny (snapshot ~10 Hz) i periodyczny `DEVICE_STATUS` (~30 s).
- Cykl życia połączenia: parowanie przy pierwszym połączeniu, pojedyncze central, ponowne reklamowanie po rozłączeniu.
- Brak `firmware/lib/`, `test/`, środowiska hostowego `[env:native]`.

### Kluczowe odkrycia:

- **Format przewodowy jest już zaimplementowany i przetestowany w Kotlinie.** [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt) jest bajt-po-bajcie referencją dla wszystkich 7 wiadomości; jego komentarz nagłówkowy wskazuje firmware jako przyszłego konsumenta. Testy złotych ramek [BoardWireCodecTest.kt](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodecTest.kt) są wielojęzykową wyrocznią, którą testy hostowe firmware ponownie wykorzystują.
- **Plansza jest "głupim" sensorem.** Raportuje tylko surowe przejścia podniesienie/postawienie figury i naciśnięcia przycisków. Mobilny `SequenceInterpreter` rekonstruuje ruchy. Firmware **nie może** dodawać logiki szachowej (lessons.md: "Engine move-geometry mirrored outside `domain/chess` must be SYNC-commented" — trzecia interpretacja w firmware rozeszłaby się po cichu).
- **Kolejność bitów w snapshocie jest load-bearing** (`contract-surfaces.md` §1.3, doprecyzowano 2026-06-16): bajt `i` bit `j` (LSB-first) = pole `i*8 + j`. Pozycja startowa = `01 FF FF 00 00 00 00 FF FF`. Istniejące `uint64_t occ` już pasuje.
- **Spec behawioralny** ([EmulatedBoard.kt:84-93](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt:84)): przy (po)połączeniu wyemituj `BOARD_SNAPSHOT` a następnie `DEVICE_STATUS` i zresetuj tryb do GAME; streaming `SQUARE_EVENT` per-przejście jest obowiązkowy (nigdy nie łącz); tryb diagnostyczny dodaje snapshoty ~10 Hz; rozłączenie zatrzymuje emisję, ale skan trwa.
- **Dekodowanie jest totalne** w kodeku — puste/nieznany-tag/zły-długość/zarezerwowane-bity/poza-zakresem ramki mapują się do wyniku "Malformed", nigdy nie rzucają wyjątku. Firmware musi analogicznie **ignorować** zniekształcone/nieznane zapisy `mobile_command` zamiast faultować.
- **Oprogramowanie F-03 jest odblokowane** (roadmap 2026-06-19); tylko naprawa sprzętu matrycy reedowej pozostaje w zawieszeniu i blokuje S-09. `firmware/AGENTS.md` nadal zawiera przestarzały banner "PARKED" poprzedzający odblokowanie.

## Docelowy stan końcowy

ESP32 wgrany z tym firmware reklamuje się jako `SmartChessboard-XXXX`, akceptuje jedno sparowane central BLE i mówi protokołem §1 bajt po bajcie: wysyła burst snapshot + status przy subskrypcji, streamuje `SQUARE_EVENT` dla każdego debouncowanego przejścia reedowego, emituje `BUTTON_EVENT` przy dwóch fizycznych przyciskach, odpowiada na `SET_MODE` / `REQUEST_SNAPSHOT` / `REQUEST_STATUS`, wysyła periodyczny `DEVICE_STATUS` i ponownie reklamuje się po rozłączeniu. Trzy UUID GATT są zapisane w `contract-surfaces.md` §1.2.

**Weryfikacja:** `pio test -e native` potwierdza, że enkodery/dekoder/debounce pasują do złotych wektorów Kotlina; `pio run -e esp32dev` buduje pełny obraz urządzenia BLE; a best-effort pass na sprzęcie (na częściowo działającej planszy + tymczasowych przyciskach) potwierdza reklamowanie, połączenie/parowanie, burst przy subskrypcji, żywe zdarzenia pól/przycisków, tryb diagnostyczny i ponowne połączenie. Pełne end-to-end z aplikacją mobilną na w pełni naprawionej planszy to **S-09**, nie ta zmiana.

## Czego NIE robimy

- **Brak przepisania skanu reedowego.** Zweryfikowany sprzętowo pętla skanująca i mapowanie matrycy `pins.h` są reużywane bez zmian; piny przycisków są *addytywne*.
- **Brak logiki szachowej w firmware.** Żadnej świadomości promocji/roszady/en-passant, walidacji ruchów, śledzenia turów — plansza jest głupia; mobile re-derivuje geometrię. Żadnej trzeciej kopii geometrii ruchów (reguła SYNC z lessons.md).
- **Brak persystencji między włączeniami** (FR-FW-013) — żadnych zapisów stanu gry do flash; stan jest re-sensowany przy starcie.
- **Brak zarządzania baterią** — tylko USB dla MVP; `battery_pct` jest stałą, brak trybów uśpienia, brak UX niskiej baterii.
- **Brak OTA, LED-ów, multi-planszy, uwierzytelniania board→mobile** poza zaufaniem przy pierwszym parowaniu (kontrakt §1.8).
- **Brak negocjacji MTU** — największa ramka ma 9 bajtów, dobrze w ramach domyślnego ATT MTU 23 bajtów.
- **Brak makiety/pętli sprzężenia zwrotnego NimBLE** — granica testów poza-sprzętowych to czysta logika `lib/`; ścieżka radiowa jest weryfikowana na sprzęcie.
- **Brak wstępnej fazy reconciliacji złotych wektorów** — ufamy wektorom Kotlina i reconciliujemy tylko jeśli rozbieżność pojawi się podczas pracy nad firmware (patrz Otwarte Ryzyka).
- **Brak mobilnej adaptera BLE** (to S-09) i **brak naprawy sprzętu matrycy reedowej** (w zawieszeniu).

## Podejście implementacyjne

Cztery fazy, każda niezależnie wgrywalna/testowalna, uszeregowane tak by w pełni zautomatyzowana praca lądowała pierwsza, a ścieżka radiowa była nakładana na przetestowane jądro:

1. **Jądro protokołu + harness testów hostowych** — wyodrębnienie czystej logiki do `firmware/lib/` i postawienie środowiska testów Unity `native`, które weryfikuje złote wektory Kotlina. W pełni zautomatyzowane; zamyka ryzyko dryfu kontraktu przed jakimkolwiek kodem radiowym.
2. **Peryferium NimBLE** — przekształcenie urządzenia w reklamujące, parujące peryferium BLE z jednym serwisem GATT i cyklem życia połączenia (burst snapshot+status przy subskrypcji, ponowne reklamowanie po rozłączeniu). Mintowanie i zapisanie UUID.
3. **Zachowanie gry** — okablowanie pętli skan/debounce jako producenta na kolejkę FreeRTOS opróżnianą przez konsumenta BLE; streaming `SQUARE_EVENT`, dodanie dwóch przycisków, obsługa trzech komend zapisu, timery diagnostyczne (~10 Hz) i periodycznego statusu (~30 s).
4. **Dokumentacja + konsolidacja kontraktu** — odwieszenie `firmware/AGENTS.md`, rozwiązanie otwartych pytań `prd-firmware.md` które ta zmiana zamknięła, zanotowanie nowych przycisków w dokumentacji sprzętu.

Model współbieżności (badanie D7): pętla skan/debounce i odczyty przycisków działają jako **producent** wysyłający mały struct zdarzenia `{tag, payload}` do kolejki FreeRTOS kiedy `stable` się zmienia lub pojawia się krawędź przycisku — **nigdy** nie wywołuje API BLE. **Konsument** należący do kontekstu hosta BLE opróżnia kolejkę i wywołuje API notify, gateowany flagą CCCD-subscribe `board_event`. Periodyczny status (~30 s auto-reload `xTimer`) i timer diagnostycznych snapshotów (~100 ms, uruchamiany przez `SET_MODE→diagnostic`, zatrzymywany przez `→game`) również wysyłają na tę samą kolejkę. NFR latencji ≤100 ms ma komfortowy margines (~80 ms debounce + sub-ms hop kolejka→notify).

## Krytyczne szczegóły implementacyjne

- **Burst połączenia odpala na CCCD *subscribe*, nie na gołym connect.** Notify bez subskrybenta jest porzucane, więc "przy połączeniu wyemituj `BOARD_SNAPSHOT` a następnie `DEVICE_STATUS`" z kontraktu mapuje się na "kiedy central włącza powiadomienia na `board_event` (zapis CCCD)". Resetuj `mode = GAME` i zatrzymaj timer diagnostyczny na zdarzeniu GAP `CONNECT`; emituj burst na zdarzeniu `SUBSCRIBE`. Emulator modeluje to jako "przy połączeniu" tylko dlatego, że nie ma warstwy CCCD.
- **Budżet reklamowania 31 bajtów.** 128-bitowy UUID serwisu (16 B) plus ~16-znakowa nazwa przekracza 31-bajtowe legacy advertisement. Umieść **UUID serwisu w reklamie** i **nazwę w scan response**. Ponownie uzbrajaj reklamowanie w zdarzeniu GAP `DISCONNECT` (FR-FW-012).
- **`SQUARE_EVENT` per-przejście jest obowiązkowy — nigdy nie łącz.** Oblicz zestaw zmian z diffu zdebouncowanej bitmapy `stable` każdego skanu: `placed = stable & ~prevStable`, `lifted = prevStable & ~stable`; emituj jeden `SQUARE_EVENT` na zmienione pole (podniesienie na polu docelowym bicia jest discriminatorem, którego sam diff snapshotów nie może dostarczyć). Pomijanie/zmiana kolejności/łączenie niszczy rozwiązywanie przez `SequenceInterpreter`.
- **Zniekształcone/nieznane zapisy są ignorowane, nie faultowane.** Odzwierciedlaj totalność kodeka: nieznany tag, zła długość, zarezerwowane bity, enum poza zakresem lub zbędny payload na komendzie tag-only — wszystko staje się no-op w handlerze zapisu (zarezerwowana przestrzeń komend `0x84–0x9F` włącznie).
- **Sprawdzenie symbolu `ble_gatts_notify_custom`.** Potwierdź symbol względem faktycznie zainstalowanych nagłówków ESP-IDF/NimBLE — bardzo stary NimBLE używał `ble_gattc_notify_custom`. Rozwiąż przy buildzie, nie przez założenie.
- **`battery_pct = 100` to celowa stała USB**, w udokumentowanym zakresie 0–100 — nie prawdziwy odczyt. `firmware_version = 1.0.0`. `uptime_seconds` to **unsigned** 32-bitowe pole little-endian (sekundy z `esp_timer`/ticków).

## Faza 1: Jądro protokołu + harness testów hostowych

### Przegląd

Wyodrębnienie logiki bez sprzętu do `firmware/lib/` i dodanie środowiska testów Unity PlatformIO `native`, które weryfikuje te same złote wektory bajtów co koder Kotlina. Brak ESP-IDF, NimBLE ani GPIO w tym kodzie. Ta faza jest w pełni automatycznie weryfikowalna i redukuje ryzyko kontraktu przed jakąkolwiek pracą radiową.

### Wymagane zmiany:

#### 1. Biblioteka protokołu planszy (czysty C++)

**Plik**: `firmware/lib/board_protocol/board_protocol.h`, `firmware/lib/board_protocol/board_protocol.cpp`

**Intencja**: Przechowuje koder bajtowy używany przez firmware w czasie wykonania — enkodery dla czterech zdarzeń board→mobile i dekoder dla trzech komend mobile→board — plus pakowanie bitów snapshotów i wyprowadzanie zdarzeń pól z diffu `stable`. Czysty, samodzielny C++ używalny zarówno przez build urządzenia jak i build testów hostowych.

**Kontrakt**: Brak include ESP-IDF / Arduino / GPIO (tylko `<cstdint>`/`<cstddef>`). Pakowanie pól/snapshotów zablokowane do §1.3: bajt `i` bit `j` (LSB-first) = pole `i*8+j`; bajt snapshota `i = (occ >> (i*8)) & 0xFF`. Tagi i układy pól dokładnie jak w `BoardWireCodec.kt`. Typ ramki o stałej pojemności (max 9 bajtów + długość). Dekodowanie komend zwraca otagowany wynik z explicite przypadkiem `Malformed` (nigdy nie przerywa). Enkodery obejmują: `BOARD_SNAPSHOT` (`0x01` + 8 bajtów), `SQUARE_EVENT` (`0x02`, `(eventBits<<6)|square`, `00`=lift `01`=place), `BUTTON_EVENT` (`0x03`, `0x00` biały / `0x01` czarny), `DEVICE_STATUS` (`0x04` + bateria + major/minor/patch + uptime u32 LE). Dekoder komend obejmuje `SET_MODE` (`0x81`), `REQUEST_SNAPSHOT` (`0x82`), `REQUEST_STATUS` (`0x83`) z dokładnymi sprawdzeniami rozmiaru ramki i odrzucaniem zarezerwowanych tagów.

#### 2. Biblioteka debouncingu (czysty C++)

**Plik**: `firmware/lib/debounce/debounce.h`, `firmware/lib/debounce/debounce.cpp`

**Intencja**: Wyodrębnienie automatu stanu debouncingu z `main.cpp` do czystej, testowalnej hostowo jednostki operującej na 64-bitowych bitmapach raw/stable i licznikach zgodności per-pole — zachowanie identyczne z bieżącą pętlą (`kStableScans=4`: N zgodnych skanów zatwierdza; jakiekolwiek niezgodności resetują).

**Kontrakt**: Mały struct trzymający `agree[64]` + `stable`; `step(rawScan)` zwracający zaktualizowaną bitmapę `stable`. Brak timingu/GPIO — wywołujący jest właścicielem kadencji skanowania. `main.cpp` skonsumuje to w Fazie 3; w Fazie 1 build urządzenia nadal kompiluje się z pętlą nietkniętą (wyodrębnienie jest podłączane w Fazie 3 by ta faza była tylko addytywna dla testów).

#### 3. Środowisko testów natywnych PlatformIO

**Plik**: `firmware/platformio.ini`

**Intencja**: Dodanie środowiska hostowego `[env:native]` używającego Unity, aby `pio test -e native` budowało i uruchamiało testy `lib/` na maszynie deweloperskiej, osobno od buildu urządzenia.

**Kontrakt**: `[env:native]` z `platform = native`, `test_framework = unity`. Domyślne `test_build_src = false` nie włącza `main.cpp` do binarki hostowej; `lib/` jest pobierane przez Library Dependency Finder. Istniejący `[env:esp32dev]` jest niezmieniony.

#### 4. Testy złotych wektorów

**Plik**: `firmware/test/test_protocol/test_protocol.cpp`, `firmware/test/test_debounce/test_debounce.cpp`

**Intencja**: Weryfikacja enkoderów/dekodera przeciw **dokładnym** złotym ramkom z `BoardWireCodecTest.kt` (wielojęzykowa wyrocznia), oraz weryfikacja semantyki zatwierdzania/resetowania debouncingu.

**Kontrakt**: Wektory protokołu weryfikowane dosłownie — `SQUARE_EVENT`: `02 00`, `02 40`, `02 3F`, `02 7F`, `02 0C`, `02 5C`; `BUTTON_EVENT`: `03 00`, `03 01`; `BOARD_SNAPSHOT`: `01`+`00×8`, `01`+`FF×8`, `01 FF FF 00 00 00 00 FF FF`, `01 00 01 00 00 00 00 00 00` (a2=sq8), `01 80 00 00 00 00 00 00 00` (h1=sq7); `DEVICE_STATUS`: `04 64 01 02 03 00 00 00 00`, `04 32 02 00 01 01 02 03 04` (uptime 67 305 985), `04 00 00 00 00 FF FF FF FF` (uptime 4 294 967 295, nie −1); komendy `81 00`, `81 01`, `82`, `83`. Odrzucane przypadki zniekształcone: puste, `05 00`, `01 FF`, za duży snapshot (10 B), `02`, `02 85`, `02 C0`, `03 02`, `04 64`; komendy puste, `84`, `90`, `81 02`, `81`, `82 00`, `83 00`. Debounce: pole przełącza się na zajęte dopiero po 4 kolejnych zgodnych skanach; jeden niezgodny skan resetuje licznik.

### Kryteria sukcesu:

#### Weryfikacja automatyczna:

- Testy hostowe przechodzą: `cd firmware && pio test -e native`
- Build urządzenia nadal się kompiluje: `cd firmware && pio run -e esp32dev`
- Źródła `firmware/lib/` nie zawierają include ESP-IDF / Arduino / GPIO (grep na `driver/gpio.h`, `esp_`, `Arduino.h` nie zwraca nic pod `lib/`)

#### Weryfikacja ręczna:

- Wyrywkowe sprawdzenie czy złote ramki w `test_protocol.cpp` zgadzają się z literałami w `BoardWireCodecTest.kt` (te same bajty, oba kierunki)

**Uwaga implementacyjna**: Po tej fazie i przejściu weryfikacji automatycznej, zatrzymaj się na potwierdzenie manualne przed Fazą 2.

---

## Faza 2: Peryferium NimBLE — GATT, reklamowanie, parowanie, cykl życia

### Przegląd

Przekształcenie urządzenia w peryferium BLE: włączenie NimBLE, mintowanie i zapisanie UUID, reklamowanie, akceptacja pojedynczego sparowanego central, eksponowanie jednego serwisu GATT i implementacja cyklu życia połączenia (burst snapshot+status przy subskrypcji, ponowne reklamowanie po rozłączeniu). Streaming pól/przycisków i komendy przychodzą w Fazie 3 — ta faza dostarcza podłączalną, parującą planszę emitującą wstępny burst.

### Wymagane zmiany:

#### 1. Włączenie BLE/NimBLE w ziarnie konfiguracji

**Plik**: `firmware/sdkconfig.defaults`

**Intencja**: Włączenie Bluetooth + hosta NimBLE (tylko BLE peripheral) i umożliwienie przechowywania parowania wspieranego przez NVS, edytując zatwierdzane ziarno (nigdy wygenerowane `sdkconfig*`).

**Kontrakt**: Dodaj `CONFIG_BT_ENABLED=y` i `CONFIG_BT_NIMBLE_ENABLED=y`. Ogranicz do jednego połączenia (`CONFIG_BT_NIMBLE_MAX_CONNECTIONS=1`) i roli peripheral-only gdzie opcje istnieją. Zapewnij persystencję NVS dla parowania (domyślna NimBLE + `nvs_flash_init()` przy starcie). Zachowaj `CONFIG_ESP_CONSOLE_UART_DEFAULT=y`.

#### 2. Zapisanie UUID GATT w kontrakcie

**Plik**: `docs/reference/contract-surfaces.md` (§1.2)

**Intencja**: Wypełnienie slotu "UUID przypisane podczas implementacji firmware" §1.2 mintowaną rodziną, by mobile (S-09) i firmware dzieliły identyczne bajty. Rozwiązuje OQ-5.

**Kontrakt**: Service `787e0001-15a4-4fc9-a469-05096dbad1a1`; `board_event` (notify) `787e0002-15a4-4fc9-a469-05096dbad1a1`; `mobile_command` (write) `787e0003-15a4-4fc9-a469-05096dbad1a1`. Zaktualizuj `updated:` w frontmatter i dodaj jednolinijkowe uzasadnienie zgodnie z własną regułą kontroli zmian dokumentu.

#### 3. Serwer peryferium BLE + GATT

**Plik**: `firmware/src/ble_service.h`, `firmware/src/ble_service.cpp` (nowe), podłączone z `firmware/src/main.cpp`; `firmware/src/CMakeLists.txt` zaktualizowany o rejestrację nowego źródła i wymagania komponentów `nimble`/`nvs_flash`.

**Intencja**: Inicjalizacja NVS + hosta NimBLE, rejestracja jednego primary serwisu z dwoma charakterystykami, reklamowanie i prowadzenie cyklu życia GAP/GATT. Eksponuje małe wewnętrzne API, które producent/konsument Fazy 3 będzie wywoływał (punkt wejścia "notify tej ramki jeśli zasubskrybowany" i flagi subscribe/connection).

**Kontrakt**: Jeden primary serwis (`BLE_UUID128_INIT(service)`); `board_event` = `BLE_GATT_CHR_F_NOTIFY` (NimBLE automatycznie dodaje CCCD 0x2902), `mobile_command` = `BLE_GATT_CHR_F_WRITE`. Reklamowanie: nazwa `SmartChessboard-XXXX` (ostatnie 4 hex z `esp_read_mac(..., ESP_MAC_BT)`) w **scan response**, UUID serwisu w **reklamie**; undirected connectable. Parowanie "Just Works" (`sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT`, `sm_bonding=1`, `sm_sc=1`). Zdarzenia GAP: `CONNECT` → oznacz jako połączony, `mode=GAME`, zatrzymaj timer diagnostyczny; `SUBSCRIBE` (CCCD board_event włączone) → wyemituj `BOARD_SNAPSHOT` a następnie `DEVICE_STATUS`; `DISCONNECT` → wyczyść flagi, zatrzymaj timery, ponownie reklamuj. Notify przez `ble_gatts_notify_custom(conn, board_event_handle, om)` (zweryfikuj symbol zgodnie z Krytycznymi Szczegółami).

#### 4. Helpery źródła statusu/snapshotów

**Plik**: `firmware/src/ble_service.cpp` (lub mała jednostka `device_state`)

**Intencja**: Dostarczenie ramki snapshota bieżącej zajętości i ramki `DEVICE_STATUS` (bateria 100, fw 1.0.0, uptime z `esp_timer`) używając enkoderów Fazy 1.

**Kontrakt**: Snapshot czyta żywą zdebouncowaną bitmapę `stable`; status czyta stałe + sekundy uptime. Ramki budowane wyłącznie przez enkodery `board_protocol` (jedyne źródło prawdy).

### Kryteria sukcesu:

#### Weryfikacja automatyczna:

- Build urządzenia linkuje z NimBLE: `cd firmware && pio run -e esp32dev`
- Testy hostowe nadal przechodzą: `cd firmware && pio test -e native`
- `contract-surfaces.md` §1.2 zawiera wszystkie trzy UUID i zaktualizowaną datę `updated:`

#### Weryfikacja ręczna:

- Wgrana plansza reklamuje się jako `SmartChessboard-XXXX` (widoczne w skanerze BLE, np. nRF Connect)
- Central łączy się i paruje ("Just Works", bez PIN); ponowne połączenie nie re-paruje
- Po włączeniu powiadomień dla `board_event`, skaner otrzymuje `BOARD_SNAPSHOT` (9 B) a następnie `DEVICE_STATUS` (9 B: bateria `0x64`, fw `01 00 00`)
- Rozłączenie central zostawia planszę w trybie reklamowania (możliwość ponownego połączenia bez cyklu zasilania)

**Uwaga implementacyjna**: Po tej fazie i przejściu weryfikacji automatycznej, zatrzymaj się na potwierdzenie manualne przed Fazą 3.

---

## Faza 3: Zachowanie gry — zdarzenia, przyciski, komendy, diagnostyka

### Przegląd

Podłączenie żywego zachowania na szkielecie BLE: pętla skan/debounce i odczyty przycisków stają się producentami na kolejkę FreeRTOS opróżnianą przez konsumenta BLE; `SQUARE_EVENT` streamuje per-przejście; dwa przyciski emitują `BUTTON_EVENT`; handler zapisu dispatchuje trzy komendy; timery diagnostyczne (~10 Hz) i periodycznego statusu (~30 s) działają. To kończy kontrakt §1.

### Wymagane zmiany:

#### 1. Potok zdarzeń producent/konsument

**Plik**: `firmware/src/main.cpp`, `firmware/src/ble_service.cpp`

**Intencja**: Refaktoryzacja `app_main` tak by pętla skan/debounce (teraz używająca `lib/debounce`) działała jako producent wysyłający struct zdarzenia do kolejki FreeRTOS kiedy `stable` się zmienia; konsument w kontekście hosta BLE opróżnia kolejkę i powiadamia (gateowany flagą subscribe + połączeniem).

**Kontrakt**: Kolejka `BoardEventMsg { uint8_t bytes[9]; uint8_t len; }` (lub struct `{kind,payload}` kodowany przy opróżnianiu). Producent wysyła jeden `SQUARE_EVENT` na zmienione pole z diffu `stable` (`placed`/`lifted` jak w Krytycznych Szczegółach), nigdy nie łącząc. Producent **nigdy** nie wywołuje API BLE. Konsument wywołuje `ble_gatts_notify_custom` tylko gdy podłączony i zasubskrybowany; w przeciwnym razie porzuca (zdarzenia game-mode bez subskrypcji nie są buforowane, zgodnie z kontraktem §1.7). Istniejący szeregowy `render()` może zostać dla lokalnego debug, ale nie jest na ścieżce kontraktu.

#### 2. Fizyczne przyciski potwierdzenia

**Plik**: `firmware/src/pins.h`, `firmware/src/main.cpp`

**Intencja**: Dodanie dwóch zdebouncowanych przycisków potwierdzenia (FR-FW-007) i emitowanie `BUTTON_EVENT(white|black)` przy naciśnięciu — addytywnie, bez dotykania mapowania matrycy.

**Kontrakt**: `pins.h` zyskuje `kButtonWhitePin = GPIO_NUM_22`, `kButtonBlackPin = GPIO_NUM_23` (WROOM-32 bonded-out, zdolne do internal pull-up), skonfigurowane jako wejścia z pull-up (idle HIGH, naciśnięty = LOW). Krótki debounce przycisku (ponowne użycie idei licznika zgodności lub prosta krawędź + settle) wysyła `BUTTON_EVENT` do tej samej kolejki przy krawędzi naciśnięcia. Biały = `0x00`, czarny = `0x01`. Przyciski są gołymi zdarzeniami — bez walidacji tury.

#### 3. Handler zapisu komend mobilnych

**Plik**: `firmware/src/ble_service.cpp`

**Intencja**: Dekodowanie zapisów `mobile_command` przez `lib/board_protocol` i działanie na nich; ignorowanie czegokolwiek zniekształconego/nieznanego bez faultowania.

**Kontrakt**: W callbacku `BLE_GATT_ACCESS_OP_WRITE_CHR`, spłaszcz mbuf do bufora bajtów, wywołaj dekoder komend i dispatchuj: `SET_MODE(game)` → zatrzymaj timer diagnostyczny; `SET_MODE(diagnostic)` → uruchom timer snapshota ~100 ms; `REQUEST_SNAPSHOT` → wyślij `BOARD_SNAPSHOT`; `REQUEST_STATUS` → wyślij `DEVICE_STATUS`. Wynik `Malformed` (zła długość, nieznany/zarezerwowany tag `0x84–0x9F`, tryb poza zakresem, zbędny payload) jest no-op. Zwróć sukces ATT bez względu na wszystko (brak odpowiedzi błędu które mogłyby zmylić central).

#### 4. Timery periodycznego statusu + diagnostyki

**Plik**: `firmware/src/ble_service.cpp`

**Intencja**: Napędzanie `DEVICE_STATUS` ~30 s i diagnostycznego `BOARD_SNAPSHOT` ~10 Hz przez softwarowe timery FreeRTOS wysyłające na kolejkę zdarzeń.

**Kontrakt**: Auto-reload `xTimer` ~30 s uruchamiany przy subskrypcji (zatrzymywany przy rozłączeniu) wysyła `DEVICE_STATUS`. `xTimer` ~100 ms uruchamiany przez `SET_MODE→diagnostic` i zatrzymywany przez `SET_MODE→game` (i przy rozłączeniu) wysyła `BOARD_SNAPSHOT`. Prędkość jest celem; umiarkowana wariancja jest akceptowalna (kontrakt §1.6). Callbacki timerów tylko enqueue — nigdy nie wywołują API BLE bezpośrednio.

### Kryteria sukcesu:

#### Weryfikacja automatyczna:

- Build urządzenia kompiluje się i linkuje: `cd firmware && pio run -e esp32dev`
- Testy hostowe nadal przechodzą (debounce + protokół niezmienione): `cd firmware && pio test -e native`
- Jeśli wyprowadzanie zdarzeń pól z diffu `stable` jest w `lib/`, ma natywny test weryfikujący zdarzenia lift/place dla reprezentatywnego diffu

#### Weryfikacja ręczna (na sprzęcie, best-effort — częściowo działająca plansza + tymczasowe przyciski):

- Przesunięcie magnesu na działającym polu generuje lift a następnie place `SQUARE_EVENT` w skanerze, z prawidłowym indeksem i bitami zdarzenia (`00`=lift, `01`=place)
- Naciśnięcie tymczasowego przycisku białego/czarnego generuje `BUTTON_EVENT` `03 00` / `03 01`
- `SET_MODE(diagnostic)` (`81 01`) uruchamia stream `BOARD_SNAPSHOT` ~10 Hz; `SET_MODE(game)` (`81 00`) zatrzymuje go
- `REQUEST_SNAPSHOT` (`82`) i `REQUEST_STATUS` (`83`) generują każdy natychmiastową ramkę
- `DEVICE_STATUS` przychodzi mniej więcej co ~30 s podczas subskrypcji
- Rozłączenie → zmiana pola offline → ponowne połączenie: snapshot przy reconnect odzwierciedla zmianę offline
- Zapisanie zniekształconej/zarezerwowanej komendy (np. `84`, `81 02`) jest ignorowane — brak krachu, brak resetu

**Uwaga implementacyjna**: Po tej fazie i przejściu weryfikacji automatycznej, zatrzymaj się na potwierdzenie manualne przed Fazą 4. Zgodnie z konwencją manual-gate projektu, elementy na sprzęcie wymagające planszy mogą być zapisane do `manual-verification.md` i potwierdzone na końcu slice jeśli plansza nie jest pod ręką przy zamknięciu fazy.

---

## Faza 4: Dokumentacja + konsolidacja kontraktu

### Przegląd

Zapisanie tego co F-03 zbudował i rozwiązanie otwartych pytań, które ta zmiana zamknęła. (Write-back UUID już nastąpił w Fazie 2.)

### Wymagane zmiany:

#### 1. Odwieszenie i aktualizacja przewodnika modułu firmware

**Plik**: `firmware/AGENTS.md`

**Intencja**: Zastąpienie przestarzałego banneru "PARKED" dokładnym statusem (oprogramowanie firmware **odblokowane**; tylko naprawa sprzętu matrycy reedowej pozostaje w zawieszeniu i blokuje S-09) i udokumentowanie nowej powierzchni: peryferium NimBLE, serwis `board_event`/`mobile_command`, dwa przyciski (GPIO22/23), `firmware/lib/` i flow testów hostowych `pio test -e native`.

**Kontrakt**: Sekcja statusu przepisana; sekcje architektury/buildu rozszerzone o BLE + przyciski + lib + natywne testy. Bez zmian w uwadze o "dwóch mapach pinów" ani ostrzeżeniu o `pins.h` matrycy.

#### 2. Rozwiązanie otwartych pytań PRD firmware

**Plik**: `context/foundation/prd-firmware.md`

**Intencja**: Oznaczenie OQ które ta zmiana zamknęła: OQ-2 zasilanie = tylko USB (battery_pct stała 100), OQ-4 toolchain = ESP-IDF, OQ-5 UUID przypisane (zapisane w kontrakcie §1.2). Zanotuj wybór NimBLE i konwencję `battery_pct=100` USB.

**Kontrakt**: Dołącz datowane rozwiązania w sekcji Otwartych Pytań (i/lub notatkę Decyzje Implementacyjne); zachowaj OQ-1 (dokładny wariant) i OQ-3 (dokumentacja okablowania matrycy) jako otwarte i nieblokujące.

#### 3. Zanotowanie przycisków w dokumentacji sprzętu

**Plik**: `firmware/HARDWARE.md` (i/lub `PINOUT.md`)

**Intencja**: Zapisanie że GPIO22 (biały) / GPIO23 (czarny) są teraz pinami przycisków potwierdzenia, by przyszły czysty build je okablował.

**Kontrakt**: Krótka addytywna notatka; bez zmian w tabelach okablowania matrycy.

### Kryteria sukcesu:

#### Weryfikacja automatyczna:

- `firmware/AGENTS.md` nie prezentuje już oprogramowania firmware jako w zawieszeniu (framing "Status: PARKED / don't resume" jest zaktualizowany)
- `prd-firmware.md` OQ-2/OQ-4/OQ-5 mają datowane rozwiązania
- Brak regresji buildu/testów: `cd firmware && pio run -e esp32dev && pio test -e native`

#### Weryfikacja ręczna:

- Zaktualizowany `firmware/AGENTS.md` czyta się spójnie i pasuje do tego co zostało zbudowane (BLE, przyciski, lib, natywne testy)

**Uwaga implementacyjna**: Finalna faza — po weryfikacji automatycznej i przejrzeniu dokumentów, zmiana jest gotowa do `/10x-impl-review`.

---

## Strategia testowania

### Testy jednostkowe (host, `pio test -e native`):

- Enkodery/dekoder protokołu zweryfikowane przeciw dokładnym złotym wektorom `BoardWireCodecTest.kt` (zarówno prawidłowe ramki jak i odrzucenie zniekształconych/zarezerwowanych).
- Automat stanu debouncingu: zatwierdzanie po 4 zgodnych skanach, resetowanie przy jakiejkolwiek niezgodności.
- (Jeśli wyodrębnione) wyprowadzanie zdarzeń pól z diffu `stable`: zestawy lift/place dla reprezentatywnych diffów.

### Testy integracyjne:

- Żadnych automatyzowalnych poza sprzętem (ścieżka radiowa/GPIO). Kompilacja/linkowanie buildu urządzenia (`pio run -e esp32dev`) jest automatyczną bramą; zachowanie jest weryfikowane na sprzęcie.

### Kroki testowania ręcznego (na sprzęcie, best-effort):

1. Wgraj (`pio run -t upload`), potwierdź nazwę reklamowania w skanerze BLE.
2. Połącz + sparuj; zweryfikuj burst `BOARD_SNAPSHOT` → `DEVICE_STATUS` przy subskrypcji.
3. Przesuń magnes na działającym polu → poprawne `SQUARE_EVENT`(s).
4. Naciśnij tymczasowy przycisk → `BUTTON_EVENT`.
5. `SET_MODE(diagnostic)` → snapshoty ~10 Hz; `SET_MODE(game)` → zatrzymanie.
6. `REQUEST_SNAPSHOT` / `REQUEST_STATUS` → natychmiastowe ramki; poczekaj na periodyczny status ~30 s.
7. Rozłącz → zmień pole offline → ponownie połącz → snapshot odzwierciedla zmianę.
8. Zapisz zniekształconą/zarezerwowaną komendę → ignorowana, brak krachu.

## Uwagi dotyczące wydajności

- **NFR latencji ≤100 ms**: ~80 ms debounce + sub-ms hop kolejka→notify — komfortowy margines.
- **Diagnostyka 10 Hz / status 30 s**: cele softwarowych timerów; umiarkowana wariancja akceptowalna. Diagnostyczne snapshoty dodają się do (nie zastępują) `SQUARE_EVENT` per-przejście.
- **Heap/flash**: NimBLE jest mniejszym stosem (vs Bluedroid); pojedyncze połączenie, jeden serwis. Brak negocjacji MTU (max 9-bajtowa ramka).

## Uwagi migracyjne

- `sdkconfig.defaults` zyskuje opcje BLE/NimBLE/NVS — edytuj **ziarno**, nigdy gitignorowane wygenerowane `sdkconfig*` (regenerowane przy następnym buildzie).
- Piny przycisków `pins.h` są **addytywne**; mapowanie matrycy i celowa rozbieżność `pins.h`-vs-README pozostają niezmienione.
- Wyodrębnienie debouncingu musi być identyczne behawioralnie z bieżącą pętlą — natywny test jest strażnikiem przed regresją.

## Referencje

- Badania: `context/changes/firmware-ble-gatt-service/research.md`
- Kontrakt (zamrożony interfejs): `docs/reference/contract-surfaces.md` §1
- Referencja bajt-po-bajcie: [BoardWireCodec.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodec.kt)
- Złote wektory (wyrocznia): [BoardWireCodecTest.kt](SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/data/board/protocol/BoardWireCodecTest.kt)
- Spec behawioralny: [EmulatedBoard.kt](SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/data/board/emulator/EmulatedBoard.kt)
- Bieżący firmware: [main.cpp](firmware/src/main.cpp), [pins.h](firmware/src/pins.h), [platformio.ini](firmware/platformio.ini), [firmware/AGENTS.md](firmware/AGENTS.md)
- PRD Firmware: `context/foundation/prd-firmware.md` (FR-FW-002…013)
- Roadmap: `context/foundation/roadmap.md` (F-03)

## Progress

> Konwencja: `- [ ]` oczekujące, `- [x]` zrobione. Dołącz ` — <commit sha>` gdy krok ląduje. Nie zmieniaj nazw kroków. Patrz `references/progress-format.md`.

### Faza 1: Jądro protokołu + harness testów hostowych

#### Automatyczne

- [ ] 1.1 Testy hostowe przechodzą: `pio test -e native`
- [ ] 1.2 Build urządzenia nadal się kompiluje: `pio run -e esp32dev`
- [ ] 1.3 `firmware/lib/` nie zawiera include ESP-IDF / Arduino / GPIO

#### Ręczne

- [ ] 1.4 Wyrywkowe sprawdzenie złotych ramek w `test_protocol.cpp` zgadza się z `BoardWireCodecTest.kt`

### Faza 2: Peryferium NimBLE — GATT, reklamowanie, parowanie, cykl życia

#### Automatyczne

- [ ] 2.1 Build urządzenia linkuje z NimBLE: `pio run -e esp32dev`
- [ ] 2.2 Testy hostowe nadal przechodzą: `pio test -e native`
- [ ] 2.3 `contract-surfaces.md` §1.2 zawiera wszystkie trzy UUID + zaktualizowaną datę

#### Ręczne

- [ ] 2.4 Plansza reklamuje się jako `SmartChessboard-XXXX` (widoczne w skanerze BLE)
- [ ] 2.5 Central łączy się i paruje ("Just Works"); ponowne połączenie nie re-paruje
- [ ] 2.6 Przy subskrypcji `board_event`: odebrano `BOARD_SNAPSHOT` a następnie `DEVICE_STATUS` (bateria `0x64`, fw `01 00 00`)
- [ ] 2.7 Rozłączenie zostawia planszę w trybie reklamowania (możliwość ponownego połączenia, bez cyklu zasilania)

### Faza 3: Zachowanie gry — zdarzenia, przyciski, komendy, diagnostyka

#### Automatyczne

- [ ] 3.1 Build urządzenia kompiluje się i linkuje: `pio run -e esp32dev`
- [ ] 3.2 Testy hostowe nadal przechodzą: `pio test -e native`
- [ ] 3.3 Wyprowadzanie zdarzeń pól z diffu `stable` ma natywny test (jeśli wyodrębnione do `lib/`)

#### Ręczne

- [ ] 3.4 Magnes na działającym polu → lift a następnie place `SQUARE_EVENT`, prawidłowy indeks/bity zdarzenia
- [ ] 3.5 Tymczasowy przycisk biały/czarny → `BUTTON_EVENT` `03 00` / `03 01`
- [ ] 3.6 `SET_MODE(diagnostic)` → snapshoty ~10 Hz; `SET_MODE(game)` zatrzymuje je
- [ ] 3.7 `REQUEST_SNAPSHOT` / `REQUEST_STATUS` → natychmiastowe ramki
- [ ] 3.8 `DEVICE_STATUS` przychodzi mniej więcej co ~30 s podczas subskrypcji
- [ ] 3.9 Rozłączenie → zmiana pola offline → snapshot przy reconnect odzwierciedla zmianę
- [ ] 3.10 Zniekształcona/zarezerwowana komenda jest ignorowana — brak krachu, brak resetu

### Faza 4: Dokumentacja + konsolidacja kontraktu

#### Automatyczne

- [ ] 4.1 `firmware/AGENTS.md` nie prezentuje już oprogramowania firmware jako w zawieszeniu
- [ ] 4.2 `prd-firmware.md` OQ-2/OQ-4/OQ-5 mają datowane rozwiązania
- [ ] 4.3 Brak regresji buildu/testów: `pio run -e esp32dev && pio test -e native`

#### Ręczne

- [ ] 4.4 Zaktualizowany `firmware/AGENTS.md` czyta się spójnie i pasuje do tego co zostało zbudowane
