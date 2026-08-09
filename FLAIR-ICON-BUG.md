# Flair icons never render: the bake drops image data

Diagnosed 2026-08-09 against the live ATM10 server. Not an identity problem, and
not a font problem. Both of those are healthy.

## Symptom

A player with `features = [flair5, flair1, subscriber]` sees their name in chat with
no icon before it. `flair1` and `subscriber` are correctly hidden; `flair5` should draw.

## What is already working

Ruled out with evidence rather than assumption:

| | Evidence |
|---|---|
| Identity resolves | dggauth: `SPI asked for e71ee54c… -> Rooyal features=[flair5, flair1, subscriber]` |
| Retry works | first query returned EMPTY at join, retry two seconds later got the identity |
| Font provider active | client: `emote font installed; 324 emote and 25 flair glyphs known so far` |
| Catalogue loads | client: `loaded 324 emotes and 46 flairs, baked 2026-08-09T02:11:33Z` |
| Chat renders | client: `[CHAT] Rooyal: no flairs` |

Note there are **25 flair glyphs in the font**, so the images were rasterised. Only the
eligibility check fails.

## Root cause

`FlairCatalogue.parse` builds each `Flair` from an image object:

```java
JsonObject image = firstImage(object);
...
image == null ? null : string(image, "url"),
image == null ? 0    : integer(image, "width", 0),
image == null ? 0    : integer(image, "height", 0),
```

and `Flair.hasIcon()` gates on all three:

```java
return iconUrl != null && iconWidth > 0 && iconHeight > 0;
```

But the baked manifest carries no image data. Every flair entry in
`https://mcdggnet.github.io/dgg-chat-mod/manifest.json` has exactly these keys:

```
color, hidden, label, name, order, priority, rainbowColor
```

There is no `image` or `images`. So `firstImage()` returns null for all 46 flairs,
`hasIcon()` is false for all 46, and `icons()` returns an empty list for **every**
player. No icon has ever rendered for anyone.

Confirmed for the three flairs in question:

```
flair1      hidden=True   icon=False   order=2147483647
subscriber  hidden=True   icon=False   order=2147483647
flair5      hidden=False  icon=False   order=127          <- should draw, blocked by icon=False
```

`flair5` passes the hidden check and fails only on `hasIcon()`.

## The deeper inconsistency

The eligibility check and the renderer disagree about what "has an icon" means.

- **Eligibility** (`icons()` -> `hasIcon()`) asks whether the upstream **image URL** is known
- **Rendering** (`appendIcons` -> `DggFont.flairCharacter(name)`) uses the **baked font glyph**

Those are different sources. The font already has 25 flair glyphs; the manifest has zero
image URLs. So the gate consults something the renderer never uses, and rejects flairs the
renderer could happily draw.

## Two ways to fix

1. **Gate on the glyph, not the URL.** Have `icons()` include a flair when a baked glyph
   exists for it. This matches what rendering actually needs and makes the manifest's lack
   of image URLs irrelevant. Note `hasIcon()` lives in `core`, which has no font access, so
   the check probably belongs in `ChatDecorator` alongside the existing `DggFont.isActive()`
   guard rather than inside `FlairCatalogue`.

2. **Carry image data through the bake.** Keep `hasIcon()` as is and have the baker emit
   `url`/`width`/`height` per flair. More faithful to upstream `flairs.json`, but it stores
   data nothing reads, since drawing goes through the font.

Option 1 looks right: one source of truth, and it cannot drift from what the font contains.
Whichever is chosen, the two should agree, or this recurs the next time the bake changes.

## Not the problem

- Identity delivery. dggauth answers correctly, verified in the server log.
- `hasChannel` gating. Suspected early and cleared: `ClientIdentities` has no logging, so
  the absence of client-side identity lines proved nothing either way.
- The 46 flairs vs 25 glyphs gap. Expected, since most flairs are hidden.
