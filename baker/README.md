# baker

Renders destiny.gg's emotes in headless Chromium and writes out frames the mod can draw.

Nothing here runs in the game, and the mod does not depend on this code — only on its
output. This is the only part of the project that talks to `cdn.destiny.gg`.

## Why a browser

`emotes.css` is two documents glued together: a generated head, one block per emote, and a
hand-written tail of per-emote overrides. The tail is where the real display size lives,
and it is irregular in every way a stylesheet can be — multi-track `animation` shorthands,
`!important`, custom properties, negative delays, `cubic-bezier`, `::before` content,
sibling selectors, and sprite strips cropped by `overflow: hidden`.

Reimplementing a useful subset in Java would be a CSS animation engine, and would still
leave dozens of emotes as still images. Chromium already implements all of it correctly, so
it renders each emote once, offline, and the mod ships the pixels.

## Running it

```bash
npm install
npx playwright install chromium

npm run bake -- --out ../out              # all 324, a few minutes
npm run bake -- --only PEPE,Askers        # one or two, while changing something
```

| Flag | Default | |
|---|---|---|
| `--out` | `./out` | where the PNGs and `manifest.json` go |
| `--cache` | `./.cache` | downloaded CDN responses, so a re-run is offline |
| `--only` | | comma-separated prefixes |
| `--limit` | | first N emotes |
| `--fps` | 20 | sampling rate for animations with no natural frame grid |
| `--max-frames` | 120 | ceiling per emote; anything cut is named in the summary |
| `--max-tile` | 128 | largest tile edge, see below |
| `--concurrency` | 4 | browser pages in parallel |

## What comes out

One PNG per emote, its distinct frames stacked vertically, plus `manifest.json`. Per-emote
files rather than one atlas, because the client fetches an emote the first time it is
actually seen: a session uses a few dozen of the 324, so a single atlas would spend twelve
megabytes to save nothing.

Geometry is measured from the live DOM rather than derived from the CSS. Two zero-width
probes either side of the emote give the advance including its negative margins, a third
gives the text baseline, and the captured frames are cropped to the union of their
non-transparent pixels. That last step is what answers "how much padding does a `translate`
need" by measurement instead of by guess, and it is why the manifest can state an exact
bearing and ascent.

Timing describes **one iteration**; the client repeats it. `Askers` is `steps(99)` over 3.5s
played four times, so it bakes as 99 tiles at 35ms with `iterations: 4` rather than as 396
frames of the same four passes.

## Things it gets specifically right

- **Sprite strips.** 45 emotes are drawn at a size that is not the size in `emotes.json`;
  `Askers` is 5544px wide there and 48px on screen. `overflow: hidden` in the harness is
  what crops them, and `steps(N)` is read off the computed style so each step is captured
  once instead of being sampled on a frame rate that misses most of them.
- **Animated containers.** GIF, WebP and AVIF play on Chromium's own clock, which no amount
  of seeking controls. They are decoded up front with `ImageDecoder`, and each frame is
  swapped into `background-image` as a blob URL, so CSS transforms and filters still apply
  on top.
- **Image types.** `AlienPls` is published as `image/webp` at a URL ending `.gif`, and it
  really is a WebP. Types come from sniffing the bytes.
- **Image loading.** Three emotes, `OMEGALUL` among them, used to bake as sixty frames of
  transparent pixels because nothing waited for the background image to decode.
- **Oversized emotes.** Minecraft stitches glyphs into 256×256 font pages, and `Chatting` is
  320px wide. Tiles above `--max-tile` are box-filtered down in premultiplied alpha, and the
  manifest records the scale so the mod still draws it at full size.
- **Flairs.** Two of the 25 visible flair icons are WebP, which Minecraft's image reader
  cannot open. They are re-encoded here, along with the joined flair metadata, so the client
  needs no second host and no browser user agent of its own.

## What it cannot capture

Anything that depends on an emote's surroundings: `AMOGUS` hue-rotating by `nth-child`,
`.GIGACHAD + .ApeHands` reacting to its neighbour, and `:hover`, which does not exist in
Minecraft chat. Those bake in their default context. Roughly half the animation rules in the
stylesheet are hover duplicates and are skipped.

## Publishing

Deliberately not automated. Serving derived copies of Destiny.gg's emotes is a licensing
question for a person to answer, not a cron job — see the open question in the root README.
The workflow in `.github/workflows/bake.yml` runs on demand and uploads the result as a
build artifact; moving that to a host is a manual step.
