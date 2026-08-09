# dgg-chat-mod

Renders Destiny.gg chat inside Minecraft the way destiny.gg renders it: real emote
images with animation, real flair icons, and the exact username colour a person has in
DGG chat.

**Status: skeleton.** The build, the identity SPI, the wire format and CI are real and
working. No emote rendering yet. This document is the brief for the rest.

Facts below about destiny.gg's data were verified against the live CDN and the
`destinygg/chat-gui` source on 2026-08-08. Where something is an open decision it says
so.

---

## Two halves, both optional

One jar, two sides. Neither side may ever be a requirement for the other.

| | client has the mod | client does not |
|---|---|---|
| **server has the mod** | full: emotes, flair icons, DGG names and colours | ordinary vanilla chat, unchanged |
| **server does not** | emotes only, matched from raw names in chat text | nothing happens |

Three rules fall out of that, and they are the ones most likely to be broken by accident:

1. **A vanilla or non-mod client must still be able to join a server running this mod.**
   In NeoForge 21.1 that means every custom payload is registered through
   `PayloadRegistrar#optional()`. A non-optional payload makes the channel mandatory and
   the server kicks anyone who does not have it, which is exactly the failure to avoid.
2. **A client running this mod must still be able to join any server**, vanilla or
   modded. Nothing arrives on the channel, so the identity half stays dormant and only
   emote rendering runs.
3. **The emote half never needs the server.** It works on raw emote names in chat text
   (`PEPE`, `glorp`), so it works on any server anywhere, including servers that have
   never heard of DGG.

## Target

Matches `dggauth-proxy`, which is already built and deployed against this stack.

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | NeoForge 21.1.248 |
| Pack | ATM10 7.3 |
| Java | release 21, toolchain 25 |
| Build | ModDevGradle 2.0.143 |

Fabric has no consumer here and should not be built.

---

## Where the data comes from

Four URLs. The site loads all four; so should the mod.

| URL | What it carries | Read by |
|---|---|---|
| `cdn.destiny.gg/emotes/emotes.json` | the emote list: name, image URL, intrinsic size | the baker |
| `cdn.destiny.gg/emotes/emotes.css` | **how each emote is actually drawn**, see below | the baker |
| `cdn.destiny.gg/flairs/flairs.json` | flair list: name, label, colour, priority, icon | the client |
| `cdn.destiny.gg/flairs/flairs.css` | flair icon ordering and hidden flags | the client |

Emote data is consumed by the offline baker rather than by the client, for reasons in
[Bake the animations with a real browser](#bake-the-animations-with-a-real-browser). The
client reads flairs directly and gets emotes as pre-rendered frames from `mcdgg.net`.
Everything below about emote data still matters, because the baker has to get it right.

Image URLs inside the CSS are relative, so they resolve against the CSS's own directory
(`cdn.destiny.gg/emotes/`), not against the versioned path in the JSON. The JSON's URLs
are absolute and version-pinned (`.../4.61.1/emotes/66596c571d8e5.png`); prefer those.

Note `cdn.destiny.gg` returns **403 to unusual user agents**. Send a normal one.

### emotes.json

324 entries. Every entry has exactly one image.

```json
{
  "prefix": "Abathur", "creator": "", "twitch": false, "theme": 1,
  "minimumSubTier": 0,
  "image": [{ "url": "https://cdn.destiny.gg/4.61.1/emotes/66596c571d8e5.png",
              "name": "66596c571d8e5.png", "mime": "image/png",
              "height": 30, "width": 82 }]
}
```

Currently 289 PNG, 30 GIF, 4 AVIF, 1 WebP. All are `theme: 1` and `twitch: false`; one
emote is gated at `minimumSubTier: 5` and the rest at 0. Do not hardcode any of that,
but it does mean the Twitch-sub code path in chat-gui is presently dead weight.

### emotes.css, and why it is not optional

**This is the part that is easy to get wrong.** `emotes.css` is two documents glued
together:

- **A generated head**, one block per emote, derived from the JSON: background image,
  plus `height` and `width` equal to the JSON's intrinsic image size.
- **A hand-written tail** (from roughly line 2916 on) with per-emote overrides:
  real display size, sprite-sheet cropping, keyframes, transforms, sibling rules.

The consequence: **for 45 emotes the JSON's width is not the width the emote is drawn
at.** Those images are horizontal sprite strips and the JSON reports the strip's size.

| emote | width in JSON | width drawn |
|---|---|---|
| `Askers` | 5544px | 56px |
| `BINGQILIN` | 6390px | 32px |
| `CuckCrab` | 2944px | 32px |
| `ACKSHUALLY` | 94px | 47px |

Render `Askers` at its JSON width and you get a 5544 pixel smear across the chat box. So
**a build that reads only `emotes.json` is wrong**, not merely less pretty. 43 of the 45
are sprite strips; the other two are half-width crops.

Vertical placement also comes from here. The base rule is
`.emote { display:inline-block; position:relative; overflow:hidden; text-indent:-999em }`
and each emote adds `margin-top: -<height>px; top: <height/4>px` inside `.msg-chat`. In
other words the emote hangs from the text baseline with a quarter of its height below.

### flairs.json and flairs.css

46 flairs:

```json
{ "label": "Admin", "name": "admin", "description": null, "hidden": true,
  "priority": 1, "color": "#EE1F1F", "rainbowColor": false,
  "image": [{ "url": ".../admin.png", "mime": "image/png", "height": 16, "width": 16 }] }
```

The colour and `hidden` values in the JSON agree with `flairs.css` exactly: all 46 were
cross-checked, zero mismatches. So **the JSON alone is enough for colours and hidden
flags**, unlike emotes.

One thing lives only in the CSS: **icon display order**, via the flex `order` property
(`.flair.flair33 { order: 3 }`, values seen from 2 to 127). `priority` in the JSON drives
username colour, not icon order, and the two are different numbers. Parse `order` out of
the CSS or the flair row will be in the wrong sequence.

---

## Emote matching: copy chat-gui exactly

From `assets/chat/js/emotes.js` and `formatters/EmoteFormatter.js`:

```js
new RegExp(`(^|\\s)(${prefixes.join('|')})(?=$|\\s)`, 'gm')
```

Which means, precisely:

- **Whitespace-delimited only.** `PEPE` in `xPEPEy` is not an emote. The trailing
  lookahead is what stops longer names being shadowed by shorter ones, so
  `PEPELAUGH` still wins over `PEPE` regardless of list order.
- **Case sensitive.** `pepe` is not `PEPE`.
- **Adjacent emotes both match**, because the leading whitespace is captured rather
  than consumed.

Two behaviours worth deciding on rather than inheriting blindly:

- **Sender tier gating.** chat-gui builds the regex from the emotes *the sender is
  allowed to use*, so a non-subscriber typing a tier-5 emote name gets plain text. This
  needs sender identity, so it only works with the server half installed. Without it,
  render every emote for everyone. With it, matching DGG exactly is possible; whether
  that is desirable on a Minecraft server is a call to make.
- **Formatter order.** chat-gui runs `html → amazon → url → emote → mentioned → green →
  sus → embed → badwordscensor`. Only the relative order of URL and emote matters here:
  URLs are linkified first, so an emote name inside a URL is not replaced.

### Combos

chat-gui collapses N identical consecutive emote-only messages into one line with a
`N X Hits C-C-C-COMBO` counter, with size steps at 2, 5, 10, 20, 30 and 50. This is
pure chat-gui behaviour, not part of emote rendering, and it is **out of scope for v1**.
Noted so it is a deliberate omission rather than an oversight.

---

## Drawing the emote

### How to get it on screen

The central technical decision, and it should be made before anything else is built.

**Recommended: a client-side dynamic font.** Register a font with a `GlyphProvider`
that maps each emote to a private-use codepoint backed by a texture fetched at runtime.
Minecraft's own text renderer then handles baseline, advance width, wrapping, shadow and
scaling, and emotes work anywhere text is drawn.

This is **not** the thing the earlier draft of this brief rejected. That was the *server
plugin's* resource-pack glyph substitution, which is static because a resource pack
ships fixed images. A client-side dynamic font has neither constraint: textures are
fetched at runtime and can be re-uploaded per frame to animate.

The alternative is mixing into the chat renderer and drawing quads directly. It gives
full control, but it fights every other mod that touches chat and it means
reimplementing wrapping. Prefer the font; fall back to this only if a specific effect
justifies it.

A third option, an embedded browser rendering chat-gui itself, was considered and
rejected. See non-goals for why.

Either way animation advances on a **wall clock**, never a tick counter: chat must look
right at low TPS and must keep animating while the game is paused with chat open.

### Bake the animations with a real browser

Reproducing `emotes.css` in Java would mean writing a CSS animation engine. The
declarations are hand-written and irregular: multi-track shorthands, arbitrary property
order, `!important`, custom properties, negative delays, `alternate-reverse`,
`cubic-bezier`, `::before` and `::after` content, and sibling selectors like
`.emote.GIGACHAD + .emote.ApeHands`. Hand-implementing a useful subset would still leave
roughly 73 emotes as still images forever.

So do not write the engine. **Let Chromium render the CSS once, offline, and ship the
frames.**

A headless browser driven by Playwright loads the real `emotes.css`, mounts each emote
in the same `.msg-chat` DOM context the site uses, advances the clock deterministically
through the DevTools Protocol's virtual time, and screenshots each frame. Out comes a
sprite sheet plus timings per emote. Because the frames were produced by the actual
engine, bespoke effects come out pixel-exact rather than approximated.

The client then holds no CSS parser and no browser. It picks a frame off a wall clock.

| Emote group | Roughly | Source of frames |
|---|---|---|
| plain image, no override | 191 | the image itself, no bake needed |
| animation baked into the file (GIF, WebP, AVIF) | 35 | decode frames and delays from the file |
| sprite strip stepped by `steps(N)` | 29 | already frames; slice by the CSS geometry |
| single continuous track (transform, filter, opacity) | 41 | **bake** |
| multi-track, pseudo-element or sibling-dependent | 32 | **bake** |

Counts are from classifying the live CSS today and will drift. **Do not hardcode them.**
The bake decides per emote which group applies; a still image is still the right output
for the 191.

Two details the bake has to get right:

- **Transforms draw outside the emote box.** `translate` and `scale` push pixels past the
  declared width and height, so capture a padded canvas and record the offset back to the
  text baseline, rather than cropping to the CSS box and clipping the effect.
- **Skip `:hover` variants.** Roughly half the animation rules are hover duplicates and
  there is no hover in Minecraft chat.

What the bake cannot capture is anything depending on surrounding context: `AMOGUS`
hue-rotating by `nth-child` position, `.GIGACHAD + .ApeHands` reacting to its neighbour,
and hover. Those bake in their default context only. That is a small and enumerable
loss, against 73 emotes that would otherwise never animate at all.

### Where the baked output lives

Serve it from `mcdgg.net`, not from the repo and not from destiny.gg:

- **Not the repo**, because committing baked emotes means committing derived copies of
  Destiny.gg's assets. Fetching keeps that at arm's length, which was already the
  argument for fetching in the first place.
- **Not destiny.gg**, because the whole point is that the frames are ours to generate.
  Their CDN has no baked form to serve.

A cron re-bakes when `emotes.json` changes, so a new emote appears for players without a
mod release. The client fetches one manifest plus atlases and caches them on disk. This
also sidesteps the CDN's 403-on-unusual-user-agent behaviour, since only the baker talks
to destiny.gg.

Flairs stay a direct client-side fetch from destiny.gg. They are static images with no
animation and nothing to bake.

### Failure behaviour

Unknown name, failed download, decode error, emote missing from the bake manifest: fall
through to plain text. Chat does not throw, and the render thread never waits on the
network.

---

## The identity half

### What it is for

So a message from a DGG tier 4 sub with a mod flair renders in Minecraft with the same
icons and the same name colour it would have in destiny.gg chat.

### Where identity comes from: another mod

**This mod does not look identity up itself.** `dggauth-proxy` already resolves DGG
identities at login and already runs on this server as a NeoForge mod. This mod consumes
that.

As of `dggauth-proxy` commit `56eb863` the record carries everything needed:

```java
record DggIdentity(UUID minecraftUuid, Optional<String> dggId, Optional<String> dggNick,
                   Optional<DggBan> ban, int subTier, List<String> features)
```

The handoff should be a **`ServiceLoader` SPI owned by this mod**: declare a
`DggIdentitySource` interface, and let any mod on the classpath provide an
implementation via `META-INF/services`. NeoForge mods share a classloader in 1.21.1, so
this works across jars, and the coupling is loose in the right direction:

- This mod has no compile-time knowledge of `dggauth`. If nothing implements the SPI, the
  server half stays dormant and clients get plain names.
- `dggauth` compiles against a tiny API artifact as `compileOnly` and declares an
  optional dependency. Its implementing class is only loaded when this mod's
  `ServiceLoader` looks for it, so if this mod is absent nothing references the missing
  types and nothing breaks.

`features` is the field flair icons and username colour are computed from, and it is
already parsed from `userinfo.features` in the lookup response and passed through
**verbatim**, deliberately untranslated: appearance is resolved against destiny.gg's own
`flairs.json` by whoever renders it, so resolving it upstream would fork that logic and
go stale. Missing key, `null`, and non-array all degrade to an empty list rather than
throwing, so an unlinked or partially-resolved player renders as a plain name.

That closes the one hard blocker this brief previously carried. Nothing else about
identity needs to change in `dggauth` before work starts here.

### What goes over the wire

Server to client, on an **optional** payload, sent once per player when they join or
when their identity resolves, plus a small delta when it changes:

| Field | Why |
|---|---|
| Minecraft UUID | the key everything else is joined on |
| DGG nickname | display name, with DGG's casing |
| `features[]` | flair names, verbatim, e.g. `["subscriber","flair33","moderator"]` |
| sub tier | for emote gating, if that is enabled |

That is `DggIdentity` minus `ban`, which is the auth service's business and not chat's.

Deliberately **not** on the wire: colours, icon URLs, priorities. The client already has
`flairs.json` and `flairs.css` from the CDN and computes appearance from feature names,
exactly as the site does. Sending resolved colours would fork the logic and go stale.

Also worth carrying: a protocol version, and a capability handshake on join, so a newer
client and an older server degrade instead of misparsing.

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

Read carefully, because the details are what make it match:

- Sort is **ascending by priority**, so *lower* priority number wins.
- The comparator returns `1` for equal priorities rather than `0`, which reorders ties.
  Reproduce that behaviour rather than writing a clean comparator, or two flairs at the
  same priority can resolve to different colours than the site shows.
- Only the **first** flair with a colour wins. Others contribute icons only.
- The site puts the winning flair's *name* on the element and lets `flairs.css` supply
  the colour, but since JSON and CSS colours were verified identical, reading `color`
  straight from the JSON is equivalent and simpler.

### Rainbow names

Two flairs (`flair33`, `flair42`, both tier 5) set `rainbowColor: true` and render as a
scrolling gradient: a repeating linear gradient through 9 hues at
`hsl(h, 100%, 65%)`, clipped to the text, at `background-size: 200% 100%`, scrolled by
`animation: move 3s linear infinite`.

Minecraft text components colour per character, not per gradient, so the faithful
approximation is a per-character colour sampled from that same hue ramp, offset by a
wall-clock phase with a 3 second period. Splitting a name into one component per
character is cheap at chat volumes.

### Flair icons

Render the icon row before the name, as the site does:

- Include only features present in `flairs.json`; ignore unknown ones.
- Drop anything `hidden: true`. That is 21 of the 46, including `moderator`, `protected`
  and `admin`, which colour the name but show no icon.
- Sort by the CSS `order` value, not by `priority` and not by the order in `features`.
- Icons are small, 13 to 20px wide and 15 to 19px tall, PNG and WebP. They need the same
  texture pipeline as emotes; reuse it rather than building a second one.
- **Tolerate a flair with no image.** `flair125` (Head Mod) has an `image` entry whose
  every field is `null`. It is hidden, so nothing renders today, but the parser must not
  assume a usable URL is present.

---

## Compatibility with ATM10

"Do not break the pack" is a hard requirement, not a preference.

- **Optional payloads, always.** See rule 1 at the top. This is the single most likely
  way to break the server for people without the mod.
- **Set `displayTest` in `neoforge.mods.toml`** so the server does not advertise itself
  as incompatible to clients that lack the mod.
- **Declare both sides properly.** `dggauth` scopes its dependencies with
  `side = "SERVER"`; this mod has real work on both sides, so scope each piece and make
  sure the client half is never loaded server-side or the reverse.
- **Audit the pack for other chat mods before choosing the render hook.** ATM10 ships
  around 500 mods and the client list already includes performance mods that touch
  rendering, notably Sodium, Iris and ImmediatelyFast. A font-based approach is far less
  likely to collide than a chat-renderer mixin, which is a large part of why it is
  recommended.
- **Do not bundle anything ATM10 already has.** The pack ships its own JEI, Jade and
  ModernFix; the same trap applies to any library dependency.
- **Fetching happens off-thread with a disk cache**, so a slow CDN never becomes a
  server tick stall or a client stutter on a pack that is already heavy.

---

## Build layout

Follow `dggauth-proxy`: a Gradle multi-module build where the NeoForge module is
isolated, so building the parts that do not touch Minecraft never triggers
ModDevGradle's decompile step.

| Module | Contents | State |
|---|---|---|
| `api` | the `DggIdentitySource` SPI and `DggChatIdentity`. No Minecraft, no NeoForge. | exists |
| `neoforge` | the mod. Optional payload registration and `ServiceLoader` discovery. | skeleton |
| `baker` | the Playwright bake pipeline and its manifest writer. Not Java; runs in CI, not in the game. | not started |

Whether this should instead become another module inside `dggauth-proxy` is worth
asking: it would share `api`, the toolchain and the release process, at the cost of
coupling a chat cosmetic to the auth service's release cadence. Separate repos with a
small shared API artifact is the safer default.

### Building

```bash
./gradlew build          # everything, including tests
./gradlew :api:build     # no Minecraft download, builds in seconds
```

Java 21 is fetched by the toolchain if the machine has none, so no local JDK setup is
required. The first `:neoforge` build downloads and decompiles Minecraft and is slow;
after that it is cached.

### Releasing

Tags drive versions. `git tag v0.2.0 && git push --tags` publishes `0.2.0`, whatever
`modVersion` says in `gradle.properties`, and the `release` workflow then:

1. builds and runs tests,
2. publishes `dgg-chat-api` and `dgg-chat-neoforge` to **GitHub Packages**, so
   `dggauth` can take the SPI as a `compileOnly` dependency,
3. creates a **GitHub Release** with the mod jar attached.

The jar on the Release page is the download for players. Not a container registry: a
Minecraft mod is a jar you drop in a `mods` folder, and GHCR would only wrap it in an
OCI artifact that no launcher can read. Modrinth and CurseForge are the other real
distribution channels if this ever goes wider than the server's own players.

Both workflows run on least privilege: `contents: read` by default, with the release
job asking for `contents: write` and `packages: write` and nothing else. Publishing
authenticates with the job-scoped `GITHUB_TOKEN`, so there is no long-lived credential
anywhere in the repo or its settings.

---

## Open questions

- **Bake frame rate and size budget.** Frames are a straight trade of fidelity against
  download and VRAM. Some animations are long: `catJAM` runs 6.5s at 188 steps and
  `BINGQILIN` is a 6390px strip. Pick a capture rate, decide whether to dedupe identical
  frames, and set a per-emote frame ceiling.
- **Padding for transforms.** How much canvas to leave around the CSS box before pixels
  start getting clipped. Measurable from the CSS rather than guessed, but it needs doing.
- **Animation lifetime.** On the site an animation runs a fixed number of iterations
  from when the message appears, then stops. If emotes share one animated texture per
  emote type, every instance shares a timeline and they animate in sync. Simpler, and
  probably fine, but it is a visible difference.
- **Emote gating by sender tier.** Faithful to DGG, but it makes a Minecraft player's
  emote render depend on their DGG subscription. Product call, not a technical one.
- **Cache policy.** Keyed by URL with a periodic refresh. Decide whether a stale emote or
  a missing emote is the better failure; stale is almost certainly right.
- **Scope beyond chat.** Signs, item names, nameplates. Chat first. A font-based
  implementation gets these nearly for free, which is another argument for it.
- **Asset licensing, and baking makes this a real question rather than a footnote.** The
  emotes are Destiny.gg's. A client fetching them from destiny.gg's own CDN is plainly
  fine. Generating derived frames and serving them from `mcdgg.net` is redistribution,
  even though it is the same pixels for the same audience. Worth asking rather than
  assuming, given this is a DGG community server rendering DGG emotes for DGG viewers.

## Non-goals

- Fabric. No consumer.
- Combo counters, greentext, mentions, embeds, and the rest of chat-gui's formatter
  chain. Emotes and identity only.
- **An embedded browser on the client.** [MCEF](https://modrinth.com/mod/mcef) has a
  NeoForge 1.21.1 build, and chat-gui already abstracts its message source behind an
  `EventEmitter` (`MockChatSource` is a working drop-in), so running the real frontend
  against Minecraft chat instead of the websocket is genuinely feasible. It is still the
  wrong trade. Minecraft chat is not text, it is `Component`s carrying `ClickEvent` and
  `HoverEvent`, and `SHOW_ITEM` renders a real item tooltip through Minecraft's item
  renderer plus every mod's tooltip callbacks. None of that survives conversion to HTML,
  and on ATM10 it is load-bearing: quest notifications, advancement and death messages,
  teleport and waypoint links. The cost is real too, a per-client Chromium fetched from
  a third-party host, but the lost chat behaviour is the actual reason. Baking gets the
  pixel fidelity without any of it.
- Context-dependent emote effects: `nth-child` hue rotation, sibling-pair reactions, and
  hover. The bake captures the default context only.
- Any change to `DGGServerPlugin`'s existing glyph substitution on the Paper servers.
  That path keeps working for players without this mod and is not this mod's business.

## Prior art

| Thing | Where |
|---|---|
| Emote names and animation grouping | `mcdggnet/DGGServerPlugin` → `EmoteRegistry.java` |
| Server-side chat rendering today | `DGGServerPlugin` → `ChatFormatListener.java` |
| DGG identity resolution, already deployed | `mcdggnet/dggauth-proxy` → `api`, `neoforge` |
| The rendering being copied | `destinygg/chat-gui` → `js/emotes.js`, `js/messages/ChatUserMessage.js`, `formatters/EmoteFormatter.js` |
