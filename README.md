# dgg-chat-mod

Client-side Minecraft mod that renders Destiny.gg emotes in chat the way destiny.gg
renders them, including animation.

**Status: design only. No code yet.** This document is the brief.

Target: **Minecraft 1.21.1 / NeoForge 21.1.x**, matching the ATM10 server. Client-side
only, and it must be optional: a player without it should see chat degrade to what they
see today, never break.

---

## The problem

Emotes already reach the client. They just cannot move.

`DGGServerPlugin` (the Paper server plugin) carries an `EmoteRegistry` that maps emote
names to Unicode codepoints:

- `emotes_map.json` maps a name to a codepoint, e.g. `glorp` -> `0xE0xx`
- `emotes_anim.json` maps an animation base to an **array** of codepoints, one per frame
- Frame names follow `^(.+)_([0-9]+)$`, so `glorp_0`, `glorp_1`, `glorp_2` are the frames of `glorp`

Chat messages have emote words replaced with those glyphs, and a resource pack supplies
a texture per glyph. That is why emotes appear at all without a mod.

The limitation is that **a resource pack glyph is a single static image**. An animated
DGG emote is already split into per-frame glyphs server-side, but vanilla has no way to
cycle them, so the client shows one frame forever. Nothing on the server can fix this:
the animation has to happen at render time, on the client.

## What the mod has to do

1. **Recognise emote glyphs in chat.** The private-use codepoints the resource pack
   defines. The mod needs the same `emotes_map.json` / `emotes_anim.json` data the
   server uses, so those files (or an endpoint serving them) are the shared contract.
2. **Animate.** When a glyph belongs to an animation base, cycle its frames at the
   correct rate rather than drawing frame 0. This is the whole point of the mod.
3. **Match destiny.gg's presentation.** Emote size relative to text, alignment on the
   text baseline, and spacing. Getting this wrong makes chat look worse than the
   static version, so it is worth comparing against the site directly.
4. **Degrade safely.** Any unknown glyph, missing frame, or malformed data must fall
   through to vanilla rendering. Chat is not a place to throw.

## Open questions for whoever builds this

- **Where does emote data come from?** Bundled in the jar (simple, goes stale), fetched
  from mcdgg.net at startup (fresh, needs an endpoint and a cache), or read from the
  resource pack itself (no new plumbing, but the pack has no animation metadata). This
  is the first decision and it shapes everything else.
- **What frame rate?** `emotes_anim.json` stores frames but, as far as I can tell, no
  timing. Either a fixed rate for all emotes, or timing has to be added to that file,
  which means changing the server plugin too.
- **Does this belong in chat only?** Signs, item names and nameplates can carry the same
  glyphs. Chat first; the rest is scope creep until asked for.
- **How are combos handled?** destiny.gg supports emote modifiers (`:wide`, `:spin` and
  similar). Whether those survive the server's text-to-glyph substitution at all needs
  checking before promising anything.

## Where to look first

| Thing | Where |
|---|---|
| Emote name to codepoint mapping | `mcdggnet/DGGServerPlugin` -> `EmoteRegistry.java` |
| Animation frame arrays | same, `getAnimation(base)` and `emotes_anim.json` |
| Glyph to name map used for hover text | `ChatFormatListener` -> `glyphToEmoteName` |
| How chat is currently rendered server-side | `ChatFormatListener` (Paper `ChatRenderer`) |

`EmoteRegistry` is the important one. It is the existing, working answer to "what is
this glyph", and the mod should share its data rather than invent a second source of
truth that can drift.

## Constraints worth stating up front

- **Client-side only.** No server component, no packets. If the design starts needing
  server changes, that is a signal to re-scope.
- **NeoForge 1.21.1.** Same loader and version as ATM10. A Fabric build has no consumer.
- **Optional.** Never required to join, and never a reason someone cannot read chat.
- **Emote assets are Destiny.gg's.** Redistributing them in a public repo is a question
  worth answering before bundling any images.
