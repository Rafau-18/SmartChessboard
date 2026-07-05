# README media — still needed

The root [`README.md`](../../README.md) showcase section currently renders labeled SVG
placeholders. Replace them with real media, one at a time — each is independent, no
need to do them all at once. Delete this file once every row below is done.

| # | What | Placeholder file (delete once replaced) | Real file to add | README line(s) to update |
| - | --- | --- | --- | --- |
| 1 | YouTube demo video link | — | — | Replace both `YOUTUBE_ID` occurrences in the "See it in action" section with the real video ID (from `https://www.youtube.com/watch?v=<ID>`) |
| 2 | Hero photo — board mid-game + phone running the app | `hero-board.svg` | `hero-board.jpg` (or `.png`) | `<img src="docs/assets/readme/hero-board.svg" ...>` → change extension |
| 3 | Hardware photo — board interior (reed matrix, diodes, wiring) | `hw-internals.svg` | `hw-internals.jpg` | same pattern, in the Gallery section |
| 4 | Hardware photo — ESP32 + DGT chess-clock buttons | `hw-esp32-clock.svg` | `hw-esp32-clock.jpg` | same pattern, in the Gallery section |
| 5 | App screenshot — physical mode with live reed-matrix overlay | `app-physical.svg` | `app-physical.png` | same pattern, in the Gallery section |
| 6 | App screenshot — replay with eval bar + best-move arrow | `app-replay.svg` | `app-replay.png` | same pattern, in the Gallery section |
| 7 | App screenshot — game history (seeded famous games) | `app-history.svg` | `app-history.png` | same pattern, in the Gallery section |

## How to swap one in

1. Drop the real file into `docs/assets/readme/` under the "Real file to add" name.
2. In `README.md`, find the matching `<img src="docs/assets/readme/<name>.svg" ...>` and
   change `.svg` to the new extension.
3. `git rm docs/assets/readme/<name>.svg` (the placeholder is no longer referenced).
4. Screenshots: capture at a normal phone resolution and crop tight to the device
   frame or just the app content — no need for a literal device mockup. Photos: landscape
   orientation reads best for the hero shot (`width="100%"` spans the README).

## Video thumbnail — optional nicer version

The current link is a plain clickable placeholder image. Once you have the video ID,
you can swap the whole block for an auto-generated YouTube thumbnail instead (no image
file to maintain):

```md
[![Smart Chessboard — demo](https://img.youtube.com/vi/YOUTUBE_ID/maxresdefault.jpg)](https://www.youtube.com/watch?v=YOUTUBE_ID)
```
