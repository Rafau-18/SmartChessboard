# Manual Verification — real-board-over-ble (S-09, Phase 8)

**HARD BLOCKING GATE.** S-09 is *not complete and must not be archived* until this full,
app-driven acceptance pass succeeds on the repaired board over BLE on **both Android and iOS**.
This is the one piece no automated suite can prove: the iOS simulator has no BLE radio, and "the
real radio actually connects, streams, and reconnects" is only observable through the running app
against real reeds. (The link is **plaintext** — S-09 reverted the Phase 2 encryption after the
on-hardware gate found iOS bonding unreliable; see §7.)

This gate reuses the *structure* of the F-03 nRF-Connect gate (rows 2.4–3.10) but drives every
check **through the app** — asserting the app's move log, reconnect-reconcile behaviour, and the
live matrix overlay, not raw GATT bytes. It also absorbs every on-device manual row deferred from
Phases 3–7 (the whole slice's device manual was concentrated here on purpose).

> **How to use this file:** run the entire gate (§3 A–K) once on Android, then again on iOS.
> Record each item in the per-platform result logs (§4 Android, §5 iOS). Tick a `plan.md`
> `## Progress` row only after a human has actually performed it on the named platform(s) —
> see the write-back map in §6. Do **not** pre-tick anything.

---

## 0. Prerequisites & hardware setup

The board is `SmartChessboard-DA3A` (reed matrix repaired 2026-06-28; F-03 firmware on-hardware
verified 2026-06-29). Before you start:

- [ ] **Firmware is current (plaintext build).** The board must run the **plaintext** firmware
  (`mobile_command` `WRITE` / `board_event` `NOTIFY`, no `_ENC`). S-09 reverted the Phase 2 encryption — the Phase 8
  on-hardware gate found iOS bonding unreliable (stale-LTK desync). Re-flash from `firmware/`:
  `pio run -t erase && pio run -t upload` — the **erase clears any leftover bond** from the old
  encrypted build. A board still on the encrypted build will hit the iOS `reason=531` drop.
- [ ] **Power & ground.** ESP32 powered over USB; the **DGT clock is powered ON** and shares a
  **common ground** with the ESP32 — the side-button ADC reads (GPIO34/GPIO35, 100 kΩ pull-down,
  Schmitt hysteresis) only register while the clock is on. Without the clock on, the white/black
  confirm buttons do nothing.
- [ ] **Matrix sanity.** Move a magnet on a couple of squares and confirm on the serial monitor (or
  in nRF Connect) that the matrix still scans and orientation is correct (corners a1/h1/a8/h8).
  This catches a regressed solder joint before you blame the app.
- [ ] **Android device** running API ≥ 24 (the app's `minSdk`), Bluetooth available, *physical
  device* (an emulator has no usable BLE). USB-debugging enabled.
- [ ] **iOS device** — a **real iPhone/iPad** (the iOS *simulator has no BLE radio*, so 8.4 cannot
  run on it). A Mac with Xcode and a valid signing team (`iosApp/Config.xcconfig` `TEAM_ID`).
- [ ] **A clean slate (important now).** Re-flash the plaintext firmware (`pio run -t erase && pio run
  -t upload`) AND, in **each** device's system Bluetooth settings, **"Forget"** any previously-bonded
  `SmartChessboard-*` — this clears the leftover encrypted-build bond on every side so the plaintext
  link connects with no `reason=531`.
- [ ] **One device at a time (single-central board).** The board serves exactly one central (PRD
  non-goal). Run the cross-platform gate **sequentially** — before connecting the second device, fully
  disconnect the first (turn its Bluetooth off / kill the app). A board busy with one central **stops
  advertising**, so the other device can't even find it (and a leftover bond makes the OS silently
  re-grab it). If the app can't find a powered board, this is the first thing to check.

---

## 1. Build & install

**Android** (from `SmartChessboard/`):

```bash
# Install the debug build onto a connected device:
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :androidApp:installDebug --console=plain --no-daemon
# (or assembleDebug and install the APK from androidApp/build/outputs/apk/debug/ by hand)
```

**iOS:** open `iosApp/` in Xcode, select your *physical* iPhone as the run destination, and Run.
Ensure `Config.xcconfig` carries your `TEAM_ID` (managed locally via skip-worktree — never
committed). The first BLE use will surface the `NSBluetoothAlwaysUsageDescription` system prompt.

**Sign in** with Google on each platform first (the physical-mode game lives in your history), so
History and New-game flows are reachable.

---

## 2. Entry path into physical play (read once before §3)

Both **New game → Physical** and **History → (resume a physical game)** route through the
connection screen before the play screen:

```
New game → toggle "Physical" → Start ─┐
                                      ├─→  Connect board (scan → pair → Connected) ──→  Physical play
History → tap a physical game ────────┘                                              (adapter stays connected)
```

The connection screen ("Connect board") is the S-09 Phase 5 MVI gate; once it reaches **Connected**
it hands off to the play screen automatically and the singleton Kable adapter stays connected (the
play screen re-requests a snapshot on the CONNECTED it sees — an idempotent duplicate snapshot).

---

## 3. The gate — run A–K on Android, then again on iOS

Real, observable UI labels are quoted verbatim so you know exactly what to look for.

### A. Permissions, scan & connect  *(satisfies 5.4 / 5.5, the connect half of 8.3 / 8.4, and 8.5)*

1. Start a **New game**, toggle to **Physical**, tap **Start** (or tap a physical game in History).
2. **Permission prompt appears _before_ any scan.**
   - **Android:** the OS "Nearby devices" / Bluetooth-permission dialog appears. While it is up the
     screen shows **"Checking Bluetooth permission…"**.
     - **Deny it** once → the screen shows **"Bluetooth permission is needed to find and connect to
       your board."** plus the hint about enabling it in Settings, with **"Try again"** and **"Open
       settings"** buttons. "Open settings" deep-links to the app's settings page. *(5.4)*
     - Tap **"Try again"** and **grant** → it proceeds to scanning.
   - **iOS:** first BLE use shows the **`NSBluetooth…`** ("…would like to use Bluetooth") system
     prompt. Allow it. **The app must not crash** whether you allow or deny. *(5.5)*
3. **Scan.** The screen shows **"Scanning for boards…"** with a spinner; **`SmartChessboard-DA3A`**
   appears in the list within a few seconds, with a **"Signal −NN dBm"** line under the name.
   - If the list stays empty you'll see **"Make sure the board is powered on and nearby."** — check
     power/range, not the app.
4. **Tap the board row.** The screen shows **"Connecting…"** and connects **directly — no OS pairing
   prompt** (the link is plaintext; no bonding). It briefly shows **"Connected"** and hands off to the
   play screen.
5. **No pairing, no bond (8.5).** Because the link is plaintext there is **no** OS pairing dialog, and
   the board does **not** appear as a bonded device in system Bluetooth settings. *(If you DO get a
   pairing prompt — or a `reason=531` drop on the serial monitor — you're on the old encrypted firmware
   or a leftover bond: re-flash plaintext with `pio run -t erase && pio run -t upload` and "Forget" the
   board in system Bluetooth once.)*
6. **(8.5 reconnect half — re-verified in §I.)** No pairing is needed on reconnect either, since the
   link never bonded.

**Edge cases (S-09 P8 fixes — verify on both platforms):**
- **Bluetooth OFF.** With the phone's Bluetooth **turned off**, enter the connection screen / try to
  connect. The app must **not crash** — it shows **"Bluetooth is off. Turn it on and retry."** with a
  **Retry**. Turn Bluetooth on, tap Retry → it scans and proceeds.
- **Connect stability.** A connect must **not** flash "failed" on a transient blip (the adapter retries
  internally) and must **not** hang on "Connecting…" forever — a stuck connect falls through to a
  Retry-able failure within ~30 s ("Couldn't reach the board"), never an infinite spinner.

### B. On-subscribe burst & game start  *(app-driven mirror of nRF 2.6; satisfies the burst half of 8.3 / 8.4)*

7. The play screen opens titled **"<White> vs <Black>"** with the turn banner (e.g. **"White to
   move"**).
8. **The board is set to the starting position and there is _no_ "Set up the board…" warning** — the
   on-subscribe `BOARD_SNAPSHOT` was consumed and matched the expected position. (If the physical
   pieces are *not* in the start layout you'll instead see **"Set up the board to match the position
   on screen."** and the reed grid — set the pieces up to clear it; that itself exercises the
   snapshot-match path.)

### C. A full game — normal + capture + promotion + both confirm buttons  *(app-driven mirror of nRF 3.4 / 3.5; satisfies the gameplay half of 8.3 / 8.4)*

Play a short but complete game on the **physical** pieces. After each move, **press the side
confirm button** to commit it.

9. **A normal move.** Lift a pawn (e.g. e2), place it (e4). The lifted square is **highlighted** on
   screen while the piece is up; the sensor dot (see §D) moves with it. Press the **white** DGT
   confirm button → the move appears in the move list (**"1. e4"**) and the banner flips to **"Black
   to move"**. *(white-button confirm; nRF 3.5 white)*
10. **Both buttons.** Make Black's reply and confirm with the **black** DGT button → it commits and
    the banner flips back. Over the game, **confirm at least once with each colour's button**. *(nRF
    3.5 black)*
11. **A capture.** Reach a position with a legal capture; perform it physically (lift the captured
    piece off, move the capturing piece on — the order the interpreter accepts is either-first) and
    confirm → the capture commits and the move list shows the `x` SAN (e.g. **"… exd5"**).
12. **A promotion.** Push a pawn to the last rank and confirm → the **promotion picker** appears
    ("Pick a promotion piece"). Choose a piece → the promoted move commits with the right SAN
    (e.g. **"… e8=Q"**). *(If you press confirm before picking, the banner shows **"Pick a promotion
    piece before confirming."** — then pick and confirm.)*
13. **Reject path (illegal).** Attempt an obviously illegal move and confirm → play pauses, the banner
    shows **"That move isn't legal — restore the previous position and try again."**, and the reed grid
    opens to show which squares differ. The **reason stays on screen** while you restore — it is no
    longer flashed-then-replaced by the generic "Set up the board…" text (S-09 P8 message-priority fix).
    Restore the piece to where it was → the next legal move is accepted again. *(reject-recover on real reeds)*
14. **Finish.** Either reach a natural mate/stalemate (banner **"Checkmate — White wins"** /
    **"Stalemate — draw"**) or tap **"End game"**, pick a result, confirm. The finished screen shows
    **"Analyse"** and **"Back to history"**, and **"Saving…"** clears once the game is persisted.

### D. Live matrix overlay (sensor dots) tracks the real reeds  *(satisfies 7.5 and the overlay half of 8.6)*

15. With **"Sensor dots"** toggled **on** (the default), each occupied square shows a small neutral
    **corner dot**. Lift a piece → its dot disappears the instant the reed releases; place it (or any
    piece on an empty square) → a dot appears there. **The dots mirror the physical matrix in real
    time, before you confirm.**
16. Toggle **"Sensor dots" off** → all dots vanish, game state is unchanged. Toggle back on → dots
    return. (Display-only; flipping it never touches the game.)

### E. Diagnostic toggle (reed grid)  *(app-driven mirror of nRF 3.6)*

17. Trigger the reed grid (either via the **"Show diagnostics"** button that appears under a rejected
    move, or by causing a setup mismatch). The **Reed diagnostics** grid renders under the board:
    **"Reed diagnostics — highlighted squares differ from the position above."** with the differing
    squares highlighted, **updating live (~10 Hz)** as you move magnets. When opened manually a
    **"Hide"** affordance is shown; hiding it returns the board to GAME mode (the diagnostic stream
    stops). A setup-mismatch auto-entry clears itself the moment the board matches.

### F. Disconnect window — no move is saved while paused  *(satisfies the invariant half of 8.6; §1.7)*

18. Mid-game, **note the current move count** (length of the move list).
19. **Force a disconnect** without changing the pieces: walk the phone out of range, or toggle the
    phone's Bluetooth off→on briefly (leave the board powered). The banner shows **"Board
    disconnected — moves are paused until it reconnects."** with a **"Reconnect"** button beneath it
    (S-09 P8). The adapter also auto-retries in the background (foreground bounded backoff).
20. While disconnected, **press a confirm button** (and/or lift/place a piece). **Nothing commits** —
    the move count does not change; acceptance is blocked the whole window. (No journal write happens
    while paused/reconciling.)

### G. Reconnect-reconcile — auto-resume on a match  *(satisfies 6.5 and the reconnect half of 8.3 / 8.4)*

21. From the disconnected state in §F (pieces unchanged), **bring the phone back in range** (or
    toggle BT back on), **or tap "Reconnect"** on the banner. The app reconnects (the adapter re-drives
    `connect()`; `observe` re-subscribes; the play screen re-requests a snapshot on CONNECTED).
22. Because the board still matches the live position, the **post-reconnect snapshot clears the
    reconnect-reconcile gate automatically**: the "disconnected" banner disappears, **no "set up"
    prompt appears**, and **play resumes with no extra taps**. Make the next move + confirm → it
    commits normally. The move count is exactly what it was in §18 plus this one real move — **nothing
    was lost or double-counted across the window**.

### H. Reconnect-reconcile — offline change → mismatch → reed-grid restore  *(satisfies 6.5 / FR-012; app-driven mirror of nRF 3.9)*

23. Force a disconnect again (as in §19).
24. **While disconnected, make a legal change on the physical board** (e.g. play the side-to-move's
    next move on the pieces) — an "offline change" the app never saw.
25. **Reconnect.** The post-reconnect snapshot now **mismatches** the rebuilt expected position →
    the **reed grid opens automatically** and the banner shows **"Set up the board to match the
    position on screen."** Acceptance stays blocked.
26. **Restore the board to the on-screen position** (undo the offline change, matching the grid) →
    the mismatch clears, the grid closes, and **play resumes automatically**. Then make the move
    properly (lift/place + confirm) → it commits. The offline change was **never** silently accepted
    as a move.

### I. Reconnect needs no pairing  *(satisfies 8.5; app-driven mirror of nRF 2.5)*

27. After the reconnects in §G/§H, confirm **no OS pairing prompt appeared** on reconnect — the link is
    plaintext, so there is never any pairing/bonding to redo, and **no** `reason=531` drop. For a
    stronger check: fully **close and relaunch the app**; on re-entry it **auto-connects to the
    remembered board** (remembered by its advertised id, not a bond) with no prompt.

### J. Forget & re-pair  *(satisfies 5.6)*

28. Go back to the connection screen (start a new physical game or re-enter). Tap **"Forget saved
    board"** (shown while a remembered board exists) — or **"Forget & scan"** from a failure state.
29. The next entry shows the **scan list again** ("Scanning for boards…"), i.e. the remembered-device
    auto-connect no longer fires; you can re-select and re-pair the board from scratch.

### K. Emulator is gone from production DI — the device needs a real board  *(satisfies 4.5; backs 3.5)*

30. Confirm there is **no way to play physical mode without a real paired board**: a fresh physical
    game always routes through **"Connect board"** (scan/pair), never an auto-bound emulated board.
    This is the observable face of the Phase 4 DI swap (emulator → test-only) and the Phase 3 Kable
    adapter driving the real radio. *(There is no off-device radio test; this gate **is** the proof
    the real adapter works — 3.5.)*

### L. Screen stays awake & manual reconnect  *(S-09 P8 fixes surfaced by this gate; roll up into 8.3 / 8.4)*

31. **Screen stays on.** During play, stop touching the screen for longer than the system screen
    timeout. The screen must **not dim or lock** while the physical-play (or connection) screen is
    foreground — the app holds the screen awake so an idle stretch can't background it and drop the
    foreground-first BLE link.
32. **Manual reconnect works.** From the "Board disconnected" banner, tap **"Reconnect"** → the app
    re-establishes the link **without leaving the screen** (no backing out to History and re-entering).
    On success play resumes (reconnect-reconcile as in §G/§H). Also confirm the **background
    auto-reconnect** recovers a brief drop on its own within a few seconds, without any tap.

---

## 4. Result log — Android

Board: `SmartChessboard-DA3A` · Device: user's test Android tablet¹ · OS: Android¹ · Date: `2026-06-30`

> **✅ ACCEPTED 2026-07-04, recording the 2026-06-30 on-hardware pass (see `change.md`).** The user ran the full §3 A–K gate on the real board and accepted it — a complete game (normal / capture / promotion / castling, **both** DGT confirm buttons), the live sensor-dot overlay, the reed-diagnostics grid, disconnect-pause, reconnect-reconcile (match **and** offline-change→restore), resume, and forget/re-pair, all observed working. **Documented caveat:** raw BLE connect/reconnect was **flaky on this Android tablet** (possibly specific to its Bluetooth stack) — accepted for S-09, hardening spun off to **S-10** (`ble-connectivity-robustness`). ¹Exact model / OS build not captured in-repo during the session.

| § | Check | Pass? | Notes |
| --- | --- | --- | --- |
| A | Permission prompt before scan; deny → rationale + "Open settings"; grant → scan | ☑ | |
| A | Scan lists `SmartChessboard-DA3A` with RSSI; tap → Connecting → Connected | ☑ | |
| A | Connects with **no** OS pairing prompt (plaintext, no bond); "Connected" → play screen | ☑ | |
| A | Bluetooth OFF → graceful "Bluetooth is off…" message, **no crash**; Retry works | ☑ | |
| A | Connect doesn't flash "failed" on a blip and doesn't hang forever (≤30 s → Retry) | ☑ | |
| B | On-subscribe burst → game opens at expected position, no false "set up" warning | ☑ | |
| C | Normal move commits on **white** button; move list + banner update | ☑ | |
| C | A move commits on **black** button (both buttons exercised) | ☑ | |
| C | Capture commits with `x` SAN | ☑ | |
| C | Promotion → picker → promoted SAN (e.g. `=Q`) | ☑ | |
| C | Illegal move → reject reason stays up + reed grid → restore → next legal move accepted | ☑ | |
| C | Game finishes (mate/stalemate or manual End game); "Saving…" clears | ☑ | |
| D | Sensor dots track the real reeds live; toggle off/on works; game unaffected | ☑ | |
| E | Reed diagnostics grid renders + updates live; Hide returns to game | ☑ | |
| F | While disconnected, confirm/lift/place commits **nothing** (move count steady) | ☑ | |
| G | Reconnect on a match → auto-resume, no "set up", nothing lost/double-counted | ☑ | |
| H | Offline change → reconnect mismatch → reed grid → restore → resumes | ☑ | |
| I | No re-pair on reconnect; relaunch auto-connects to remembered board | ☑ | |
| J | Forget saved board → next entry shows scan list (re-pair path) | ☑ | |
| K | No physical play without a real paired board (emulator gone) | ☑ | |
| L | Screen stays awake during play + on the connection screen (no dim/lock) | ☑ | |
| L | "Reconnect" button recovers in-screen; background auto-retry recovers a brief drop | ☑ | |

**Android overall: ☑ PASS** (with the connect-stability caveat above → S-10) — backs plan rows 8.3, and the Android half of 8.5/8.6.

---

## 5. Result log — iOS

Board: `SmartChessboard-DA3A` · Device: user's real iPhone/iPad¹ · iOS¹ · Date: `2026-06-30`

> **✅ ACCEPTED 2026-07-04, recording the 2026-06-30 on-hardware pass (see `change.md`).** The same full §3 A–K gate was run on iOS and accepted. The earlier iOS bonding desync (stale-LTK `reason=531`) was resolved by reverting the link to **plaintext** (see §7 + `lessons.md`); post-revert the iOS connect / stream / reconnect flow was reliable. ¹Exact model / iOS build not captured in-repo during the session.

| § | Check | Pass? | Notes |
| --- | --- | --- | --- |
| A | First BLE use shows `NSBluetooth…` prompt; **no crash** on allow/deny | ☑ | |
| A | Scan lists `SmartChessboard-DA3A` with RSSI; tap → Connecting → Connected | ☑ | |
| A | Connects with **no** OS pairing prompt (plaintext, no bond); "Connected" → play screen | ☑ | |
| A | Bluetooth OFF → graceful "Bluetooth is off…" message, **no crash**; Retry works | ☑ | |
| A | Connect doesn't flash "failed" on a blip and doesn't hang forever (≤30 s → Retry) | ☑ | |
| B | On-subscribe burst → game opens at expected position, no false "set up" warning | ☑ | |
| C | Normal move commits on **white** button; move list + banner update | ☑ | |
| C | A move commits on **black** button (both buttons exercised) | ☑ | |
| C | Capture commits with `x` SAN | ☑ | |
| C | Promotion → picker → promoted SAN (e.g. `=Q`) | ☑ | |
| C | Illegal move → reject reason stays up + reed grid → restore → next legal move accepted | ☑ | |
| C | Game finishes (mate/stalemate or manual End game); "Saving…" clears | ☑ | |
| D | Sensor dots track the real reeds live; toggle off/on works; game unaffected | ☑ | |
| E | Reed diagnostics grid renders + updates live; Hide returns to game | ☑ | |
| F | While disconnected, confirm/lift/place commits **nothing** (move count steady) | ☑ | |
| G | Reconnect on a match → auto-resume, no "set up", nothing lost/double-counted | ☑ | |
| H | Offline change → reconnect mismatch → reed grid → restore → resumes | ☑ | |
| I | No re-pair on reconnect; relaunch auto-connects to remembered board | ☑ | |
| J | Forget saved board → next entry shows scan list (re-pair path) | ☑ | |
| K | No physical play without a real paired board (emulator gone) | ☑ | |
| L | Screen stays awake during play + on the connection screen (no dim/lock) | ☑ | |
| L | "Reconnect" button recovers in-screen; background auto-retry recovers a brief drop | ☑ | |

**iOS overall: ☑ PASS** — backs plan row 8.4, and the iOS half of 8.5/8.6.

---

## 6. Sign-off & `plan.md` `## Progress` write-back

Tick a Progress row only after the backing checks pass **on the platform(s) the row names**:

| Progress row | Backed by (this file) | Platform(s) |
| --- | --- | --- |
| 3.5 (adapter on real radio) | §K (and the whole gate driving the real adapter) | Android + iOS |
| 4.5 (DI swap covered) | §K | Android + iOS |
| 5.4 (Android perms: prompt / deny→rationale / grant→scan) | §A | Android |
| 5.5 (iOS first BLE prompt, no crash) | §A | iOS |
| 5.6 (forget → re-pair) | §J | Android + iOS |
| 6.5 (reconnect-reconcile on hardware) | §G + §H | Android + iOS |
| 7.5 (live overlay vs real reeds) | §D | Android + iOS |
| 8.3 (Android full gate) | §3 A–K on Android, §4 PASS | Android |
| 8.4 (iOS full gate) | §3 A–K on iOS, §5 PASS | iOS |
| 8.5 (no pairing/bond needed; reconnect needs no re-pair) | §A + §I on both | Android + iOS |
| 8.6 (no move lost; overlay matches reeds) | §F + §G + §D on both | Android + iOS |

When every row above is confirmed, S-09 is acceptance-complete and may proceed to
`/10x-impl-review` / `/10x-archive`.

---

## 7. Troubleshooting

### Immediate drop on (re)connect — `reason=531` (resolved: encryption reverted)

**Symptom:** the board serial log shows the central connecting then disconnecting within ~200 ms
(`central disconnected; reason=531` — `0x213` = HCI base `0x200` + `0x13` "remote terminated"),
repeatedly, while the app's connection screen stays on "Connecting…/Couldn't connect…".

**Root cause (and the fix):** this was the **Phase 2 encryption requirement** forcing an iOS bond. iOS
held a stale LTK the board no longer matched, tried to encrypt the link, failed, and terminated. S-09
**reverted the encryption to plaintext** (2026-06-30) — characteristics are `NOTIFY`/`WRITE` with no
`_ENC`, so there is no bond to desync and this drop should no longer occur.

**If you still see it,** you're running the **old encrypted firmware** or there's a **leftover bond**:
1. **Re-flash the plaintext firmware:** from `firmware/`, `pio run -t erase && pio run -t upload`
   (the erase clears the board's stored bond).
2. **Phone:** system Bluetooth settings → forget/unpair `SmartChessboard-DA3A` (clears iOS's stale LTK).
3. Re-enter the connection screen → it connects plaintext with **no** pairing prompt.

After both sides are clean and on the plaintext build, no pairing/bond happens at all, so there is
nothing left to desync.

### A mid-game disconnect with no way back

Fixed in S-09 P8: the "Board disconnected" banner now carries a **"Reconnect"** button and the adapter
auto-retries in the foreground. If a drop ever leaves you stuck, tap **Reconnect**; if that fails, the
bond-desync recovery above applies.

---

## Status

- [x] Android gate (§4) — PASS (accepted 2026-06-30; connect-stability caveat → S-10)
- [x] iOS gate (§5) — PASS (accepted 2026-06-30; plaintext revert resolved the iOS bonding drop)

> Filled in 2026-07-04, recording the user's 2026-06-30 on-hardware acceptance (see `change.md`).
> The matching `plan.md` Progress rows (3.5, 4.5, 5.4, 5.5, 5.6, 6.5, 7.5, 8.3–8.6) are ticked
> with the `f41f766` (p8) SHA. Doc-drift findings F1/F2/F3/F5/F6 from the impl-review are deferred
> to roadmap **S-10** (`ble-connectivity-robustness`).
