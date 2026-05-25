---
project: "Smart Chessboard"
context_type: greenfield
created: 2026-05-25
updated: 2026-05-26
checkpoint:
  current_phase: 8
  phases_completed: [1, 2, 3, 4, 5, 6, 7]
  gray_areas_resolved:
    - topic: "kategoria bólu"
      decision: "dane uwięzione w fizycznym świecie + brak analizy + tarcie ręcznego zapisu"
    - topic: "insight motywujący projekt"
      decision: "komercyjne smart chessboardy są za drogie i zamknięte dla tego use-case; autor ma już prototyp sprzętu"
    - topic: "zakres persony"
      decision: "multi-user — autor + grono znajomych, każdy z własnym profilem i historią"
    - topic: "model uwierzytelniania"
      decision: "konta użytkowników (mechanizm odracza się do tech-stack)"
    - topic: "role"
      decision: "flat — wszyscy zalogowani równi"
    - topic: "sign-up"
      decision: "open registration via external identity provider (OAuth) — initially 'closed beta with invitations' in Phase 2, revised post-Phase-7 to open OAuth-based registration; provider deferred to tech-stack"
  frs_drafted: 23
  # FR rev history: +1 FR-011 (zegar szachowy, +Runda 3); -1 FR-018 (wstrzymanie/wznowienie redundantne, -Runda 5); +1 FR-022 (auto-detect powrotu pozycji, post-Phase-7); +1 FR-023 (network-loss recovery, post-Phase-8); ostateczna liczba: 23
  quality_check_status: accepted
---

# Shape notes — Smart Chessboard

Notatki z sesji /10x-shape. Treść porządkuje się pod sekcje PRD, ale to NIE jest jeszcze PRD.

## Vision & Problem Statement

Szachista amator gra w szachy na fizycznej, drewnianej szachownicy ze znajomymi w domu. Partie rozegrane w ten sposób znikają w chwili zakończenia — nie ma ich w żadnym zapisie, nie da się ich później przeanalizować, ani wrócić do konkretnej pozycji. Próby ręcznego notowania ruchów na kartce zabijają tempo i przyjemność z gry, więc w praktyce nikt tego nie robi.

Insight: komercyjne "smart chessboardy" (Square Off, Chessnut, DGT) rozwiązują ten problem, ale są drogie i zamknięte — nie pasują do use-case'u autora i jego znajomych. Autor zbudował już własny prototyp fizycznej szachownicy z matrycą kontaktronów i ESP32 podczas studiów. Projekt to nadbudowa software'owa nad istniejącym sprzętem, krojona pod konkretne grono użytkowników, nie pod rynek masowy.

## User & Persona

**Primary persona — szachista amator.** Autor projektu i jego grono znajomych. Grają w szachy fizyczne dla przyjemności (nie turniejowo), regularnie w domu. Każdy ma własne konto, własny profil i własną historię partii — chcą widzieć swoje statystyki i wracać do swoich partii, nie do "wspólnej puli".

## Access Control

Każdy gracz posiada własne konto i loguje się do aplikacji, by mieć przypisaną historię partii i statystyki.

- **Model:** flat — wszyscy zalogowani użytkownicy mają te same uprawnienia. Brak ról admin/user/guest w MVP.
- **Sign-up:** otwarty — każdy może założyć konto bez zaproszenia. Założenie: niska widoczność projektu w MVP ("nikt nie wie o produkcie") + krótka ścieżka rejestracji jest wystarczającym balansem dla closed-circle persony. Brak mechanizmu zaproszeń = brak osobnego UI i osobnego kodu w MVP.
- **Mechanizm uwierzytelniania:** logowanie i rejestracja przez **external identity provider (OAuth)** — pojedynczy provider, ten sam dla rejestracji i logowania. Brak własnego password hashing, brak własnego flow reset hasła, brak własnego email-verification. Konkretny provider (Google / GitHub / Apple / Auth0 / Supabase Auth / inne) odracza się do tech-stack-selection.
- **Anonymous access:** brak. Niezalogowany użytkownik nie ma dostępu do żadnych widoków partii ani analiz w MVP.

## Success Criteria

### Primary
- Użytkownik może rozegrać pełną partię szachów w jednym z dwóch trybów: (a) **gra cyfrowa** w trybie pass-and-play na ekranie urządzenia mobilnego (Android lub iOS), (b) **gra fizyczna** na podłączonej do aplikacji fizycznej szachownicy z matrycą kontaktronów (ESP32, przycisk zatwierdzania ruchu). W obu trybach partia jest automatycznie zapisywana w standardowych formatach (PGN i FEN po każdym ruchu). Użytkownik może później wrócić do listy swoich partii i przejść wybraną partię ruch po ruchu z odtworzeniem każdej pozycji.

### Secondary
- Po zakończeniu partii użytkownik może zobaczyć ocenę pozycji (np. pasek oceny / wykres oceny Stockfisha) dla wybranych pozycji partii — analiza pobierana z zewnętrznego, darmowego źródła oceny pozycji szachowych.

### Guardrails
- **Legalność ruchu:** aplikacja NIGDY nie zapisuje nielegalnego ruchu. Każdy ruch jest walidowany wg pełnych reguł szachowych — ruch związanej figury, ruch nie wychodzący z szacha, niedozwolony ruch króla, błędna roszada, niedozwolone bicie w przelocie, niedozwolona promocja — przed zapisem. Walidacja obowiązuje w obu trybach (cyfrowym i fizycznym). **Uwaga:** automatyczne *wykrywanie końca partii* (mat / pat / 3x powtórzenie / 50 ruchów) jest poza MVP — w MVP użytkownik manualnie oznacza koniec partii i wynik (patrz FR).
- **Trwałość partii:** raz zapisana partia nie znika z konta użytkownika. Awaria aplikacji w trakcie trwającej partii nie powoduje utraty ruchów wykonanych do momentu awarii. Guardrail pokrywa awarię aplikacji; utrata połączenia z fizyczną szachownicą obsługiwana w MVP minimalnie (ostatni zapisany ruch zachowany dzięki FR-015), pełne wstrzymanie + auto-reconnect bez utraty stanu wyodrębnione jako nice-to-have FR-023.
- **Reaktywność ruchu:** zaakceptowany ruch (dotyk ekranu w trybie cyfrowym lub naciśnięcie przycisku zatwierdzającego w trybie fizycznym) pojawia się jako wykonany na ekranie urządzenia w czasie nieprzekraczającym 500 ms od interakcji.
- **Brak cichej korupcji detekcji fizycznej:** aplikacja nie zapisuje błędnie zinterpretowanego ruchu po cichu. Każdy zatwierdzony przyciskiem stan kończy się jedną z dwóch obserwowalnych dla użytkownika reakcji: (a) ruch jest poprawnie rozpoznany jako konkretny legalny ruch szachowy i zapisany, albo (b) aplikacja widocznie zgłasza problem detekcji (brak odpowiadającego legalnego ruchu, niejednoznaczność, wykryta inkonsystencja stanu matrycy) i wstrzymuje partię do manualnej korekty. **Akceptujemy hobbystyczny charakter sprzętu:** matryca kontaktronów może mieć fałszywe trafienia (magnes "zapala" zbędne pole) lub niewykrycia (figura stoi, ale pole nie sygnalizuje). Dlatego doświadczenie nie jest w pełni autonomiczne — aplikacja oferuje wsparcie do manualnej korekty (diagnostyczny live-widok matrycy FR-012, jasny komunikat błędu z możliwością ponowienia FR-013, opcjonalny auto-detect powrotu do pozycji FR-022). Lepiej "częściowo autonomicznie + pomoc człowieka" niż "100% autonomicznie albo nic".

## Timeline acknowledgment

Acknowledged on 2026-05-25: MVP wymaga ponad 12 tygodni regularnej, intensywnej pracy. Użytkownik jawnie zaakceptował koszt: długoterminowy projekt edukacyjny, sustained-effort cost przyjęty na wejściu, z buforem na naukę nowych technologii. `mvp_weeks: 12` (rewizja post-Phase-8: bez hard capa — 12 tygodni jako realistyczny budżet z buforem na nice-to-have, nie sztywny deadline). Zakres MVP: pełen scope cyfrowy (Android + iOS, multi-user, rejestracja, replay UI) PLUS warstwa sprzętowa (ESP32 z matrycą kontaktronów, przycisk zatwierdzania, WiFiManager captive portal, diagnostyka stanu szachownicy, symulator sprzętu na potrzeby testów i CI/CD). Wszystkie 4 nice-to-have FR (FR-016, FR-017, FR-018, FR-022) plus nowy FR-023 zostają w scope jako opcjonalne — wejdą do MVP jeśli budżet czasu pozwoli, w przeciwnym razie spadają do post-MVP roadmapy bez renegocjacji must-have.

## Functional Requirements

### Konta i logowanie
- FR-001: Nowy użytkownik może utworzyć konto poprzez zalogowanie się przez external identity provider (OAuth single-sign-on). Rejestracja jest otwarta — bez zaproszenia, bez kodu, bez weryfikacji email. Priority: must-have
  > Socrates: Counter-argument considered in Round 1: "ręczne zakładanie kont / kod zaproszenia". Resolution: initially kept ("zaproszenia jako must-have"), then REVISED post-Phase-7: open registration via OAuth zastępuje closed beta. Powody rewizji: (1) niska widoczność projektu w MVP ("nikt nie wie") jest wystarczającym ograniczeniem; (2) OAuth eliminuje rejestracyjne UI + password management; (3) cel edukacyjny autora obejmuje praktyczne wdrożenie OAuth.

- FR-002: Użytkownik może się zalogować do swojego konta poprzez tego samego external identity providera (OAuth), z którego korzystał przy zakładaniu konta. Priority: must-have
  > Socrates: Counter-argument considered: "PIN + select-from-list / magic-link". Resolution: kept; pełne logowanie przez OAuth zostaje (potwierdzone post-Phase-7 razem z FR-001), konkretny provider w tech-stack.

- FR-003: Użytkownik może się wylogować. Priority: must-have
  > Socrates: Counter-argument considered: "wylogowanie martwą funkcją na jednoosobowym telefonie". Resolution: kept; podstawowy auth feature, brak byłby dziwny nawet jeśli rzadko używany.

### Tworzenie partii
- FR-004: Zalogowany użytkownik może utworzyć nową partię, wskazując tryb gry (cyfrowy lub fizyczny) oraz kto gra białymi i czarnymi. Priority: must-have
  > Socrates: Counter-argument considered: "tryb auto-detect po sparowanym sprzęcie / kolor białego automatycznie". Resolution: kept; użytkownik explicite kontroluje tryb i kolory.

### Rozgrywka cyfrowa (pass-and-play)
- FR-005: Użytkownik wykonuje ruchy na ekranowej szachownicy interaktywnie (przeciągając lub klikając pole-pole). Priority: must-have
  > Socrates: Counter-argument considered: "tylko drag-and-drop bez tap-tap / notacja algebraiczna". Resolution: kept; standardowy UX szachownicy, konkretny wybór drag/tap odracza się do implementacji.

- FR-006: Aplikacja waliduje legalność każdego ruchu przed jego wykonaniem zgodnie z pełnymi regułami szachowymi (ruch związanej figury, wyjście z szacha, roszada, en passant). Priority: must-have
  > Socrates: Counter-argument considered: "gracze sami się walidują / tylko podstawowa walidacja bez pinning". Resolution: kept; spina się z guardrailem "Legalność ruchu" z Fazy 3, niezbywalne.

- FR-007: Przy promocji piona aplikacja wyświetla pop-up wyboru figury (hetman / wieża / skoczek / goniec). Priority: must-have
  > Socrates: Counter-argument considered: "auto-promocja do hetmana / gesture na figurę". Resolution: kept; standard z Lichess/Chess.com.

### Rozgrywka fizyczna (sprzęt)
- FR-008: Użytkownik konfiguruje Wi-Fi szachownicy fizycznej przez captive portal (WiFiManager) — bez wpisywania credentials do firmware. Priority: must-have
  > Socrates: Counter-argument considered: "hardcoded credentials / BLE handshake". Resolution: kept; notatka jawnie wymaga (credentials nie w kodzie); BLE jest jawnie poza MVP.

- FR-009: Aplikacja nawiązuje połączenie z fizyczną szachownicą w lokalnej sieci. W MVP — jedna domyślna szachownica per użytkownik; parowanie z wieloma szachownicami odracza się do post-MVP. Priority: must-have
  > Socrates: Counter-argument considered and ACCEPTED: "tylko jedna plansza per użytkownik — niepotrzebne wybieranie z listy". FR przepisany: zamiast "parowania" — proste połączenie z jedną domyślną szachownicą; multi-board pairing wychodzi do post-MVP.

- FR-010: Sprzęt monitoruje stan matrycy kontaktronów w czasie ciągłym i wysyła zmiany (delta) do aplikacji. Aplikacja śledzi sekwencję podniesień i odłożeń figur od ostatniego zatwierdzenia ruchu. Po naciśnięciu przycisku zatwierdzającego aplikacja interpretuje całą zarejestrowaną sekwencję jako jeden konkretny legalny ruch szachowy — w tym poprawnie obsługując bicia (np. "figura podniesiona z pola A → figura podniesiona z pola B → figura odłożona na pole B" rozpoznane jako bicie AxB) oraz roszadę (dwie figury podniesione, dwie odłożone na inne pola). Priority: must-have
  > Socrates: Counter-argument considered and ACCEPTED: "continuous detection bez przycisku to chaos / przycisk per gracz (zegar)". FR przepisany: continuous monitoring stanu matrycy + sekwencja podniesień/odłożeń jako wejście, ZAMIAST snapshotu delta (snapshot nie wystarczy dla bić — bicie z perspektywy delta wygląda jak zniknięcie figury). Zegar (per-player przycisk) wyodrębniony jako osobny FR-011.

- FR-011: Sprzęt udostępnia dwa przyciski zatwierdzające ruch — po jednym dla każdego gracza, fizycznie zorganizowane w formie zegara szachowego. Aplikacja rozróżnia, który przycisk został naciśnięty, i przypisuje zatwierdzony ruch do koloru gracza po tej stronie zegara. Priority: must-have
  > Socrates: Nowy FR powstały z counter-argumentu FR-010. Notatka wcześniej wspominała "przycisk/przyciski (np. w formie zegara szachowego)" — Socrates uświadomił, że bez dwóch przycisków nie wiadomo, czyj ruch zatwierdzono (jeden gracz może wcisnąć za drugiego). Must-have.

- FR-012: Użytkownik widzi w aplikacji diagnostyczny podgląd stanu matrycy kontaktronów (które pola wykrywają figurę / magnes). Priority: must-have
  > Socrates: Counter-argument considered and REJECTED: "diagnostyka tylko w logach firmware / tylko przy błędach". Reason: projekt hobbystyczny z nieidealnym sprzętem, wady kontaktronów wymagają stałego wsparcia debugowania — diagnostyka jest absolute must-have.

- FR-013: Aplikacja sygnalizuje błąd, gdy zarejestrowana sekwencja stanów matrycy po naciśnięciu przycisku NIE odpowiada żadnemu legalnemu ruchowi z aktualnej pozycji; partia jest wstrzymana, użytkownik proszony o manualne przywrócenie poprzedniej pozycji figur (z pomocą widoku diagnostycznego FR-012) i ponowne naciśnięcie przycisku zatwierdzającego, gdy plansza będzie ponownie zgodna z oczekiwanym stanem. Aplikacja NIE zapisuje takiego stanu jako ruchu. Priority: must-have
  > Socrates: Counter-argument considered: "ignorowanie błędnych stanów / force-update". Resolution: kept; spina się z guardrails (legalność + brak cichej korupcji). Cisza nie jest jednoznacznym odrzuceniem. Post-Phase-7 refinement: w treści FR doprecyzowano, że "przywrócenie pozycji" jest manualne (z pomocą widoku diagnostycznego); pełne auto-detect zgodności matrycy bez przycisku wyodrębniony do nowego FR-022 jako nice-to-have.

### Koniec partii i persystencja
- FR-014: Użytkownik może w dowolnym momencie ręcznie oznaczyć koniec partii i jej wynik (1-0 / 0-1 / ½-½ / niedokończona). Priority: must-have
  > Socrates: Counter-argument considered: "wynik bez sztywnej enumeracji / tylko binarne 'koniec partii'". Resolution: kept; standardowa notacja szachowa wymaga jednego z czterech wyników.

- FR-015: Aplikacja automatycznie zapisuje każdy zaakceptowany ruch w trwałym magazynie (PGN + FEN po każdym ruchu, niezależnie od trybu i niezależnie od tego, czy partia została później zakończona). Priority: must-have
  > Socrates: Counter-argument considered: "zapis tylko po zakończeniu / tylko PGN bez FEN per move". Resolution: kept; spina się z guardrailem trwałości (crash-safe), FEN per move ułatwia replay (FR-020).

- FR-016: Aplikacja automatycznie wykrywa koniec partii (mat / pat / trzykrotne powtórzenie pozycji / reguła 50 ruchów) i sugeruje wynik. Priority: nice-to-have
  > Socrates: Counter-argument considered: "promo do must-have / tylko mat i pat bez 3x i 50". Resolution: kept as nice-to-have — MVP simplification autora, manualne oznaczanie wystarcza, auto-detect odracza się.

- FR-017: Gracz może zarejestrować rezygnację lub remis za zgodą jako jeden ze sposobów oznaczenia końca partii. Priority: nice-to-have
  > Socrates: Counter-argument considered: "redundantne z FR-014 / UI zgody za skomplikowane dla pass-and-play". Resolution: kept as nice-to-have — wchodzi po Primary path.

- FR-018: Użytkownik może rozpocząć nową partię z pozycji wybranej z dowolnej zapisanej partii (zarówno zakończonej, jak i niedokończonej) — "nowa partia od pozycji X". Priority: nice-to-have
  > Socrates: Counter-argument considered: "FEN do zewnętrznego narzędzia / tylko dla zakończonych". Resolution: kept as nice-to-have — wchodzi po Primary path.

> Usunięty FR (wstrzymanie/wznowienie partii): w Socratic round counter-argument ZAAKCEPTOWANY. Funkcjonalność wstrzymania jest implicite zapewniona przez FR-015 (auto-zapis każdego ruchu) + FR-019 (otwarcie partii z listy) — osobny FR redundantny.

### Historia i replay
- FR-019: Zalogowany użytkownik może otworzyć listę swoich partii w porządku chronologicznym. Priority: must-have
  > Socrates: Counter-argument considered: "lista wszystkich partii grona (społecznościowa) / tylko ostatnie 20". Resolution: kept; lista 'moich' partii (privacy), paginacja vs scroll to detal implementacyjny.

- FR-020: Użytkownik może otworzyć wybraną partię z historii i przejść ją ruch po ruchu (forward / back / start / end) z odtworzeniem każdej pozycji na ekranie. Priority: must-have
  > Socrates: Counter-argument considered: "tylko forward (playback) / Lichess-style side-by-side analysis board". Resolution: kept; pełen forward/back/start/end jest niezbywalny dla 'wracania do pozycji' (pierwotny ból z Fazy 1).

### Analiza (Secondary)
- FR-021: Po zakończeniu partii użytkownik może wywołać ocenę pozycji dla wybranych pozycji partii ze źródła zewnętrznego (np. darmowe Lichess Cloud Eval). Priority: nice-to-have
  > Socrates: Counter-argument considered: "tylko ocena końcowa / pasek real-time / własny silnik". Resolution: kept as nice-to-have — konkretne źródło (Lichess vs własny Stockfish) i zakres (per-position vs per-game) odracza się do implementacji. Własny silnik (na serwerze albo na urządzeniu mobilnym) wyraźnie zaznaczony jako post-MVP roadmap item.

### Rozszerzenia sprzętowe (nice-to-have)
- FR-022: Po wykryciu błędu detekcji (FR-013) aplikacja monitoruje stan matrycy w czasie ciągłym i automatycznie wykrywa moment, gdy plansza fizyczna zgadza się z oczekiwaną poprzednią legalną pozycją; po wykryciu zgodności partia wznawia się bez wymagania ponownego naciśnięcia przycisku zatwierdzającego. Zmniejsza tarcie w sytuacjach granicznych z hobbystycznym sprzętem. Priority: nice-to-have
  > Socrates: Nowy FR powstały post-Phase-7 jako odpowiedź na realistyczne ograniczenia sprzętu hobbystycznego (matryca kontaktronów z możliwymi fałszywymi trafieniami/niewykryciami). Wyodrębniony z must-have FR-013, który zachowuje manualne ponowienie po naciśnięciu przycisku.

- FR-023: Po utracie połączenia z fizyczną szachownicą w trakcie partii aplikacja wstrzymuje przyjmowanie ruchów, wyświetla jednoznaczny komunikat o utracie sieci i próbuje auto-reconnect w tle. Po przywróceniu połączenia partia kontynuuje od ostatniego zatwierdzonego ruchu bez utraty stanu i bez wymagania manualnej rekonstrukcji pozycji. Priority: nice-to-have
  > Socrates: Nowy FR powstały post-Phase-8 w wyniku cross-checku z shape-alternative.md. Counter-argument considered: "MVP może żyć bez tego — FR-015 (auto-zapis każdego ruchu) gwarantuje, że ostatni zatwierdzony stan jest na dysku, a po reconnect użytkownik otworzy partię z listy". Resolution: zachowane jako nice-to-have — happy-path bez utraty ciągłości gry istotnie poprawia UX dla mid-game disconnectu, ale wymaga bufferingu po stronie ESP32 i protokołu replay; w MVP akceptowalne jest minimalne pokrycie (ostatni zapis + manualne wznowienie z historii).

## User Stories

### US-01: Rozgrywka partii na fizycznej szachownicy

- **Given** zalogowany użytkownik z aplikacją mobilną sparowaną z fizyczną szachownicą podłączoną do tej samej sieci Wi-Fi oraz fizycznie obecny przeciwnik przy szachownicy
- **When** użytkownik tworzy nową partię w trybie "fizycznym", obaj gracze wykonują ruchy na fizycznej planszy i naciskają przycisk zatwierdzenia po każdym swoim ruchu
- **Then** aplikacja po każdym naciśnięciu przycisku odbiera stan matrycy ze sprzętu, rozpoznaje wykonany ruch, waliduje jego legalność, zapisuje go w trwałym magazynie (PGN + FEN) i wyświetla aktualną pozycję na ekranie urządzenia

#### Acceptance Criteria
- Naciśnięcie przycisku zegara po stronie gracza, który właśnie wykonał legalny ruch, skutkuje zapisem ruchu pod tym kolorem oraz widoczną aktualizacją pozycji na ekranie w czasie < 500 ms (zgodnie z guardrailem reaktywności i FR-011)
- Aplikacja śledzi sekwencję podniesień i odłożeń figur od ostatniego zatwierdzenia (continuous monitoring) i poprawnie rozpoznaje bicia oraz roszadę z pełnej sekwencji, a nie z samego snapshotu delta (FR-010)
- Jeśli zarejestrowana sekwencja po naciśnięciu przycisku NIE odpowiada żadnemu legalnemu ruchowi z aktualnej pozycji, aplikacja wyświetla komunikat błędu, wstrzymuje partię i prosi o przywrócenie poprzedniej pozycji; ruch nie zostaje zapisany (FR-013)
- Przy promocji piona aplikacja wyświetla pop-up wyboru figury; ruch jest zapisywany dopiero po wyborze (FR-007)
- Diagnostyczny widok stanu matrycy (które pola wykrywają figurę) jest dostępny dla użytkownika w trakcie partii (FR-012)
- Koniec partii i wynik (1-0 / 0-1 / ½-½ / niedokończona) są ustawiane manualnie przez użytkownika (FR-014); aplikacja nie ogłasza końca samodzielnie w MVP
- Po oznaczeniu końca partia jest dostępna w liście historii (FR-019) i może być przejrzana ruch po ruchu (FR-020)

## Business Logic

Aplikacja gwarantuje, że każda zarejestrowana partia szachowa składa się wyłącznie z legalnych ruchów i może być odtworzona ruch po ruchu w identycznym stanie, niezależnie od medium (fizyczne czy cyfrowe), na którym została rozegrana.

**Co reguła konsumuje (wejście).** Wejściem są surowe akty rozgrywki produkowane przez użytkownika w jednym z dwóch kanałów: (a) interakcje dotykowe na ekranie urządzenia mobilnego (przeciągnięcia lub kliknięcia pola-pole) wraz z wyborem figury w pop-upie promocji; (b) fizyczna sekwencja podniesień i odłożeń figur na drewnianej szachownicy z magnesami, zarejestrowana w czasie ciągłym, zakończona naciśnięciem przycisku zatwierdzającego po stronie gracza, który wykonał ruch. Oba kanały oddają tę samą semantykę "gracz chce wykonać ten ruch teraz".

**Co reguła produkuje (wyjście).** Każda zgłoszona próba ruchu kończy się jednym z dwóch wyników: ruch zostaje zaakceptowany jako konkretny legalny ruch szachowy z aktualnej pozycji i utrwalony w historii partii w standardowej notacji (PGN + FEN per pozycja); albo ruch zostaje jednoznacznie odrzucony z informacją, dlaczego jest nielegalny, a partia wstrzymana do momentu przywrócenia poprzedniej pozycji. Stan historii jest deterministycznie odtwarzalny — z zapisanej notacji można odtworzyć każdą pozycję, jaka kiedykolwiek wystąpiła w partii.

**Jak użytkownik z regułą się spotyka.** W trakcie rozgrywki — natychmiastowy feedback: ruch widoczny na ekranie albo komunikat błędu z prośbą o korektę. Po rozgrywce — partia w liście historii własnych partii, otwierana do replay'u krok po kroku (forward / back / start / end), gdzie każdy stan pośredni jest dokładnie tym, co wydarzyło się przy stole lub na ekranie. Reguła nie pyta użytkownika o rozstrzygnięcia szachowe — sama wie, co jest legalne, a co nie.

## Non-Functional Requirements

- **Prywatność partii.** Partia rozegrana przez użytkownika jest dostępna wyłącznie dla niego — nie pojawia się w widokach innych zalogowanych użytkowników ani niezalogowanych. Wyjątek w MVP nie istnieje.
- **Testowalność bez fizycznego sprzętu.** Aplikacja kliencka i jej część serwerowa mogą być w pełni zbudowane, uruchomione i zwalidowane automatycznie bez podpięcia fizycznej szachownicy ESP32 — niezależnie od tego, czy programista dysponuje sprzętem.
- **Wsparcie platform.** Aplikacja kliencka jest dostępna i w pełni funkcjonalna na aktualnych dwóch głównych wersjach Androida oraz dwóch głównych wersjach iOS w momencie wydania MVP.
- **Bezpieczeństwo poświadczeń sieci Wi-Fi.** Dane uwierzytelniające sieci Wi-Fi (SSID, hasło) nie są przechowywane w firmware mikrokontrolera ani w repozytorium kodu źródłowego. Są wprowadzane przez użytkownika końcowego i pozostają wyłącznie w pamięci konkretnego, sparowanego urządzenia.

## Non-Goals

Funkcjonalne i jakościowe scope avoidy. Technologie nie są tu listowane (idą do tech-stack-selection downstream).

- **Gra online przez internet / matchmaking / multiplayer.** MVP nie zawiera dobierania przeciwników, gry zdalnej ani integracji z platformami multiplayer (Lichess, Chess.com). Rozgrywka odbywa się lokalnie — na jednym urządzeniu (pass-and-play) lub na podłączonej fizycznej szachownicy. Online multiplayer jest jawnie odłożone do Fazy 2 (notatka idea-shape.md).
- **Gra przeciwko AI / lokalny silnik szachowy jako przeciwnik.** MVP wspiera wyłącznie partie człowiek vs człowiek. Lokalny Stockfish jako "przeciwnik komputerowy" jest funkcjonalnością post-MVP (notatka idea-shape.md jawnie). Ocena pozycji ze źródła zewnętrznego po partii (FR-021) to inny use case — analiza, nie gra.
- **Turnieje, ranking ELO, statystyki klubowe.** MVP nie zawiera funkcjonalności społecznościowo-klubowej (rozgrywki turniejowe, drabinki, ranking, agregaty W/L/D klubu). Każdy użytkownik widzi wyłącznie swoje partie.
- **Trening / puzzle / lekcje szachowe.** MVP nie zawiera modułu edukacyjnego (zadania matowe, treningi otwarć, lekcje). Aplikacja jest narzędziem do *grania i analizy własnych partii*, nie do *uczenia się szachów*.
- **Parowanie Bluetooth (BLE) sprzętu.** MVP używa wyłącznie Wi-Fi (WiFiManager captive portal — FR-008). BLE pairing dla skróconego onboardingu sprzętu jest odłożone do Fazy 2 (notatka idea-shape.md).
- **Klient web (Kotlin/Wasm).** Pierwotna notatka idea-shape.md zakładała klient telefon + tablet + web. W Fazie 3 web został wycięty z MVP — klient mobilny (Android + iOS) jest jedynym kanałem dostępu.
- **Parowanie aplikacji z wieloma fizycznymi szachownicami.** W MVP jedna domyślna szachownica per użytkownik (FR-009). Pełen mechanizm parowania z wieloma planszami (np. klub szachowy z kilkoma) jest odłożone do post-MVP.
- **Kontrola czasu (time control).** MVP nie wspiera ustawiania limitu czasu na partię (10+5, 5+0, blitz, klasyczne 90+30 itp.) ani wygranej przez przekroczenie czasu. "Zegar" w opisie sprzętu (FR-011) odnosi się wyłącznie do fizycznej formy organizacji dwóch przycisków zatwierdzających (chess-clock style), NIE do funkcji odmierzania czasu. Time control jest odłożone do post-MVP roadmapy.
- **Multi-client real-time dla gry fizycznej.** W MVP aplikacja działa na jednym urządzeniu obok planszy — oboje gracze widzą ten sam ekran w jednej sesji użytkownika zalogowanego do app (host gry). Wariant "każdy gracz na swoim telefonie, ekran obraca się dla perspektywy gracza, real-time sync między klientami" jest jawnie poza MVP — wymagałby pełnej infrastruktury multi-client sync i zarządzania sesją wieloosobową.

## Product framing (anticipated PRD frontmatter)

Notatka informacyjna dla `/10x-prd` — finalne wartości frontmatter pojawią się w `prd.md`.

- **`project`**: "Smart Chessboard"
- **`product_type`**: `mobile` (primary)
  - Free-text uzupełnienie: hybrydowy produkt obejmujący aplikację mobilną (Android + iOS) jako główny kanał użytkownika, towarzyszący backend (auth, persystencja, ewentualny pośrednik analizy) oraz fizyczny sprzęt IoT (ESP32 + matryca kontaktronów). `mobile` wybrane jako primary, bo to tam użytkownik spędza czas; backend i firmware są infrastrukturą wspierającą produkt.
  - Open Question (do PRD): czy `product_type` powinien być rozszerzony do explicit hybridy, czy `mobile` + adnotacja w opisie jest wystarczający?
- **`target_scale`**:
  - `users`: `small` (3–7 osób, closed beta)
  - `qps`: `low` (kilka partii dziennie, bursty traffic; analiza pozycji opcjonalna)
  - `data_volume`: `small` (PGN + FEN per partia; nawet 1000 partii = bardzo małe wolumeny)
- **`timeline_budget`**:
  - `mvp_weeks`: `12` (rewizja post-Phase-8: realistyczny budżet z buforem na nice-to-have, bez hard capa)
  - `hard_deadline`: `null` (projekt hobbystyczny/edukacyjny, brak zewnętrznej presji)
  - `after_hours_only`: `false` (autor pracuje nad projektem w mieszanym trybie, w tym pełen etat / sabbatical — to NIE jest klasyczny "tylko po godzinach")

## Quality cross-check

Faza 7 zakończona. Quality status: **accepted** (wszystkie 5 wymaganych elementów obecnych, w tym dodatkowo 4 Guardrails z Fazy 3).

| Element                    | Stan     | Notatka |
|----------------------------|----------|---------|
| Access Control             | present  | Otwarta rejestracja przez OAuth (post-Phase-7 revision); flat role; mechanizm OAuth provider w tech-stack. |
| Business Logic             | present  | Jednozdaniowa reguła: "Aplikacja gwarantuje, że każda zarejestrowana partia szachowa składa się wyłącznie z legalnych ruchów…" |
| Project artifacts          | present  | shape-notes.md z valid frontmatter checkpoint (current_phase, phases_completed, gray_areas, frs_drafted, quality_check_status). |
| Timeline-cost acknowledged | present  | `mvp_weeks: 12` (rewizja post-Phase-8) z jawną akceptacją sustained-effort cost (Faza 3, "Timeline acknowledgment" block). |
| Non-Goals                  | present  | 9 wpisów: online multiplayer, AI/silnik, turnieje, trening, BLE pairing, Web client, multi-board, time control, multi-client real-time. |
| Guardrails                 | present  | 4 (Faza 3): legalność ruchu, trwałość partii, reaktywność <500ms, brak cichej korupcji detekcji fizycznej. |

Brak zaakceptowanych "warned" gaps — `/10x-prd` otrzymuje pełen zestaw inputów i nie musi rzutować gap-warnings do `## Open Questions`.

**Post-Phase-7 refinements (po pierwszym cross-checku):**
- **Access Control (Faza 2):** closed beta z zaproszeniami → otwarta rejestracja przez OAuth external identity provider. Powody: niska widoczność projektu, OAuth eliminuje password management, cel edukacyjny autora.
- **FR-001, FR-002:** przepisane pod OAuth. Socrates blockquotes zaktualizowane o "Resolution: revised post-Phase-7".
- **Guardrail "Wierność detekcji fizycznej" (Faza 3):** zmiękczony z "100% poprawne rozpoznanie" na "brak cichej korupcji + akceptacja manualnej pomocy człowieka". Dostosowane do realistycznych ograniczeń hobbystycznego sprzętu.
- **FR-013 (must-have):** doprecyzowano "manualne przywrócenie pozycji + ponowny przycisk" jako happy-error-path.
- **FR-022 (NOWY, nice-to-have):** auto-detect powrotu do pozycji bez wymagania ponownego naciśnięcia przycisku. Wyodrębniony post-Phase-7 z must-have FR-013.

**Post-Phase-8 refinements (po cross-checku z shape-alternative.md):**
- **Timeline:** `mvp_weeks` 16 → 12. Powód: alternatywna wersja realizuje porównywalny scope w 8 tyg., nasz większy scope (22→23 FR) uzasadnia bufor, ale nie aż 4 tygodnie ponad. 12 tyg. jako realistyczny budżet z buforem na nice-to-have, bez hard capa — nice-to-have spadają do post-MVP roadmapy jeśli czas się skończy, bez renegocjacji must-have.
- **Guardrail "Trwałość partii":** doprecyzowano zakres — pokrywa awarię aplikacji, ale nie utratę połączenia z fizyczną szachownicą. Pełne wstrzymanie + auto-reconnect wyodrębnione jako FR-023.
- **FR-023 (NOWY, nice-to-have):** network-loss recovery — wstrzymanie partii + komunikat + auto-reconnect + zachowanie stanu. W MVP minimalne pokrycie (FR-015 zapis każdego ruchu), pełna ciągłość mid-game odracza się do nice-to-have.
- **Non-Goals (+2):** dodano "Kontrola czasu (time control)" — wprost zamyka furtkę, że "zegar" oznacza tylko fizyczną organizację przycisków (FR-011), nie funkcję odmierzania czasu. Dodano "Multi-client real-time dla gry fizycznej" — zamyka furtkę, że MVP zakłada jedno urządzenie obok planszy, nie sync między telefonami obu graczy.
- **Świadomie NIE zmienione (po porównaniu z alt):** anonymous access pozostaje "brak" (closed-circle persona nie wymaga); latencja 500 ms pozostaje (świadomy luźny próg dla hobbystycznego sprzętu, alt 300 ms wymagałby agresywnej optymalizacji niewspółmiernej do celów edukacyjnych); brak NFR budżetu czasu captive portalu (odkładane do implementacji, bez zobowiązania w shape-notes).

<!-- Phase 7 complete. Phase 8: Finalizacja + handoff — IN PROGRESS -->

## Forward: technical-roadmap

Notatki do downstream chain steps (tech-stack-selection, planowanie post-MVP roadmapy). NIE są częścią PRD schema — nie wejdą do `prd.md`.

- **Lokalny silnik szachowy (post-MVP nice-to-have).** W MVP analiza pozycji korzysta wyłącznie z zewnętrznego źródła (FR-021 — np. Lichess Cloud Eval). Post-MVP rozważyć uruchomienie własnego silnika (np. Stockfish) jako alternatywę: (a) na backendzie do oceny prywatnej partii bez wysyłania pozycji do zewnętrznego API; (b) bezpośrednio na urządzeniu mobilnym (telefon/tablet) jako tryb offline analizy. Decyzja "gdzie żyje silnik" odracza się do tech-stack-selection lub post-MVP planu.
- **Symulator sprzętu (strategia testowa, nie produkt).** Notatka idea-shape.md proponowała dedykowany endpoint udający fizyczną szachownicę dla automatycznych testów E2E i CI/CD bez podłączania ESP32. To strategia testowa / infrastruktura developerska, NIE funkcja użytkownika — nie jest FR. Wymaganie testowalności bez fizycznego sprzętu wejdzie do PRD jako NFR (Faza 5), konkretna realizacja (symulator) zostaje do tech-stack / planu.
- **Web (Kotlin/Wasm).** Pierwotnie w notatce idea-shape.md była częścią klienta wieloplatformowego (telefon + tablet + web panoramiczny). Wycięta z MVP w Fazie 3. Post-MVP może wrócić — wymaga rewizji layoutu i strategii deploymentu.
- **Bluetooth (BLE) pairing.** Jawnie poza MVP w notatce idea-shape.md (Faza 2 post-MVP). Jako alternatywa dla WiFiManager — krótszy onboarding sprzętu. Może wejść po MVP.
- **Multi-board pairing.** W MVP jedna domyślna szachownica per użytkownik (FR-009). Post-MVP — pełen mechanizm parowania z wieloma szachownicami (klub szachowy z kilkoma planszami).
- **Network-loss recovery dla gry fizycznej (post-MVP).** FR-023 (nice-to-have w MVP, może spaść do post-MVP). Pełna obsługa mid-game disconnectu wymaga bufferingu ostatniego stanu po stronie ESP32 + protokołu replay po reconnect + UX wstrzymania/wznowienia. W MVP minimalne pokrycie (ostatni zatwierdzony ruch z FR-015 + manualne wznowienie z listy partii). Pełna ciągłość gry po reconnect jest naturalnym post-MVP rozszerzeniem.
- **Kontrola czasu (time control) (post-MVP).** MVP traktuje "zegar" wyłącznie jako fizyczną organizację przycisków (FR-011). Pełne time control (10+5, blitz, klasyczne 90+30, wygrana przez przekroczenie czasu) wymaga: UI ustawienia czasu w tworzeniu partii (FR-004), stanu odmierzania czasu per gracz, obsługi końca partii przez timeout, ewentualnie wyświetlania pozostałego czasu na ekranie. Szachy bez zegara to częściowe doświadczenie — post-MVP roadmap item z wysokim priorytetem.
