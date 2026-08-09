# dgg-chat-mod

Renders Destiny.gg chat inside Minecraft the way destiny.gg renders it: real emote images
with animation, real flair icons, and the exact username colour a person has in DGG chat.

Facts below about destiny.gg's data were verified against the live CDN and the
`destinygg/chat-gui` source on 2026-08-08.

---

## State

Built and working end to end. The baked emotes are served from GitHub Pages by this
repository's `bake` workflow, which the mod points at by default; if it cannot reach the
manifest, chat looks exactly like vanilla chat.

| | |
|---|---|
| `core` | emote matching, flair resolution, username colour, ported from chat-gui. 59 tests. |
| `api` | the identity SPI another mod implements. 4 tests. |
| `neoforge` | the mod: optional payload, identity relay, glyph provider, chat rewrite. 14 tests. |
| `baker` | the Playwright bake. Produces all 324 emotes and 46 flairs. |

### What was actually verified

Worth being precise, because a mod that compiles is not a mod that works.

- **The bake**, on all 324 emotes and 46 flairs. 4526 tiles, 12MB. Frames were inspected
  visually: sprite strips slice correctly, GIF and WebP containers decode, transforms are
  captured rather than clipped.
- **Dedicated server**, `./gradlew :neoforge:runServer`: the mod loads and the server
  reaches `Done` with no errors.
- **Client**, `./gradlew :neoforge:runClient`: the mod loads, the mixin applies, the emote
  font is created with the provider installed, and the manifest downloads and parses.
- **The chat rewrite**, under unit test against real `Component` trees, including the
  vanilla `<%s> %s` decoration whose sender and body are translation arguments rather than
  children.
- **chat-gui parity** for emote matching and username colour, against expectations produced
  by running chat-gui's own code in V8.

Not verified by running: an emote glyph being stitched into the font atlas and animated
frame by frame. That path needs text drawn on screen in a live session. It is the riskiest
remaining thing.

---

## Two halves, both optional

One jar, two sides. Neither side may ever be a requirement for the other.

| | client has the mod | client does not |
|---|---|---|
| **server has the mod** | full: emotes, flair icons, DGG names and colours | ordinary vanilla chat, unchanged |
| **server does not** | emotes only, matched from raw names in chat text | nothing happens |

Three rules fall out of that, and they are the ones most likely to be broken by accident:

1. **A vanilla or non-mod client must still be able to join a server running this mod.**
   Every custom payload is registered through `PayloadRegistrar#optional()`, and every send
   is guarded by `player.connection.hasChannel(...)`. Optional registration stops the
   handshake rejecting the client; the guard stops the server then sending on a channel that
   client never negotiated.
2. **A client running this mod must still be able to join any server**, vanilla or modded.
   Nothing arrives on the channel, so the identity half stays dormant and only emote
   rendering runs.
3. **The emote half never needs the server.** It works on raw emote names in chat text
   (`PEPE`, `glorp`), so it works on any server anywhere, including servers that have never
   heard of DGG.

## Target

Matches `dggauth-proxy`, which is already built and deployed against this stack.

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.248 |
| Pack | ATM10 7.3 |
| Java | release 21 |
| Build | ModDevGradle 2.0.143 |

Fabric has no consumer here and is not built.

---

## Where the data comes from

The baker reads all four of destiny.gg's files. The mod reads none of them.

| URL | What it carries | Read by |
|---|---|---|
| `cdn.destiny.gg/emotes/emotes.json` | the emote list: name, image URL, intrinsic size | the baker |
| `cdn.destiny.gg/emotes/emotes.css` | **how each emote is actually drawn**, see below | the baker |
| `cdn.destiny.gg/flairs/flairs.json` | flair list: name, label, colour, priority, icon | the baker |
| `cdn.destiny.gg/flairs/flairs.css` | flair icon ordering and hidden flags | the baker |

Flairs were originally going to be a direct client fetch. Two things changed that: two of
the twenty-five visible icons are WebP and Minecraft's `NativeImage` reads PNG only, and
`cdn.destiny.gg` answers **403 to unusual user agents**, which would have put a
browser-impersonating fetch in the client. Both problems disappear if the bake handles
flairs too, and the client is then left talking to exactly one host.

### emotes.json

324 entries, each with exactly one image. Currently 289 PNG, 30 GIF, 4 AVIF, 1 WebP. All
are `theme: 1` and `twitch: false`; one emote is gated at `minimumSubTier: 5` and the rest
at 0. None of that is hardcoded, but it does mean chat-gui's Twitch-sub code path is
presently dead weight.

One entry lies about its type: **`AlienPls` is published as `image/webp` at a URL ending
`.gif`**, and the bytes say WebP. The baker sniffs.

### emotes.css, and why it is not optional

**This is the part that is easy to get wrong.** `emotes.css` is two documents glued
together:

- **A generated head**, one block per emote, derived from the JSON: background image, plus
  `height` and `width` equal to the JSON's intrinsic image size.
- **A hand-written tail** (from roughly line 2916 on) with per-emote overrides: real display
  size, sprite-sheet cropping, keyframes, transforms, sibling rules.

The consequence: **for 45 emotes the JSON's width is not the width the emote is drawn at.**
Those images are horizontal sprite strips and the JSON reports the strip.

| emote | width in JSON | width drawn |
|---|---|---|
| `Askers` | 5544px | 48px (56px, less 4px of negative margin each side) |
| `BINGQILIN` | 6390px | 32px |
| `CuckCrab` | 2944px | 32px |
| `ACKSHUALLY` | 94px | 47px |

Render `Askers` at its JSON width and you get a 5544 pixel smear. So **a build that reads
only `emotes.json` is wrong**, not merely less pretty.

Vertical placement also comes from here: each emote adds `margin-top: -<height>px` and
`top: <height/4>px` inside `.msg-chat`, so the emote hangs from the text baseline with a
quarter of its height below it. The baker measures this from the live DOM rather than
trusting the rule, which is how emotes with transforms come out right.

### flairs.json and flairs.css

46 flairs. The colour and `hidden` values in the JSON agree with `flairs.css` exactly: all
46 cross-checked, zero mismatches. Two things do not live in the JSON:

- **Icon display order**, via the flex `order` property (values 2 to 127). `priority` drives
  username colour, not icon order, and the two are different numbers.
- Nothing else. The CSS is parsed for `order` and `display: none` only.

Two edge cases that a parser will hit:

- **`flair125` (Head Mod) has an image entry whose every field is `null`.** It is hidden, so
  nothing renders today, but nothing may assume a usable URL is there.
- **`flair5` and `flair124` publish `"color": ""`.** chat-gui tests `f.rainbowColor ||
  f.color`, so an empty string is *no colour* — a user with only those flairs keeps a plain
  name. Treating empty as present would hand them the username against what the site shows.

---

## Emote matching: copy chat-gui exactly

From `assets/chat/js/emotes.js` and `formatters/EmoteFormatter.js`:

```js
new RegExp(`(^|\\s)(${prefixes.join('|')})(?=$|\\s)`, 'gm')
```

Which means, precisely:

- **Whitespace-delimited only.** `PEPE` in `xPEPEy` is not an emote.
- **Case sensitive.** `pepe` is not `PEPE`.
- **Longer names win** over shorter prefixes of themselves regardless of list order, because
  the trailing lookahead forces a backtrack.
- **Adjacent emotes both match**, because the leading whitespace is captured rather than
  consumed.

Two deliberate differences from the JavaScript, both in `EmoteMatcher`: prefixes are quoted
before going into the alternation, so a future emote name containing a regex metacharacter
matches literally instead of corrupting the pattern; and `\s` is compiled with
`UNICODE_CHARACTER_CLASS`, because Java's default `\s` is ASCII-only while JavaScript's is
not.

**Sender tier gating** is implemented (`BakeManifest#prefixesFor`) but off. chat-gui builds
its regex from the emotes the sender may use, so a non-subscriber typing a tier-5 emote name
gets plain text. Turning it on would make a Minecraft player's emotes depend on their
destiny.gg subscription, which is a product call rather than a technical one.

### Username colour: copy chat-gui exactly

From `messages/ChatUserMessage.js`:

```js
export function usernameColorFlair(allFlairs, user) {
  return allFlairs
    .filter((flair) => user.features.some((userFlair) => userFlair === flair.name))
    .sort((a, b) => (a.priority - b.priority >= 0 ? 1 : -1))
    .find((f) => f.rainbowColor || f.color);
}
```

Lower priority wins, and only the first flair carrying a colour counts; the rest contribute
icons only.

That comparator never returns zero, so `compare(x, x)` is `1`. Java's `List.sort` answers it
with `IllegalArgumentException: Comparison method violates its general contract`, and any
tidied-up comparator silently reorders ties. **The ties are real and they matter**: four
coloured flairs sit at priority 3, two of them the rainbow ones, so which flair wins the
username colour is decided precisely by how the engine breaks them.

So `JsArraySort` ports V8's sort rather than rewriting the comparator. Below 64 elements
V8's TimSort computes a minimum run length equal to the whole array and reduces to
`CountAndMakeRun` followed by `BinaryInsertionSort`, which is what it reproduces exactly.
destiny.gg publishes 46 flairs, so the merge path is unreachable. Expected orderings in the
tests were produced by running that comparator in Node, not by this implementation.

### Combos

chat-gui collapses N identical consecutive emote-only messages into one line with a combo
counter. Pure chat-gui behaviour, not part of emote rendering, and **out of scope**. Noted
so it is a deliberate omission rather than an oversight.

---

## Drawing the emote

### A font, not a chat mixin

Emotes are glyphs in a font of their own, `dggchat:emotes`, one private-use codepoint per
emote from `U+E000`. Minecraft's text renderer then handles baseline, advance width,
wrapping, shadow, scaling and scrolling, and nothing has to fight the other mods on the pack
that touch chat.

Minecraft's glyph provider types are a closed enum, so a provider backed by images fetched
at runtime has no declarative way in. One mixin installs it: `FontSet.reload`, one
parameter, and every font except ours is handed back exactly what it was given. Sodium, Iris
and ImmediatelyFast do not go near it. If it ever fails to apply, `DggFont.isActive()` stays
false and chat leaves emote names as plain text rather than filling the screen with
missing-glyph boxes.

Two details in the provider that are not obvious:

- It claims the whole private-use block up front and answers unclaimed codepoints with a
  transparent glyph rather than null. `FontSet` decides at reload time which providers to
  keep by asking each one for every codepoint it claims and dropping any that answers null
  to all of them, so a provider whose manifest has not downloaded yet would be discarded for
  the rest of the session.
- It reads the glyph table live rather than snapshotting it, for the same race in the other
  direction.

**Animation re-uploads pixels into the glyph's atlas slot**, because a chat line's
`FormattedCharSequence` is built once when the message arrives and never rebuilt; changing
what the texture contains is the only thing that reaches an already-wrapped line.
`FontTexture#add` binds the atlas page before calling `SheetGlyphInfo#upload`, so reading
the GL binding at that moment is how a later frame knows where to write.

Animation advances on a **wall clock** and once per rendered frame, never on ticks: emotes
are baked at 20 to 30 frames a second and a 20-tick clock would alias, and a frame clock
keeps chat moving while the game is paused with chat open.

Scale is two tile pixels per unit of text. destiny.gg sets chat at 14px on a 19px line, so a
30px emote is about 1.6 lines tall; Minecraft's chat line is 9 units, so the same emote
wants roughly 15. Emotes overflowing the line is not a bug — it is what `margin-top: -H`
does on the site.

### The bake

Reproducing `emotes.css` in Java would mean writing a CSS animation engine, and a useful
subset would still leave dozens of emotes as still images forever. So Chromium renders it
once, offline, and the mod ships the frames. Details are in [`baker/README.md`](baker/README.md).

What that produced, from the current live CSS:

| Emote group | Count |
|---|---|
| plain image, no animation | 188 |
| CSS animation | 85 |
| animation baked into the file (GIF, WebP, AVIF) | 49 |
| both | 2 |

Fourteen emotes are sampled below full detail at the default 120-frame ceiling and the bake
names every one of them in its summary. A silently truncated animation reads as a faithful
one right up until someone notices it stutters.

What the bake cannot capture is anything depending on surrounding context: `AMOGUS`
hue-rotating by `nth-child` position, `.GIGACHAD + .ApeHands` reacting to its neighbour, and
`:hover`, which does not exist in Minecraft chat. Those bake in their default context only.

### Where the baked output lives

GitHub Pages, published by the `bake` workflow:

```
https://mcdggnet.github.io/dgg-chat-mod/manifest.json
```

The client reads `manifestUrl` from `config/dggchat.properties`, which the mod writes on
first run, and the `dggchat.manifest` system property overrides it. Pointing a custom domain
at the same Pages site later changes one config line and nothing else.

An earlier draft treated serving derived copies of Destiny.gg's emotes as an open licensing
question. It is not one: this is the Destiny.gg Minecraft server, `mc.destiny.gg`, rendering
Destiny.gg emotes for the people who already watch them on destiny.gg.

Not committed to the repo, though, for a duller reason: 12MB of generated PNGs regenerated
whenever destiny.gg adds an emote is not something git history should carry. Pages takes it
as a build artifact instead.

The workflow runs daily but publishes rarely. It hashes the four documents the bake reads
and stops unless one of them changed, so almost every run ends having done nothing.
Bandwidth is not a concern either: clients fetch an emote the first time they see it and
cache it on disk, so a full server is a few megabytes each, once, against Pages' 100GB
monthly allowance.

### Failure behaviour

Unknown name, failed download, decode error, emote missing from the manifest: fall through
to plain text. A stale cached copy always beats a missing one, so a CDN blip returns the
bytes already on disk rather than nothing. Chat never throws and the render thread never
waits on the network.

---

## The identity half

### Where identity comes from: another mod

**This mod does not look identity up itself.** `dggauth-proxy` already resolves DGG
identities at login and already runs on this server as a NeoForge mod. This mod consumes
that through a `ServiceLoader` SPI it owns: `DggIdentitySource` in the `api` module, which
any mod on the classpath can implement via `META-INF/services`.

NeoForge mods share a classloader in 1.21.1, so this works across jars, and the coupling is
loose in the right direction. This mod has no compile-time knowledge of `dggauth`; if
nothing implements the SPI, the server half stays dormant and clients get plain names.
`dggauth` compiles against the tiny `dgg-chat-api` artifact as `compileOnly`, so if this mod
is absent nothing references the missing types.

A provider that throws is logged and skipped rather than allowed to stop chat from loading.

### What goes over the wire

Server to client, on an optional payload, as a list so a player joining a busy server gets
everyone at once rather than one packet per person:

| Field | Why |
|---|---|
| Minecraft UUID | the key everything else is joined on |
| DGG nickname | display name, with DGG's casing |
| `features[]` | flair names, verbatim, e.g. `["subscriber","flair33","moderator"]` |
| sub tier | for emote gating, if that is ever enabled |

That is `DggIdentity` minus `ban`, which is the auth service's business and not chat's.

Deliberately **not** on the wire: colours, icon URLs, priorities. The client resolves
appearance from feature names exactly as the site does; sending resolved colours would fork
that logic and go stale.

Lookups run off the server thread. A provider may still be resolving a player at the moment
they join, so an empty answer is retried at two seconds, ten and thirty before giving up.

### Rainbow names

Two flairs, `flair33` and `flair42`, set `rainbowColor: true` and render as a repeating
linear gradient through nine hues at `hsl(h, 100%, 65%)`, scrolled by `animation: move 3s
linear infinite`. Nine stops evenly spaced from 0% to 50% of a gradient line twice the
element wide, so one full hue cycle covers exactly one element width.

Minecraft colours text per character, so each character samples that same ramp at its own
position. Interpolation is between the stop colours in sRGB rather than between hues,
because that is what a CSS gradient does.

**The gradient does not scroll.** It is sampled once as the message arrives. A chat line's
wrapped form is built on arrival and never rebuilt, so animating it would mean re-wrapping
the whole chat buffer every frame on a pack that cannot spare it.

### Flair icons

Rendered before the name, as the site does: only features present in the catalogue, hidden
flairs dropped (21 of the 46, including `moderator`, `protected` and `admin`, which colour
the name but show no icon), sorted by the CSS `order` value rather than by `priority` or by
the order in `features`. Equal `order` values are common — most visible flairs sit at 127 —
and flexbox falls back to document order there, so catalogue order stands in for it.

---

## Compatibility with ATM10

"Do not break the pack" is a hard requirement, not a preference.

- **Optional payloads, and a `hasChannel` guard on every send.** See rule 1 at the top.
- **`displayTest = "IGNORE_ALL_VERSION"`** so the server does not advertise itself as
  incompatible to clients that lack the mod.
- **The client half is a separate `@Mod(dist = Dist.CLIENT)` entry point**, so a dedicated
  server never loads a class that touches rendering.
- **One mixin, on one vanilla method that no rendering mod touches.** A large part of why a
  font was chosen over a chat-renderer mixin in the first place.
- **Nothing is bundled that ATM10 already ships.** Gson comes from Minecraft; the only
  jarJar'd artifacts are this project's own `api` and `core`.
- **Fetching happens off-thread with a disk cache**, on threads this mod owns rather than
  Minecraft's shared pool, so a slow CDN never becomes a tick stall or a client stutter.

---

## Build layout

A Gradle multi-module build where the NeoForge module is isolated, so building the parts
that do not touch Minecraft never triggers ModDevGradle's decompile step.

| Module | Contents |
|---|---|
| `api` | the `DggIdentitySource` SPI and `DggChatIdentity`. No Minecraft, no NeoForge. |
| `core` | chat-gui's rules: emote matching, flair resolution, username colour, the manifest model, the asset cache. Also platform-free, which is the point: the parts that have to match destiny.gg are the parts worth testing without a game around. |
| `neoforge` | the mod. |
| `baker` | the Playwright bake. Node, not Java; runs in CI, never in the game. |

### Building

```bash
./gradlew build            # everything, including tests
./gradlew :core:build      # no Minecraft download, builds in seconds
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
```

Java 21 is fetched by the toolchain if the machine has none. The first `:neoforge` build
downloads and decompiles Minecraft and is slow; after that it is cached.

### Releasing

Tags drive versions. `git tag v0.2.0 && git push --tags` publishes `0.2.0` and the `release`
workflow then builds and tests, publishes `dgg-chat-api`, `dgg-chat-core` and
`dgg-chat-neoforge` to **GitHub Packages** so `dggauth` can take the SPI as a `compileOnly`
dependency, and creates a **GitHub Release** with the mod jar attached.

The jar on the Release page is the download for players. Not a container registry: a
Minecraft mod is a jar you drop in a `mods` folder, and GHCR would only wrap it in an OCI
artifact no launcher can read.

Both workflows run on least privilege: `contents: read` by default, with the release job
asking for `contents: write` and `packages: write` and nothing else. Publishing
authenticates with the job-scoped `GITHUB_TOKEN`, so there is no long-lived credential
anywhere in the repo or its settings.

---

## Open questions

- **Emote gating by sender tier.** Implemented and off. Product call.
- **Scope beyond chat.** Signs, item names, nameplates come nearly free with a font-based
  implementation, with one caveat: those use Minecraft's fishy-glyph-filtering font, which
  rejects advances over 32 units, so the widest emotes would fall back to a missing glyph
  there. Chat itself does not filter.

## Known differences from the site

Deliberate, and each one is a trade rather than an oversight.

- **One animation timeline per emote, not per occurrence.** Every copy of an emote on screen
  shares a texture and therefore a clock, so they animate in lockstep and a new message
  restarts the older ones. Per-occurrence would need an atlas slot per occurrence.
- **Rainbow names do not scroll**, as above.
- **No context-dependent effects**: `nth-child` hue rotation, sibling-pair reactions, hover.
- **Fourteen long animations are sampled below full detail.** Named in every bake summary.

## Non-goals

- Fabric. No consumer.
- Combo counters, greentext, mentions, embeds, and the rest of chat-gui's formatter chain.
- **An embedded browser on the client.** [MCEF](https://modrinth.com/mod/mcef) has a NeoForge
  1.21.1 build, and chat-gui already abstracts its message source behind an `EventEmitter`,
  so running the real frontend against Minecraft chat instead of the websocket is genuinely
  feasible. It is still the wrong trade. Minecraft chat is not text, it is `Component`s
  carrying `ClickEvent` and `HoverEvent`, and `SHOW_ITEM` renders a real item tooltip through
  Minecraft's item renderer plus every mod's tooltip callbacks. None of that survives
  conversion to HTML, and on ATM10 it is load-bearing: quest notifications, advancement and
  death messages, teleport and waypoint links. Baking gets the pixel fidelity without any of
  it.
- Any change to `DGGServerPlugin`'s existing glyph substitution on the Paper servers. That
  path keeps working for players without this mod and is not this mod's business.

## Prior art

| Thing | Where |
|---|---|
| Emote names and animation grouping | `mcdggnet/DGGServerPlugin` → `EmoteRegistry.java` |
| Server-side chat rendering today | `DGGServerPlugin` → `ChatFormatListener.java` |
| DGG identity resolution, already deployed | `mcdggnet/dggauth-proxy` → `api`, `neoforge` |
| The rendering being copied | `destinygg/chat-gui` → `js/emotes.js`, `js/messages/ChatUserMessage.js`, `formatters/EmoteFormatter.js` |
