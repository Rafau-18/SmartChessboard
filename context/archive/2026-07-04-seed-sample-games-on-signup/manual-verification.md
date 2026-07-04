# Manual Verification — seed-sample-games-on-signup

Deferred manual rows with the evidence gathered during implementation. Check the plan's
Progress rows only after a human pass over this document.

## Phase 1

### 1.6 Each new PGN sourced from a reliable canonical source (byte-clean, no variations)

All six movetexts were fetched from canonical sources during implementation (2026-07-04) and
cross-checked move-by-move; the parser/legality oracle then replayed every ply on JVM, iOS
Native, and wasm (`truncation == null`, `positions == sanMoves + 1` for all 8 fixtures).

| # | Game | Source consulted | Notes |
|---|------|-----------------|-------|
| 3 | Anderssen–Dufresne 1852 (Evergreen) | [Wikipedia: Evergreen Game](https://en.wikipedia.org/wiki/Evergreen_Game) | Exact day unknown → `[Date "1852.??.??"]`. Wikipedia notes moves 21–24 may have been "announced mate" rather than played; the canonical score everywhere includes them. |
| 4 | Byrne–Fischer 1956 (Game of the Century) | [Wikipedia: The Game of the Century](https://en.wikipedia.org/wiki/The_Game_of_the_Century_(chess)) | 1956-10-17, Rosenwald Memorial, NYC. Score matches verbatim. |
| 5 | Kasparov–Topalov 1999 (Kasparov's Immortal) | [Wikipedia: Kasparov's Immortal](https://en.wikipedia.org/wiki/Kasparov%27s_Immortal) | Round 4, Wijk aan Zee. Article gives no calendar date; `1999.01.20` is the commonly cited round-4 date — glance-check if exactness matters. |
| 6 | Polgár–Kasparov 2002 | Web-search consensus + final-move cross-check (chessgames.com "Ladies First" gid=1254283 and 365chess.com both **blocked direct fetch**, 403) | Two independent secondary attestations of the full score incl. the trailing `42. Rxg7 Kc8 1-0`. See mapping note below. |
| 7 | Deep Blue–Kasparov 1997 game 6 | [Wikipedia: Deep Blue versus Kasparov, 1997, Game 6](https://en.wikipedia.org/wiki/Deep_Blue_versus_Kasparov,_1997,_Game_6) | 1997-05-11, NYC. Score matches verbatim. |
| 8 | Levitsky–Marshall 1912 (Gold Coins) | [Wikipedia: Levitsky versus Marshall](https://en.wikipedia.org/wiki/Levitsky_versus_Marshall) | 1912-07-20, 18th DSB Congress, Breslau. Score matches verbatim. |

Normalizations applied for byte-clean PGN (no content change):

- Castling normalized `0-0` → `O-O` / `O-O-O` (PGN-standard letter O; matches existing fixtures
  and the app's own `PgnWriter` output).
- Results normalized to ASCII `1-0` / `0-1` (sources print en-dash `1–0`).
- **#6 move 12 disambiguation**: transcriptions print `12. Ne2`, but both White knights (c3, f3)
  can legally reach e2, so SAN requires `12. Nce2`. The c3-knight is provably the mover: only the
  f3-knight can play the attested `13. Nxh4`, and the full game then replays legally through
  move 42 (the parser would have truncated on any other reading).
- No comments, NAGs, or variations in any movetext (parser would truncate on `(`).

### 1.7 Outcome→`result` mapping verified; #6 Polgár–Kasparov discrepancy resolved

Mapping for the Phase 2 `result` column (`white`/`black`/`draw`), verified against the sources
above and additionally pinned by parser-level `headers.result` assertions in `PgnParserTest.kt`:

| # | Game | PGN `[Result]` | `result` column |
|---|------|----------------|-----------------|
| 1 | Morphy — Opera Game (existing) | 1-0 | `white` |
| 2 | Anderssen — Immortal Game (existing) | 1-0 | `white` |
| 3 | Anderssen–Dufresne — Evergreen | 1-0 | `white` |
| 4 | Byrne–Fischer — Game of the Century | 0-1 | `black` |
| 5 | Kasparov–Topalov 1999 | 1-0 | `white` |
| 6 | Polgár–Kasparov 2002 | 1-0 | `white` |
| 7 | Deep Blue–Kasparov g6 | 1-0 | `white` |
| 8 | Levitsky–Marshall — Gold Coins | 0-1 | `black` |

**#6 discrepancy resolved**: the roadmap pinned `0-1` for Polgár–Kasparov, which is inverted.
All sources agree Judit Polgár played **White** and **won** (her first win over Kasparov, Russia
vs the Rest of the World rapid, Moscow, 2002-09-09) ⇒ `[Result "1-0"]` ⇒ column `white`.

## Phase 2

Evidence gathered during implementation (2026-07-04) against the local stack
(`supabase db reset` on migration `20260704221841_seed_sample_games_on_signup.sql`).
App-level passes (sign-in, replay/eval, delete) still need a human run.

### 2.7 Fresh sign-in → history shows 8 games chronologically (FR-015)

**DB-level evidence**: after `db reset`, both seed users hold exactly 8 finished
`digital` games with `result` in (`white`,`black`) matching the Phase 1 mapping
(6 white / 2 black), with 8 distinct staggered `created_at` values. Alice's history
query (`created_at desc`) lists the seeds in historical order, newest first:
Polgár 2002 → Kasparov's Immortal 1999 → Deep Blue 1997 → Byrne–Fischer 1956 →
Gold Coins 1912 → Opera 1858 → Evergreen 1852 → Immortal 1851 (her two edge-case
rows interleave by their own timestamps). pgTAP functional checks pin 8-rows-per-new-user,
correct columns, and staggered timestamps. **Pending human**: fresh sign-in in the app
and eyeballing the history screen.

### 2.8 Seeded game replays with controls (FR-016) and evaluates (FR-017)

Parity holds (SeedPgnParityTest green: migration PGNs byte-identical to the 8
parser-verified fixtures), so replayability is proven at the fixture level on
JVM/iOS/wasm (Phase 1). **Pending human**: open a seeded game in the app; confirm
replay controls and the eval path.

### 2.9 Delete a seeded game (FR-021) → stays deleted; re-login does not re-seed

pgTAP evidence: deleting one seeded row leaves 7 and re-seeds nothing; an
`auth.users` UPDATE (login-shaped write) adds no rows (INSERT-only fire-once).
**Pending human**: delete from the app UI, sign out/in, confirm no resurrection.

### 2.10 Broken-PGN scratch test → sign-up still succeeds, WARNING logged

**Verified in a rolled-back scratch transaction** (local DB, 2026-07-04): replaced
`seed_sample_games()` with a CHECK-violating insert, inserted a fresh `auth.users`
row → `INSERT 0 1` succeeded, `WARNING: seed_sample_games failed for 66666666-…:
new row for relation "games" violates check constraint "games_mode_check"` was
logged, 0 games seeded, user row present. Sign-up is isolated from seed failures.
