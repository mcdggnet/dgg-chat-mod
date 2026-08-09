# dgg-chat-mod

Renders Destiny.gg chat inside Minecraft the way destiny.gg renders it: real emote
images with animation, real flair icons, and the exact username colour a person has in
DGG chat.

**Status: design only. No code yet.** This document is the brief.

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

| URL | What it carries |
|---|---|
| `cdn.destiny.gg/emotes/emotes.json` | the emote list: name, image URL, intrinsic size |
| `cdn.destiny.gg/emotes/emotes.css` | **how each emote is actually drawn**, see below |
| `cdn.destiny.gg/flairs/flairs.json` | flair list: name, label, colour, priority, icon |
| `cdn.destiny.gg/flairs/flairs.css` | flair icon ordering and hidden flags |

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
ships fixed images. A client-side dynamic font has neither constraint: textures come off
the CDN and can be re-uploaded per frame to animate.

The alternative is mixing into the chat renderer and drawing quads directly. It gives
full control over transforms, and it is the only way to reach the bespoke effects in
tier 3 below, but it fights every other mod that touches chat and it means
reimplementing wrapping. Prefer the font; fall back to this only if a specific effect
justifies it.

Either way animation advances on a **wall clock**, never a tick counter: chat must look
right at low TPS and must keep animating while the game is paused with chat open.

### Fidelity tiers

Reproducing `emotes.css` exactly would mean implementing a CSS animation engine. The
declarations are hand-written and irregular: multi-track shorthands, arbitrary property
order, `!important`, custom properties, negative delays, `alternate-reverse`,
`cubic-bezier`, `::before` and `::after` content, and sibling selectors like
`.emote.GIGACHAD + .emote.ApeHands`. That is not worth building. Tier the work instead:

| Tier | What | Roughly | Plan |
|---|---|---|---|
| 0 | plain image, no override | 191 | draw it |
| 1 | animation baked into the file (GIF, WebP, AVIF) | 35 | decode frames and delays from the file |
| 2 | sprite strip stepped by `steps(N)` over `background-position` | 29 | frame index from the wall clock |
| 3a | single continuous track (transform, filter, opacity) | 41 | tier 0 for v1; a small tween subset later |
| 3b | multi-track, pseudo-element or sibling-dependent | 32 | tier 0, do not attempt |

Those counts are from classifying the live CSS today and will drift. **Do not hardcode
them.** Write a small generator that parses `emotes.css` and emits one
`emote-render.json` with the display geometry and, where the pattern is recognised, the
frame count, duration, iteration count and delay. Everything it does not recognise is
tier 0, which is a correct render, just a still one.

Generate at build time and ship the result, so a CDN change cannot break rendering
silently, and re-run it when emotes change. The runtime then needs no CSS parser at all.

Skip hover variants: roughly half the animation rules are `:hover` duplicates and there
is no hover in Minecraft chat.

### Failure behaviour

Unknown name, failed download, decode error, unparsed CSS: fall through to plain text.
Chat does not throw, and the render thread never waits on the network.

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

| Module | Contents |
|---|---|
| `api` | the `DggIdentitySource` SPI and the identity record. No Minecraft, no NeoForge. |
| `neoforge` | both halves of the mod, `side`-scoped. |
| `tools` | the `emotes.css` parser that generates `emote-render.json`. Plain Java, runs in CI. |

Whether this should instead become another module inside `dggauth-proxy` is worth
asking: it would share `api`, the toolchain and the release process, at the cost of
coupling a chat cosmetic to the auth service's release cadence. Separate repos with a
small shared API artifact is the safer default.

---

## Open questions

- **Sprite frame timing after tier 2.** The 29 stepped emotes are mechanical. The 41
  single-track tweens need a decision on whether a small transform and opacity
  interpolator is worth building, or whether still images are fine for them forever.
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
- **Asset licensing.** The emotes are Destiny.gg's. Fetching at runtime from their CDN is
  materially different from committing them to a repo, and is one more reason to fetch.

## Non-goals

- Fabric. No consumer.
- Combo counters, greentext, mentions, embeds, and the rest of chat-gui's formatter
  chain. Emotes and identity only.
- Reproducing tier 3b emote effects. Static is the correct answer for those.
- Any change to `DGGServerPlugin`'s existing glyph substitution on the Paper servers.
  That path keeps working for players without this mod and is not this mod's business.

## Prior art

| Thing | Where |
|---|---|
| Emote names and animation grouping | `mcdggnet/DGGServerPlugin` → `EmoteRegistry.java` |
| Server-side chat rendering today | `DGGServerPlugin` → `ChatFormatListener.java` |
| DGG identity resolution, already deployed | `mcdggnet/dggauth-proxy` → `api`, `neoforge` |
| The rendering being copied | `destinygg/chat-gui` → `js/emotes.js`, `js/messages/ChatUserMessage.js`, `formatters/EmoteFormatter.js` |
