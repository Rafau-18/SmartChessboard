<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Firmware BLE GATT Service (F-03)

- **Plan**: `context/changes/firmware-ble-gatt-service/plan.md`
- **Scope**: Full plan — Phases 1–4 of 4 (all Automated criteria complete)
- **Worktree**: `.claude/worktrees/firmware-ble-gatt-service` (branch `impl/firmware-ble-gatt-service`)
- **Date**: 2026-06-19
- **Verdict**: NEEDS ATTENTION (sound implementation; 3 non-blocking hardening warnings) → all triaged
- **Findings**: 0 critical, 3 warnings, 2 observations

## Live re-verification (not just trusting Progress SHAs)

- `pio test -e native` → **15/15 PASSED** (debounce + protocol golden vectors).
- `pio run -e esp32dev` → **SUCCESS**, links with NimBLE (Flash 42.1%, RAM 8.5%). Re-run after the triage fixes: still SUCCESS.
- `firmware/lib/` purity grep (no `driver/gpio.h` / `esp_` / `Arduino.h` / `freertos` / `nimble`) → **CLEAN**.
- Notify symbol → `ble_gatts_notify_custom` (correct; the old `ble_gattc_notify_custom` appears only in an explanatory comment).
- Plan-review F2 fix (cross-task torn read of 64-bit `stable`) → **confirmed designed out**: `stable` is `app_main`-local, owned by the scan/producer task; the BLE host + timers only post `Request`s; no cross-task `stable` read exists.

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

Plan Adherence: every planned change MATCH; all 7 "Critical Implementation Details" PASS. Scope: no creep — the few extras (`decodeEvent` for host-test symmetry, `BLE_GAP_EVENT_REPEAT_PAIRING` handling, an extra debounce test) directly serve planned intent; "What We're NOT Doing" fully respected (no chess logic, matrix `pins.h` untouched, no persistence, battery constant, no MTU negotiation). Pattern Consistency: naming (`kCamelCase`, `s_`/`g_` prefixes), `pins.h` style, `index=file+8*rank`, error-handling split, include layering all match existing firmware idioms. Success Criteria: all automated re-verified green; manual rows deferred per the project manual-gate convention (none rubber-stamped).

## Findings

### F1 — consumer_task BLE-notify stack may be lean (4096 B)

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: firmware/src/ble_service.cpp:467
- **Detail**: `consumer_task` (stack 4096 B; ESP-IDF depth is bytes) is the sole caller of `ble_gatts_notify_custom`, which descends the GATT/L2CAP/HCI stack, plus an `ESP_LOGW` on the drop path. 4 KB is the one task stack you can't eyeball; an overflow under a multi-square burst would reset the board, surfacing only on the pending hardware pass. RAM is at 8.5% (27972/327680 B), so headroom is abundant.
- **Fix A ⭐ Recommended**: Bump the stack to 6144 B now; confirm the high-water mark via `uxTaskGetStackHighWaterMark` during the hardware pass.
  - Strength: Removes a crash tail before the first on-hardware connect; ~2 KB is effectively free at 8.5% RAM use.
  - Tradeoff: Slightly over-provisions until the high-water reading lands.
  - Confidence: MED — NimBLE notify call depth is real but unmeasured here.
  - Blind spot: True high-water only comes from hardware under load.
- **Fix B**: Leave 4096; measure high-water on hardware first, bump only if headroom < 512 B.
  - Strength: Data-driven, no over-provisioning.
  - Tradeoff: First-flash boot-loop risk if 4096 is already too small.
  - Confidence: MED.
  - Blind spot: Needs the board in hand before any confidence.
- **Decision**: FIXED via Fix A — stack 4096 → 6144 B + high-water comment (ble_service.cpp:467). Device rebuild SUCCESS.

### F2 — s_conn_handle read cross-task without `volatile`

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: firmware/src/ble_service.cpp:85 (decl); read :341, :350, :497
- **Detail**: `uint16_t s_conn_handle` (:85) is written by the NimBLE host task (:262, :276) and read by `consumer_task` (:341, :350) and `is_subscribed()` on the scan task (:497). Its sibling `s_subscribed` is `volatile` (:88); this one was not. A 16-bit aligned load won't tear on Xtensa, so the only effect is a benign late-disconnect window between the :341 guard and the :350 notify — NimBLE tolerates a notify on a just-closed handle (rc logged), so worst case is a stray log line, not a crash.
- **Fix**: Mark `s_conn_handle` `volatile` to match `s_subscribed`; the notify rc at :350 stays the real authority.
- **Decision**: FIXED — `s_conn_handle` is now `volatile` (ble_service.cpp:85).

### F3 — xTimerStart/Stop return values ignored

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: firmware/src/ble_service.cpp:203, 211, 283, 301, 307
- **Detail**: All five start/stop calls (20 ms block, `kTimerCmdTicks`) ignored the return value. If the FreeRTOS timer-service command queue ever saturates, a start/stop is silently dropped → the diagnostic stream or periodic `DEVICE_STATUS` fails to start/stop. Very unlikely at these rates; not a self-deadlock (runs in GAP/host context, not in a timer callback), but unchecked.
- **Fix**: Log on a non-`pdPASS` return from each `xTimerStart`/`xTimerStop`.
- **Decision**: FIXED — added `timer_start`/`timer_stop` helpers that fold in the null-guard and `ESP_LOGW` on a dropped command; the 5 call sites now route through them (ble_service.cpp). Device rebuild SUCCESS.

### F4 — "Flash size mismatch" build warning (pre-existing, out of scope)

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: firmware/sdkconfig.defaults (no `CONFIG_ESPTOOLPY_FLASHSIZE` key)
- **Detail**: `pio run -e esp32dev` prints "Flash memory size mismatch detected. Expected 4MB, found 2MB!". The seed sets no flash-size key, so ESP-IDF defaults to 2 MB while the `esp32dev` board manifest declares 4 MB. PRE-EXISTING (the F-03 diff adds only BLE keys — verified) and harmless: the image is 42.1% of the 1 MB app partition. The final ESP32 variant is OQ-1 (still open), so the conservative 2 MB default always-fits; hardcoding 4 MB would assume a board not yet chosen.
- **Fix**: None for this change. If it ever matters (larger partition / OTA — both out of scope), add `CONFIG_ESPTOOLPY_FLASHSIZE_4MB=y` to the seed once OQ-1 is settled.
- **Decision**: SKIPPED — pre-existing, out of F-03 scope; 2 MB is the safe assumption until OQ-1 lands.

### F5 — Manual rows 1.4 & 4.4 are doc/code-read, already confirmed by this review

- **Severity**: 🔭 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: context/changes/firmware-ble-gatt-service/manual-verification.md (+ plan.md Progress)
- **Detail**: 1.4 (golden frames match `BoardWireCodecTest.kt`) and 4.4 (`AGENTS.md` reads coherently) need no hardware. This review independently confirmed both: the golden vectors match the Kotlin oracle verbatim and `pio test -e native` proves the firmware asserts them (15/15); `AGENTS.md` matches what was built (PARKED framing gone; BLE/buttons/lib/native-tests documented). Rows 2.4–3.10 remain genuine on-hardware gates.
- **Fix**: Tick 1.4 and 4.4 in Progress + `manual-verification.md`, citing this review as the evidence.
- **Decision**: FIXED — 1.4 and 4.4 ticked in `plan.md` Progress and marked CONFIRMED in `manual-verification.md`; 2.4–3.10 left pending the board.

## Triage summary

- **Fixed**: F1 (Fix A), F2, F3, F5 (4)
- **Skipped**: F4 (1)

All three Safety & Quality warnings were applied as code fixes; the device build was re-run green after them. The two observations were resolved (F5 ticked the two non-hardware manual rows; F4 deliberately left as a pre-existing, out-of-scope build warning). The remaining manual gates (2.4–3.10) are genuine on-hardware checks tracked in `manual-verification.md`, to be run at end-of-slice against the partially-working board + temporary buttons before `/10x-archive`.
