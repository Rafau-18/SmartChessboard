<!--
  NOTE FOR AGENTS: This document is intentionally written in POLISH at the
  user's explicit request (2026-07-02) — it is a hands-on diagnostic
  protocol the user will execute manually on hardware. Do not translate.
  Placeholders marked **[UZUPEŁNIJ: ...]** are to be filled by the user
  with measurement results. Once filled, this document feeds /10x-plan
  for ble-connectivity-robustness (S-10). Source: research.md §5/§7 +
  frame.md hypothesis table.
-->

# Diagnostyka BLE — protokół pomiarowy dla S-10

Cel: **przestać zgadywać**. Zanim powstanie plan implementacji, zbieramy
twarde dane: kody przyczyn rozłączeń (`reason=`), wynegocjowane parametry
połączenia, RSSI oraz reprodukcję objawu "BT off/on". Wyniki wpisuj w
placeholdery **[UZUPEŁNIJ: ...]** — po ich wypełnieniu dokument jest
wejściem do `/10x-plan ble-connectivity-robustness`.

Szacowany czas całości: ~2–3 h czystego czasu + 2× ~30 min biernego
czekania (idle-hold można robić "przy okazji").

---

## 0. Zasady ogólne (przeczytaj przed startem)

1. **Board jest single-central** — trzyma tylko JEDNO połączenie i
   połączony przestaje się rozgłaszać. Testuj jednym urządzeniem naraz;
   przed każdym testem upewnij się, że aplikacja SmartChessboard jest
   **ubita** (force-close) na wszystkich pozostałych urządzeniach.
2. **NIE klikaj "Bond"/"Sparuj" w nRF Connect** (poza eksperymentem E7,
   który tylko *odczytuje* stan). Firmware ma wciąż uzbrojone bondowanie
   (`sm_bonding=1` + NVS persist) — ręczne sparowanie utworzy LTK i może
   ponownie otworzyć ścieżkę desyncu `reason=531`.
3. **Monitor szeregowy ma być otwarty przez cały czas testów.** Board i
   tak zasilasz z USB — podłącz go do komputera i w drugim oknie trzymaj:

   ```bash
   cd firmware && pio device monitor -b 115200
   ```

   Firmware loguje przy każdym rozłączeniu linię z `reason=...`
   (`ble_service.cpp:296`) — to najważniejsza pojedyncza dana w całej
   diagnostyce. Loguj do pliku lub rób zrzuty ekranu z timestampem.
4. **Notuj timestampy zdarzeń** (wystarczy hh:mm) — pozwala skorelować
   log firmware'u z tym, co widziałeś na urządzeniu.
5. Potrzebna apka: **nRF Connect for Mobile** (Nordic Semiconductor) —
   zainstaluj na tablecie, telefonie Android i iPhonie.

### Urządzenia testowe

| Rola | Model + wersja OS |
| --- | --- |
| Problematyczny tablet Android | **[UZUPEŁNIJ: MODEL + WERSJA ANDROIDA]** |
| Nowoczesny telefon Android | **[UZUPEŁNIJ: MODEL + WERSJA ANDROIDA]** |
| iPhone | **[UZUPEŁNIJ: MODEL + WERSJA iOS]** |

### Ściąga: kody `reason=` w logu firmware'u

| Log firmware (NimBLE) | Kod HCI | Znaczenie | Wskazuje na |
| --- | --- | --- | --- |
| `reason=520` | 0x08 | **Supervision timeout** — link umarł z ciszy | parametry połączenia / RF (zasięg, antena, zakłócenia) |
| `reason=531` | 0x13 | **Remote user terminated** — central CELOWO zerwał | bonding/stan OS centrala (to był objaw z iOS w S-09) |
| `reason=573` | 0x3D | **MIC failure** — błąd kryptograficzny na łączu | desync kluczy + słabe RF |
| `reason=534` | 0x16 | Local host terminated — zerwał board | firmware |
| inny | — | zanotuj dokładną wartość | — |

(NimBLE loguje `reason` jako 512 + kod HCI, stąd 520 = 0x08 itd.)

### Jak odczytać RSSI i parametry połączenia w nRF Connect (Android)

- **RSSI przed połączeniem**: zakładka SCANNER — wartość dBm przy
  urządzeniu na liście (odświeża się na żywo).
- **RSSI po połączeniu**: w widoku połączonego urządzenia menu `⋮` →
  *Read RSSI* (lub wykres RSSI, zależnie od wersji).
- **Parametry połączenia**: w widoku urządzenia otwórz **Log**
  (zakładka/ikonka po prawej). Szukaj linii typu
  `Connection parameters updated (interval: XX,X ms, latency: X, timeout: XXXX ms)`.
  **`timeout` to supervision timeout — najbardziej diagnostyczna liczba
  w całym badaniu.** Zapisuj każdą taką linię.
- Na iOS nRF Connect nie pokazuje parametrów — z iPhone'a zbieramy tylko
  zachowanie (trzyma/zrywa) i log firmware'u.

Progi interpretacji RSSI (na połączonym linku, w pozycji grania):
**lepiej niż −70 dBm = OK; −70…−80 dBm = margines topnieje; gorzej niż
−80 dBm = łącze głoduje** (RF jest podejrzanym #1).

---

## E1 — nRF Connect idle-hold 30 min (bez aplikacji) — NAJWAŻNIEJSZY TEST

**Cel:** jednym eksperymentem rozdzielić winę aplikacji od winy
firmware/RF. nRF Connect to "goły" klient GATT — jeśli ON trzyma link
tam, gdzie aplikacja go gubi, problem jest w aplikacji; jeśli zrywa tak
samo — problem leży niżej.

**Kroki (wykonaj osobno: tablet, potem iPhone; opcjonalnie telefon):**

1. Ubij aplikację SmartChessboard na wszystkich urządzeniach.
2. Otwórz monitor szeregowy (patrz §0 pkt 3).
3. nRF Connect → SCANNER → znajdź `SmartChessboard-DA3A` → zanotuj RSSI
   ze skanera → **CONNECT**.
4. Po połączeniu rozwiń serwis (custom UUID), znajdź charakterystykę z
   właściwością **NOTIFY** (`board_event`) i kliknij ikonę subskrypcji
   (potrójna strzałka w dół). NIE klikaj Bond.
5. Zweryfikuj żywotność: podnieś i odstaw jedną figurę na planszy —
   w nRF Connect powinna mignąć notyfikacja.
6. Odczytaj z **Logu** nRF Connect linię `Connection parameters updated`
   i zapisz interval / latency / timeout.
7. Zostaw urządzenie na **30+ minut** w odległości grania (~1–2 m od
   boardu), ekran może zgasnąć naturalnie. Nic nie rób.
8. Po 30 min sprawdź: czy nRF Connect nadal pokazuje CONNECTED? Przejrzyj
   log nRF Connect i log firmware'u pod kątem rozłączeń.

**Wyniki:**

- Tablet — parametry połączenia (interval / latency / **timeout**):
  **[UZUPEŁNIJ: np. 48,75 ms / 0 / 5000 ms]**
- Tablet — utrzymał 30 min idle? Ile zrywek, jakie `reason=`:
  **[UZUPEŁNIJ: TAK trzymał / NIE — N zrywek, reason=...]**
- Tablet — RSSI (skaner + po połączeniu): **[UZUPEŁNIJ: dBm]**
- iPhone — utrzymał 30 min idle? `reason=`:
  **[UZUPEŁNIJ]**
- Telefon Android (opcjonalnie): **[UZUPEŁNIJ / POMINIĘTO]**

**Interpretacja:** tablet trzyma w nRF Connect, a w aplikacji zrywał →
wina aplikacji/integracji. Tablet zrywa też w nRF Connect → firmware/RF
(idź uważnie przez E2/E3); `reason=520` przy zrywce = supervision
timeout → parametry+RF; `reason=531` = coś inicjuje parowanie → E7.

---

## E2 — Izolacja RF: 0 cm vs dystans grania

**Cel:** sprawdzić, czy problem znika przy maksymalnym sygnale — jeśli
tak, to problem MARGINESU RF, nie protokołu.

**Kroki (tablet — urządzenie z najgorszymi objawami):**

1. Jak w E1 połącz nRF Connect z boardem, subskrybuj notyfikacje.
2. Faza A: połóż tablet **bezpośrednio na/przy boardzie (0 cm)** na
   10 min. Ruszaj czasem figurą, żeby płynęły notyfikacje.
3. Faza B: odsuń tablet na **2–5 m** (typowa pozycja przy grze) na
   10 min, znów okazjonalnie ruszaj figurą.
4. Notuj RSSI w obu fazach i wszystkie zrywki + `reason=`.

**Wyniki:**

- 0 cm — RSSI: **[UZUPEŁNIJ: dBm]**, zrywki: **[UZUPEŁNIJ: BRAK / N × reason=...]**
- 2–5 m — RSSI: **[UZUPEŁNIJ: dBm]**, zrywki: **[UZUPEŁNIJ]**

**Interpretacja:** stabilnie przy 0 cm + zrywki na dystansie = margines
RF (antena na breadboardzie / moc TX / okablowanie) — potwierdza
hipotezę RF. Zrywa nawet przy 0 cm = to NIE jest zasięg; wróć do
parametrów połączenia / centrala.

---

## E3 — Izolacja RF: goły devkit vs okablowany harness *(opcjonalny — wymaga drugiego ESP32 DevKit)*

**Cel:** rozstrzygnąć, czy to okablowanie matrycy wokół anteny psuje łącze.

**Kroki:**

1. Weź zapasowy DevKit **bez niczego podpiętego** i wgraj ten sam
   firmware: `cd firmware && pio run -t upload` (board zgłosi się z inną
   końcówką nazwy — po MAC).
2. Powtórz na tablecie test E2-faza-B (10 min na 2–5 m) na gołym boardzie.
3. Porównaj RSSI i liczbę zrywek z wynikami okablowanego boardu.

**Wyniki:**

- Goły board — RSSI na dystansie: **[UZUPEŁNIJ / POMINIĘTO]**,
  zrywki: **[UZUPEŁNIJ]**
- Różnica vs harnessowany: **[UZUPEŁNIJ: np. +8 dBm lepiej, zero zrywek]**

**Interpretacja:** goły stabilny + harnessowany zrywa = okablowanie/
breadboard potwierdzone jako eroder marginesu (fix = higiena RF, nie kod).

---

## E4 — Reprodukcja objawu głównego: BT OFF → ON na tablecie (z aplikacją)

**Cel:** udokumentować krok po kroku Twój najdotkliwszy objaw — zawodne
wznowienie po restarcie Bluetootha. Frame przewiduje (na podstawie kodu):
brak auto-odzyskania; przycisk "Reconnect" dobija się do martwego
obiektu; pomaga dopiero powrót do ekranu połączenia lub restart apki.
Sprawdź, czy rzeczywistość się zgadza.

**Kroki (tablet; powtórz cały cykl 3×, bo objaw bywa niedeterministyczny):**

1. Otwórz aplikację → połącz z boardem → rozpocznij partię fizyczną →
   wykonaj 2–3 ruchy.
2. Zciągnij pasek systemowy i **wyłącz Bluetooth**. Obserwuj aplikację
   przez ~60 s. Zanotuj DOKŁADNIE co pokazuje UI (banner? jaki tekst?).
3. **Włącz Bluetooth** z powrotem. Przez 2 minuty NIE dotykaj aplikacji.
   Czy połączenie wróciło samo?
4. Jeśli nie — kliknij **Reconnect** na bannerze. Czy zadziałał? Po jakim
   czasie? Ile prób?
5. Jeśli nie — wyjdź do ekranu połączenia (lub Historia → wznowienie
   partii). Czy skan znajduje board? Czy connect przechodzi?
6. Jeśli nie — ubij aplikację i uruchom ponownie. Czy teraz działa?
7. Zanotuj ŁĄCZNY czas i liczbę kroków od włączenia BT do wznowienia gry.
8. Równolegle patrz w log firmware'u: czy board w ogóle widzi próby
   połączenia (linie connect/disconnect)?

**Wyniki (3 przebiegi):**

- Co pokazało UI po wyłączeniu BT: **[UZUPEŁNIJ]**
- Auto-odzyskanie po włączeniu BT (krok 3): **[UZUPEŁNIJ: TAK/NIE]**
- Przycisk Reconnect (krok 4): **[UZUPEŁNIJ: działa / kręci się i pada / nic]**
- Ekran połączenia + rescan (krok 5): **[UZUPEŁNIJ]**
- Restart aplikacji (krok 6): **[UZUPEŁNIJ: pomógł / niepotrzebny / nie pomógł]**
- Łączny czas do wznowienia gry: **[UZUPEŁNIJ: ~s/min]**
- Log firmware'u podczas prób: **[UZUPEŁNIJ: board widział próby? reason=?]**

**Interpretacja:** ten eksperyment nie szuka winnego (kod już obejrzany —
ścieżka jest nieobsłużona) — on dokumentuje DOKŁADNY kształt awarii,
żeby plan naprawy flow miał kryterium akceptacji ("po BT off/on gra
wznawia się sama / jednym tapnięciem w ≤X s").

---

## E5 — Matryca urządzeń z aplikacją (idle + aktywna gra)

**Cel:** rozstrzygnąć, czy zrywki "w trakcie gry" są specyficzne dla
tabletu, dla Androida, czy uniwersalne.

**Kroki (dla KAŻDEGO z 3 urządzeń osobno):**

1. Aplikacja → połącz → partia fizyczna → graj aktywnie ~10 min.
2. Potem zostaw połączone bez ruchu na ~15 min (ekran zostaje włączony —
   aplikacja sama trzyma keep-awake).
3. Notuj każdą zrywkę: kiedy (gra/idle), co pokazało UI, `reason=` z
   logu firmware'u, czy auto-reconnect zadziałał i po jakim czasie.

**Wyniki:**

| Urządzenie | Zrywki w grze | Zrywki idle | reason= | Auto-reconnect (czas) |
| --- | --- | --- | --- | --- |
| Tablet | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** |
| Telefon Android | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** |
| iPhone | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** | **[UZUPEŁNIJ]** |

**Interpretacja:** tylko tablet zrywa → central (radio/stack tabletu);
oba Androidy → parametry/power-management Androida; wszystkie →
firmware/RF.

---

## E6 — Idle z wygaszonym ekranem / w tle (tablet + iPhone)

**Cel:** sprawdzić wpływ oszczędzania energii (Doze / background
throttling) — aplikacja trzyma ekran tylko NA ekranie gry.

**Kroki:**

1. Połączony w aplikacji, partia otwarta → wciśnij power (zgaś ekran) na
   5 min → obudź. Połączenie żyje? Zanotuj + `reason=` jeśli padło.
2. Wariant: zamiast gasić ekran zminimalizuj aplikację (home) na 5 min →
   wróć. Zanotuj.

**Wyniki:**

- Tablet, ekran zgaszony 5 min: **[UZUPEŁNIJ]**
- Tablet, aplikacja w tle 5 min: **[UZUPEŁNIJ]**
- iPhone, analogicznie: **[UZUPEŁNIJ]**

---

## E7 — Kontrola stanu bondów (czy "plaintext" naprawdę jest plaintext?)

**Cel:** empirycznie sprawdzić hipotezę połowicznego rewertu — firmware
wciąż MOŻE utworzyć i zapisać bond, jeśli cokolwiek zainicjuje parowanie.

**Kroki (nic nie klikamy w "Bond" — tylko odczyt!):**

1. Tablet: Ustawienia → Bluetooth / Połączone urządzenia → czy
   `SmartChessboard-DA3A` figuruje jako SPAROWANE? Zanotuj.
2. Tablet: nRF Connect → zakładka **BONDED** → czy board tam jest?
3. Telefon Android: to samo.
4. iPhone: Ustawienia → Bluetooth → Moje urządzenia → czy board tam jest?
5. W nRF Connect po połączeniu z boardem sprawdź etykietę przy urządzeniu
   (powinno być "NOT BONDED").

**Wyniki:**

- Tablet — bond w systemie / w nRF Connect: **[UZUPEŁNIJ: JEST/BRAK]**
- Telefon — bond: **[UZUPEŁNIJ]**
- iPhone — wpis w Moje urządzenia: **[UZUPEŁNIJ]**

**Interpretacja:** jakikolwiek bond istniejący dziś = hipoteza
"uzbrojonego bondowania" jest ŻYWA (nie tylko latentna) i dokończenie
rewertu (`sm_bonding=0`) rośnie w priorytecie. Brak bondów = hazard
tylko latentny, ale nadal do domknięcia w planie.

---

## Drzewo decyzyjne (wypełnij na końcu)

```
Czy nRF Connect trzyma 30 min idle na tablecie (E1)?
├─ NIE → wina firmware/RF (aplikacja OCZYSZCZONA z zarzutu zrywek)
│        ├─ reason=520 / zrywki rosną z dystansem (E2) / słabe RSSI
│        │     → supervision timeout + margines RF
│        ├─ reason=531 → coś tworzy bond (sprawdź E7)
│        └─ goły board trzyma, harness zrywa (E3) → okablowanie/RF
└─ TAK → aplikacja zrywa tam, gdzie nRF Connect trzyma
         → wina integracji app/central (lifecycle Kable, flow)
```

---

## Wnioski końcowe (wypełnij po wszystkich eksperymentach)

1. Dominująca przyczyna zrywek NA TABLECIE wg drzewa:
   **[UZUPEŁNIJ: APLIKACJA / PARAMETRY+RF / RF-OKABLOWANIE / CENTRAL-TABLET / BONDING]**
2. Czy zrywki w trakcie gry występują poza tabletem:
   **[UZUPEŁNIJ: TAK, na ... / NIE, tylko tablet]**
3. Supervision timeout narzucany przez tablet (z E1):
   **[UZUPEŁNIJ: ms]** — poniżej ~2000 ms to mocny argument za
   negocjacją parametrów po stronie firmware'u.
4. Najgorsze zmierzone RSSI w pozycji grania:
   **[UZUPEŁNIJ: dBm, urządzenie]**
5. Ścieżka BT off/on (E4) — potwierdzone zachowanie i minimalny działający
   krok odzyskania: **[UZUPEŁNIJ]**
6. Stan bondów (E7): **[UZUPEŁNIJ: czyste wszędzie / bond na ...]**
7. Inne obserwacje / anomalie: **[UZUPEŁNIJ / BRAK]**

> Po wypełnieniu: uruchom `/10x-plan ble-connectivity-robustness` — plan
> ma czytać `frame.md` + ten dokument i porządkować pracę wg tego, co
> POMIARY potwierdziły (a nie wg hipotez z research.md).
