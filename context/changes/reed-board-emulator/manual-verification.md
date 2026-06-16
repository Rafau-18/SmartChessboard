# Deferred manual verification — F-02 reed-board-emulator

**Status:** DEFERRED. Automated verification passed and implementation continued; the
human-in-the-loop manual checks below were intentionally postponed. Revisit at the end of the
F-02 slice (or before the change is archived).

**Why these are manual at all:** the golden-frame tests compare the codec against byte vectors
that were *hand-derived from the contract*. If a vector was hand-computed wrong **and** the codec
shares that mistake, the test stays green and proves nothing (the codec just agrees with itself).
Only an independent human recomputation from `docs/reference/contract-surfaces.md` §1.3/§1.4 closes
that gap. This is the one check a passing test suite cannot replace.

When done, tick the matching boxes in `plan.md` `## Progress` (2.3, 2.4) and delete the
`TODO(F-02, manual gate ...)` comment in `BoardWireCodecTest.kt`.

---

## [ ] 2.3 — Recompute 3–4 golden vectors by hand and compare to the codec/test

Source of truth: `contract-surfaces.md` §1.3. Files holding the values: `BoardWireCodec.kt` (codec),
`BoardWireCodecTest.kt` (the literal golden frames).

**Rule — SQUARE_EVENT (1 byte):** `byte = (event_code << 6) | square`, where
`square = file + 8 * rank` (a=0..h=7, rank1=0..rank8=7) and `event_code`: `00`=lift, `01`=place.

- [ ] place on **e4** → expect `0x5C`. (e4: file 4, rank index 3 → square 28 = `011100`; place `01`
  on top → `01 011100` = `0x5C`.)
- [ ] lift on **e2** → expect `0x0C`. (e2 = square 12; lift = `00` on top.)

**Rule — BOARD_SNAPSHOT (8 bytes):** byte `i` holds squares `i*8 .. i*8+7`; bit `j` (LSB-first) =
square `i*8 + j`.

- [ ] **start position** (ranks 1,2,7,8 occupied) → expect `FF FF 00 00 00 00 FF FF`.
  (byte0=squares 0–7=FF, byte1=8–15=FF, bytes2–5=empty=00, byte6=48–55=FF, byte7=56–63=FF.)
- [ ] **a2** (square 8) occupied alone → expect `00 01 00 00 00 00 00 00`. (byte = 8÷8 = 1, bit =
  8 mod 8 = 0 → only byte 1 = `0x01`. Pins byte ordering: a wrong order would put it in byte 0.)

**Optional — DEVICE_STATUS uptime is little-endian (4 bytes, least-significant first):**

- [ ] uptime `67_305_985` (= `0x04030201`) → bytes `01 02 03 04` (not `04 03 02 01`).

## [ ] 2.4 — Confirm the contract + PRD doc edits are minimal and dated

The contract's change-control rule routes any §1 (BLE) change into both PRDs, dated.

- [ ] `docs/reference/contract-surfaces.md` §1.3 — one added sentence pinning the snapshot byte
  layout (byte `i` bit `j` LSB-first = square `i*8+j`); frontmatter `updated: 2026-06-16`.
- [ ] `context/foundation/prd-firmware.md` — one dated line under FR-FW-005 mirroring the
  clarification (firmware must pack bytes the same way).
- [ ] `context/foundation/prd.md` — one dated line in "Implementation Decisions" marked
  **no user-facing impact** (it is an internal wire detail; no FR behavior depends on it).

## How to re-run the automated checks (optional, no install/deploy needed)

```bash
cd SmartChessboard
ANDROID_HOME="$HOME/Library/Android/sdk" ./gradlew :shared:testAndroidHostTest :shared:iosSimulatorArm64Test --console=plain --no-daemon
ktlint -F
```
