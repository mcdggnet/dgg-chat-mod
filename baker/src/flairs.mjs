// Flairs, joined and normalised.
//
// The mod could fetch flairs.json straight from destiny.gg, and an earlier draft of the
// design said it should. Two things argue against it: two of the twenty-five visible icons
// are WebP and Minecraft's NativeImage reads PNG only, and the CDN answers 403 to user
// agents it does not recognise, which would put a browser-impersonating fetch in the
// client. Both problems disappear if the bake handles flairs too, and the client is then
// left talking to exactly one host.

import { writeFile } from 'node:fs/promises';
import path from 'node:path';

import { FLAIRS_CSS, FLAIRS_JSON, sha256 } from './cdn.mjs';

/** `.flair.<name> { ... }`, ignoring `.user.<name>`, which only sets a colour. */
const CSS_RULE = /\.flair\.([A-Za-z0-9_-]+)\s*\{([^}]*)}/g;
const CSS_ORDER = /\border\s*:\s*(-?\d+)/;
const CSS_DISPLAY_NONE = /\bdisplay\s*:\s*none/;

/** After everything that has one, matching Flair.NO_ORDER on the Java side. */
const NO_ORDER = 2147483647;

function parseCss(css) {
  const rules = new Map();
  for (const [, name, body] of css.matchAll(CSS_RULE)) {
    const existing = rules.get(name) ?? { order: NO_ORDER, hidden: false };
    const order = CSS_ORDER.exec(body);
    rules.set(name, {
      // Later rules win, as they would in the cascade.
      order: order ? Number(order[1]) : existing.order,
      hidden: existing.hidden || CSS_DISPLAY_NONE.test(body),
    });
  }
  return rules;
}

export async function bakeFlairs(page, cdn, outDir) {
  const flairs = JSON.parse(await cdn.getText(FLAIRS_JSON));
  const css = parseCss(await cdn.getText(FLAIRS_CSS));

  const entries = [];
  const skipped = [];

  for (const flair of flairs) {
    if (!flair.name) continue;
    const rule = css.get(flair.name) ?? { order: NO_ORDER, hidden: false };
    const entry = {
      name: flair.name,
      label: flair.label ?? flair.name,
      hidden: Boolean(flair.hidden) || rule.hidden,
      priority: flair.priority ?? NO_ORDER,
      // Normalised to null: two flairs publish an empty string, which chat-gui treats as
      // no colour because it tests truthiness rather than presence.
      color: flair.color || null,
      rainbowColor: Boolean(flair.rainbowColor),
      order: rule.order,
    };

    // flair125 publishes an image entry whose every field is null. It is hidden, so
    // nothing renders today, but the parser must not assume a usable URL is there.
    const image = flair.image?.[0];
    if (image?.url && !entry.hidden) {
      try {
        const png = await page.evaluate((url) => window.__bake.toPng(url), image.url);
        if (png) {
          const digest = sha256(Buffer.from(png.base64, 'base64')).slice(0, 12);
          const file = `flair-${flair.name}.${digest}.png`;
          await writeFile(path.join(outDir, file), Buffer.from(png.base64, 'base64'));
          entry.iconFile = file;
          entry.iconWidth = png.width;
          entry.iconHeight = png.height;
        }
      } catch (error) {
        skipped.push(`${flair.name}: ${error.message}`);
      }
    }
    entries.push(entry);
  }

  return { entries, skipped };
}
