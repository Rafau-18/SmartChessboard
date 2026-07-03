# Manual verification — mobile-landscape-layout

Device-fleet manual checks accumulate here per phase. Per the plan's Testing Strategy,
phases run consecutively with manual rows deferred to **one end-of-slice acceptance pass**
on the owner fleet (Android phone + iPhone + web resize; tablet/iPad if at hand).

Status legend: `[ ]` pending, `[x]` confirmed on the fleet.

## Phase 1: Functional fixes

Automated gate: **passed** (all four Gradle targets green + ktlint clean).

Inset audit finding (plan Phase 1 item #4, recorded once):
- `MainActivity` calls `enableEdgeToEdge()`, so Compose receives the `systemBars`, `ime`
  and `displayCutout` insets. The manifest neither locks `screenOrientation` nor overrides
  `windowSoftInputMode`, so landscape is allowed and the IME inset animates into Compose.
- `ScaffoldDefaults.contentWindowInsets` at material3 `1.11.0-alpha07` resolves to
  `WindowInsets.systemBars` (status + navigation bars; the iOS home-indicator arrives via
  navigation bars). It does **not** include `ime` or `displayCutout`.
- Only genuinely-missing inset that causes a bug today: **IME on the NewGame form** — fixed
  with `imePadding()`. Connection/EndGamePicker have no text fields, so no IME gap there.
- `displayCutout`: NewGame and Connection content is a centered, 480 dp width-capped column,
  so it clears the long-edge landscape cutout by construction. The leading-edge action rail
  (Phase 3) will own `displayCutout` consumption when it lands. No blanket `safeDrawing`
  added anywhere; SignIn keeps its `safeContentPadding()`.

Manual checks (deferred to the end-of-slice pass):

- [ ] 1.3 NewGame landscape: with the duplicate-name error visible, Start is reachable;
      focusing a field with the IME open never hides the focused field (Android + iOS)
- [ ] 1.4 Connection with a long device list at phone height: "Forget saved board" reachable
- [ ] 1.5 EndGamePicker at ~360 dp height: all result options + confirm reachable
- [ ] 1.6 Cutout device, both landscape rotations: nothing under the cutout; portrait unregressed
