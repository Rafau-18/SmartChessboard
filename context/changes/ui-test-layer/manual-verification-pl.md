# Weryfikacja manualna — ui-test-layer (instrukcja PL)

Ten plik to prosta, polska ściąga do zaległych punktów manualnych z Fazy 3, 4 i 5.
Kanoniczne (angielskie) źródło prawdy to `manual-verification.md` w tym samym
folderze — tam odhaczaj `[x]`, kiedy skończysz. Ten plik jest tylko po to, żebyś
nie musiał sobie tego wszystkiego przypominać od nowa.

Kod jest już zmergowany do `main`. Nic z poniższego nie blokuje działania aplikacji
— to tylko potwierdzenia jakościowe, które ktoś (Ty) powinien kiedyś przejrzeć.

**Aktualizacja (2026-07-05)**: po zmergowaniu Fazy 5 bramka na `main` na chwilę
zaczerwieniła się z innego powodu — biblioteka do zapisu/odczytu goldenów w
formacie WebP okazała się wadliwa (jej własny czytnik nie potrafił odczytać
części plików, które sama zapisała). Przełączyliśmy format na PNG (commit
`607aca3`, zawiera przenagranie kanoniczne `86050d3`) i `main` jest znów
zielony. To nie zmienia nic w punktach poniżej — dotyczy tylko formatu pliku,
nie treści testów.

---

## Najpierw: jak czytać obrazek „diff" (przyda się w punkcie 5.6)

Testy „golden screenshot" działają tak:

1. Mamy zapisany **wzorzec** — zrzut ekranu z przeszłości, o którym wiemy, że
   wygląda dobrze. To panel **Reference** (lewy).
2. Test renderuje ten sam ekran od nowa i robi **nowy zrzut**. To panel **New**
   (prawy).
3. Program porównuje oba obrazki piksel po pikselu i maluje wynik w środkowym
   panelu **Diff**:
   - **czarny piksel** = w tym miejscu nowy zrzut wygląda tak samo jak wzorzec (OK),
   - **czerwony piksel** = w tym miejscu jest różnica (alarm).

Jeśli ktoś (człowiek albo AI) przypadkiem zmieni coś w wyglądzie aplikacji, test
wychodzi na czerwono i pokazuje dokładnie, które piksele się zmieniły — więc nie
trzeba zgadywać, co się popsuło.

---

## 3.6 — Test „zagraj partię" czyta się jak opis produktu

**Co sprawdzić**: czy nazwy kroków w teście brzmią jak prawdziwy przepływ
użytkownika, a nie jak wewnętrzny żargon programisty.

**Jak to zrobić**:

1. Otwórz plik:
   `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/DigitalPlaySmokeTest.kt`
2. Przeczytaj go od góry do dołu jak instrukcję obsługi, nie jak kod.
3. Scenariusz powinien brzmieć mniej więcej tak: Historia (pusta) → Nowa gra →
   wpisanie imion graczy → Start → zagranie ruchu 1. e4 (kliknięcie pola e2,
   potem e4) → sprawdzenie, że ruch pojawił się na liście ruchów → zakończenie
   gry (przycisk End game) → wybór „White wins" → potwierdzenie → powrót do
   Historii, gdzie widać wiersz „White won".
4. Jeśli coś się nie zgadza z tym, jak faktycznie działa aplikacja — zanotuj to,
   to sygnał że test trzeba poprawić.

**Jeśli wszystko się zgadza**: odhacz `3.6` w `manual-verification.md`.

---

## 4.4 — Tekst potwierdzenia usunięcia gry zgadza się z prawdziwym dialogiem

**Co sprawdzić**: czy tekst, który test „udaje", że widzi na ekranie, jest
identyczny z tym, co naprawdę pokazuje aplikacja.

**Jak to zrobić**:

1. Otwórz plik testu:
   `SmartChessboard/shared/src/commonTest/kotlin/org/rurbaniak/smartchessboard/uitest/HistoryReplayDeleteSmokeTest.kt`
   — znajdź teksty typu „Delete game?" i „This permanently deletes …".
2. Otwórz prawdziwy ekran w kodzie:
   `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/history/HistoryScreen.kt`
   — znajdź funkcję/komponent `DeleteGameDialog`.
3. Porównaj teksty słowo w słowo. Muszą się zgadzać.

**Jeśli wszystko się zgadza**: odhacz `4.4`.

---

## 5.6 — Przegląd raportu różnic wizualnych (diff report)

**Co sprawdzić**: czy raport, który generuje CI po nieudanym teście wizualnym,
jest dla Ciebie czytelny i wystarczający jako narzędzie do sprawdzania „co się
zmieniło w wyglądzie".

**Jak to zrobić**:

1. Znajdź numer ostatniego uruchomienia testów (albo użyj dowolnego innego,
   nawet starszego — mechanizm jest ten sam):
   ```
   gh run list --workflow=tests.yml --limit 5
   ```
2. Pobierz raport z konkretnego uruchomienia (podmień `<ID>` na numer z listy):
   ```
   gh run download <ID> -n roborazzi-report -D ~/Downloads/roborazzi-report
   ```
   Uwaga: ten artefakt istnieje tylko dla uruchomień, które **faili na golden
   verify**. Jeśli akurat nie masz takiego pod ręką, możesz zrobić nowy test:
   zmień tymczasowo jakiś kolor w
   `SmartChessboard/shared/src/commonMain/kotlin/org/rurbaniak/smartchessboard/presentation/theme/ChessColors.kt`,
   commitnij na osobnej gałęzi, wypchnij, odpal
   `gh workflow run tests.yml --ref <gałąź>`, poczekaj aż zfailuje, pobierz
   raport, potem cofnij zmianę i skasuj gałąź.
3. Otwórz w przeglądarce:
   `~/Downloads/roborazzi-report/reports/roborazzi/androidHostTest/index.html`
4. Sprawdź, czy widzisz czytelnie trójki obrazków (Reference | Diff | New) dla
   każdego testu, który się nie zgadzał, i czy czerwone piksele w Diff faktycznie
   pokrywają się z tym, co się realnie zmieniło.

**Jeśli raport jest czytelny i wystarczający**: odhacz `5.6`.

### 5.6b — Przegląd konkretnego odświeżenia goldenów przez bota

To osobny, mniejszy punkt: zdalny robot CI (nie ja, nie Ty — automat GitHuba)
przeliczył wszystkie zrzuty planszy od nowa na swoim środowisku i zacommitował
różnice. (Uwaga: to jest już drugie takie odświeżenie — pierwsze było jeszcze
w formacie WebP, to poniżej jest aktualne, w PNG, po naprawie kodeka.)

1. Otwórz w przeglądarce:
   https://github.com/Rafau-18/SmartChessboard/commit/86050d3
2. GitHub sam pokaże podgląd „przed/po" dla każdego zmienionego pliku `.png`
   (można przełączać widok 2-up / suwak).
3. Sprawdź, że różnice to wyłącznie subtelne wygładzenie krawędzi bierek
   (antyaliasing) — **nie** zmiana układu, kolorów, brakujące elementy.

**Jeśli wygląda dobrze**: odhacz `5.6b`.

---

## 5.7 — Sprawdzenie zużycia minut GitHub Actions po tygodniu

**Co sprawdzić**: czy darmowy limit minut na GitHub Actions (2000 min/miesiąc)
nie zostanie przekroczony przez nasze nowe automaty.

**Kiedy to zrobić**: nie wcześniej niż **2026-07-12** (tydzień od uruchomienia
pierwszych workflow'ów) — wcześniej dane będą niemiarodajne.

**Jak to zrobić**:

1. Wejdź na: https://github.com/settings/billing (albo, jeśli repo jest w
   organizacji: Settings repo/org → Billing and licensing → Usage).
2. Znajdź sekcję **Actions** i zobacz, ile minut zużyto w tym miesiącu.
3. Punkt odniesienia z naszych dzisiejszych pomiarów:
   - zwykły test (`tests.yml` na Linuksie) — ok. 2–3 minuty za uruchomienie,
     liczone normalnie (×1),
   - nocny test na iPhonie (`ios-tests.yml`) — ok. 3 minuty na „rozgrzanym"
     cache, do ok. 9–10 minut na zimnym, ale **liczone ×10** (bo to maszyna z
     macOS) → realnie 30–95 minut zużycia za każdą noc.
   - Przy jednym uruchomieniu co noc to ~900–1200 minut miesięcznie samego
     iOS-a, plus drobne z każdego PR-a — powinno się zmieścić w 2000, ale bez
     dużego zapasu.
4. Jeśli okaże się, że zużycie jest zbyt wysokie: najprostsza poprawka to
   rzadszy harmonogram nocnego testu iOS (np. co 2–3 noce zamiast co noc) —
   to jedna linijka w pliku `.github/workflows/ios-tests.yml` (pole `cron`).

**Jeśli mieścimy się komfortowo w limicie**: odhacz `5.7`.

---

## Na koniec

Kiedy wszystkie cztery punkty (3.6, 4.4, 5.6 razem z 5.6b, 5.7) są odhaczone w
`manual-verification.md`, daj znać w rozmowie z Claude Code — wtedy zostanie
wykonany ostatni krok: `change.md` przełączy się z `status: implementing` na
`status: implemented`, i powstanie końcowy commit domykający cały plan.
