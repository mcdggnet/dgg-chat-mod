# dgg-chat-mod

Client-side Minecraft mod that renders Destiny.gg emotes in chat the way destiny.gg
renders them, including animation.

**Status: design only. No code yet.** This document is the brief.

Target: **Minecraft 1.21.1 / NeoForge 21.1.x**, matching the ATM10 server. Client-side
only, and optional: a player without it should see chat degrade to what they see today,
never break.

---

## The approach: fetch from the web, do not reuse the server's glyph system

The Paper plugin renders emotes by substituting them for Unicode glyphs that a resource
pack supplies as textures. **This mod should not copy that.** A modded client can load
images off the web at runtime, which removes the constraint the glyph system exists to
work around.

That difference matters more than it first appears:

| | Plugin (glyph + resource pack) | This mod (web assets) |
|---|---|---|
| Emote source | codepoints baked into a resource pack | fetched from destiny.gg's CDN |
| Animation | impossible: a glyph is one static image | native, frames are just image frames |
| Adding an emote | rebuild and redistribute the pack | appears automatically |
| Sizing and modifiers | fixed by the font metrics | free, we control the draw call |
| Server involvement | required, for text-to-glyph substitution | none |

So the mod works on **raw emote names in chat text** (`PEPE`, `glorp`), not on the
plugin's private-use codepoints. That means it does not depend on the server having
substituted anything, and it will work on any server, including vanilla ones, wherever
someone types an emote name.

## What it has to do

1. **Get the emote manifest from destiny.gg.** The site publishes emote definitions
   (name, image URL, and for animated emotes the sprite or frame data). Fetch on startup,
   cache to disk, refresh periodically. Getting this right is the whole foundation, so
   confirm the real endpoint and shape before writing anything else.
2. **Scan incoming chat for emote names.** Word-boundary matching against the manifest.
   Case matters on destiny.gg and should be preserved.
3. **Render the image inline.** Download and upload to a texture, cache it, then draw at
   text height aligned to the baseline. Minecraft's chat renderer draws text, so this
   means hooking the chat component rendering rather than rewriting the message string.
4. **Animate.** Advance frames on a wall-clock timer, not a tick counter: chat should
   look right regardless of TPS, and animations must keep running when the game is paused
   in a menu with chat open.
5. **Degrade safely.** Unknown name, failed download, decode error: fall through to
   plain text. Chat is not a place to throw, and it must never block the render thread
   waiting on a network call.

## Open questions

- **What exactly does destiny.gg serve?** The manifest URL, its schema, and how animated
  emotes are represented (APNG, GIF, CSS sprite sheet with steps, individual frames).
  This determines the decoder and is the first thing to pin down.
- **Sprite sheets need timing.** If animation is CSS-driven on the site, frame duration
  lives in CSS rather than the image, and the mod needs that timing from somewhere.
- **Cache policy.** Emotes change rarely; a disk cache keyed by URL with a periodic
  refresh is probably right. Decide whether a stale cache or a missing emote is the
  better failure.
- **Scope beyond chat.** Signs, item names and nameplates could carry emotes too. Chat
  first; the rest is scope creep until asked for.
- **Asset licensing.** The emotes are Destiny.gg's. Fetching them at runtime from their
  CDN is materially different from bundling them in a repo, and is one more reason to
  prefer fetching.

## Prior art in this codebase

Worth reading for what emote names exist and how they are grouped, even though the
rendering approach differs:

| Thing | Where |
|---|---|
| Emote name list and animation grouping | `mcdggnet/DGGServerPlugin` -> `EmoteRegistry.java` |
| How chat is rendered server-side today | `ChatFormatListener.java` |

`emotes_anim.json` in the plugin is a useful hint at **which** emotes are animated, even
though this mod will not use its codepoints.

## Constraints

- **Client-side only.** No server component, no packets, no plugin changes. If the design
  starts needing server-side work, that is a signal to re-scope.
- **NeoForge 1.21.1.** Same loader and version as ATM10. A Fabric build has no consumer.
- **Optional.** Never required to join, never a reason someone cannot read chat.
- **Never block the render thread.** All fetching and decoding happens off-thread, with
  the renderer drawing whatever is ready and plain text otherwise.
