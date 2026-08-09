#!/usr/bin/env node
// Bake destiny.gg's emotes into frames the mod can draw.
//
//   node src/bake.mjs --out ../out            everything, which takes a while
//   node src/bake.mjs --only PEPE,Askers      one or two, for checking a change
//
// Output is a directory of one PNG per emote, each a vertical strip of frames, plus a
// manifest.json describing timing and geometry. Nothing here runs in the game.

import { mkdir, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

import { chromium } from 'playwright';

import { Cdn, EMOTES_CSS, EMOTES_JSON, sha256, sniffMime } from './cdn.mjs';
import { harnessHtml, PAD, VIEWPORT } from './harness.mjs';
import { bakeFlairs } from './flairs.mjs';
import { cropAndStack, decode, unionAlphaBounds } from './png.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const HARNESS_URL = 'https://cdn.destiny.gg/emotes/__bake_harness.html';

const MIME_BY_EXTENSION = {
  '.png': 'image/png',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.avif': 'image/avif',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.css': 'text/css',
  '.json': 'application/json',
};

function parseArgs(argv) {
  const options = {
    out: path.resolve(HERE, '..', 'out'),
    cache: path.resolve(HERE, '..', '.cache'),
    only: null,
    limit: Infinity,
    fps: 20,
    // High enough to hold the longest steps() strip whole: the biggest today is 99.
    maxFrames: 120,
    concurrency: 4,
    // Minecraft stitches glyphs into 256x256 font pages, so a tile must fit inside one.
    maxTile: 128,
  };
  for (let i = 0; i < argv.length; i++) {
    const [flag, inline] = argv[i].split('=');
    const value = inline ?? argv[++i];
    switch (flag) {
      case '--out': options.out = path.resolve(value); break;
      case '--cache': options.cache = path.resolve(value); break;
      case '--only': options.only = new Set(value.split(',').map((s) => s.trim()).filter(Boolean)); break;
      case '--limit': options.limit = Number(value); break;
      case '--fps': options.fps = Number(value); break;
      case '--max-frames': options.maxFrames = Number(value); break;
      case '--max-tile': options.maxTile = Number(value); break;
      case '--concurrency': options.concurrency = Math.max(1, Number(value)); break;
      default: throw new Error(`unknown option ${flag}`);
    }
  }
  return options;
}

/**
 * The period to sample over.
 *
 * When a CSS animation runs on top of an animated GIF the two rarely share a period, and
 * the honest cycle length is the least common multiple. That can be enormous, so it is
 * capped: past the cap the emote is sampled over the longer of the two and drifts, which
 * is a far better failure than emitting a thousand frames.
 */
function samplingPeriod(cssMs, containerMs, capMs) {
  if (cssMs <= 0) return containerMs;
  if (containerMs <= 0) return cssMs;
  const a = Math.round(cssMs);
  const b = Math.round(containerMs);
  let x = a;
  let y = b;
  while (y) [x, y] = [y, x % y];
  const lcm = (a / x) * b;
  return lcm > capMs ? Math.max(a, b) : lcm;
}

/**
 * When to screenshot, over exactly one iteration of the animation.
 *
 * A `steps(N)` animation is sampled at the midpoint of each step rather than on a frame
 * rate: `Askers` is 99 steps in 3.5s, which a 20fps sweep would render as 22 of its 99
 * distinct pictures. Everything else is sampled on the frame rate, since there is no
 * natural grid to land on.
 */
function planFrames(info, options) {
  const capMs = options.maxFrames * (1000 / options.fps);
  const periodMs = samplingPeriod(info.iterationMs, info.containerDurationMs, capMs);
  if (!(periodMs > 0)) {
    return { times: [0], frameMs: 0, periodMs: 0, capped: false };
  }

  const stepped = info.steps > 0 && info.containerFrames === 0;
  const wanted = stepped
    ? info.steps
    : Math.max(2, Math.round(periodMs / (1000 / options.fps)));
  const frameCount = Math.min(options.maxFrames, wanted);
  const frameMs = Math.max(1, Math.round(periodMs / frameCount));

  const times = stepped
    // Midpoints, so a capture never lands on the discontinuity between two steps.
    ? Array.from({ length: frameCount }, (_, i) => ((i + 0.5) * periodMs) / frameCount)
    : Array.from({ length: frameCount }, (_, i) => i * frameMs);

  return { times, frameMs, periodMs, capped: frameCount < wanted };
}

async function preparePage(browser, cdn, html) {
  const page = await browser.newPage({
    viewport: VIEWPORT,
    deviceScaleFactor: 1,
    // Nothing here is a real browsing session; keep it deterministic.
    reducedMotion: 'no-preference',
    colorScheme: 'dark',
  });

  await page.route('https://cdn.destiny.gg/**', async (route) => {
    const url = route.request().url();
    if (url === HARNESS_URL) {
      await route.fulfill({ status: 200, contentType: 'text/html; charset=utf-8', body: html });
      return;
    }
    try {
      const body = await cdn.get(url);
      const extension = path.extname(new URL(url).pathname).toLowerCase();
      await route.fulfill({
        status: 200,
        // Sniffed first: one emote is served as a .gif that is really a WebP, and the
        // page hands this header straight to ImageDecoder.
        contentType: sniffMime(body) ?? MIME_BY_EXTENSION[extension] ?? 'application/octet-stream',
        body,
      });
    } catch (error) {
      await route.fulfill({ status: 404, body: String(error) });
    }
  });

  await page.goto(HARNESS_URL, { waitUntil: 'load' });
  return page;
}

async function bakeEmote(page, emote, options) {
  const image = emote.image?.[0];
  if (!image?.url) return { prefix: emote.prefix, skipped: 'no image' };

  const info = await page.evaluate((prefix) => window.__bake.setup(prefix), emote.prefix);

  if (!(info.boxWidth > 0) || !(info.boxHeight > 0)) {
    return { prefix: emote.prefix, skipped: 'zero-sized box' };
  }

  const plan = planFrames(info, options);
  const clip = {
    x: Math.max(0, Math.floor(info.penX - PAD)),
    y: Math.max(0, Math.floor(info.baselineY - info.boxHeight - PAD)),
    width: Math.min(VIEWPORT.width, Math.ceil(Math.max(info.advance, info.boxWidth) + 2 * PAD)),
    height: Math.min(VIEWPORT.height, Math.ceil(info.boxHeight * 1.5 + 2 * PAD)),
  };

  const captured = [];
  for (const time of plan.times) {
    await page.evaluate((t) => window.__bake.seek(t), time);
    captured.push(decode(await page.screenshot({ clip, omitBackground: true, type: 'png' })));
  }

  const bounds = unionAlphaBounds(captured);
  if (!bounds) return { prefix: emote.prefix, skipped: 'renders nothing' };

  const sheet = cropAndStack(captured, bounds, options.maxTile);
  // A content hash in the filename means a re-bake invalidates the client's disk cache
  // for exactly the emotes that changed, and for no others.
  const digest = sha256(sheet.buffer).slice(0, 12);
  const file = `${emote.prefix}.${digest}.png`;

  return {
    prefix: emote.prefix,
    file,
    buffer: sheet.buffer,
    entry: {
      file,
      tileWidth: sheet.tileWidth,
      tileHeight: sheet.tileHeight,
      tileCount: sheet.tileCount,
      ...(sheet.scale < 1 ? { scale: round(sheet.scale) } : {}),
      frameCount: plan.times.length,
      frameMs: plan.frameMs,
      // 0 means loop forever; anything else stops on the last frame after that many passes.
      iterations: plan.periodMs > 0 ? info.iterations : 0,
      ...(sheet.sequence ? { sequence: sheet.sequence } : {}),
      advance: round(info.advance),
      bearingX: round(clip.x + bounds.x - info.penX),
      ascent: round(info.baselineY - (clip.y + bounds.y)),
      minimumSubTier: emote.minimumSubTier ?? 0,
    },
    kind: kindOf(info),
    capped: plan.capped,
  };
}

function kindOf(info) {
  if (info.animationCount > 0 && info.containerFrames > 0) return 'css+container';
  if (info.animationCount > 0) return 'css';
  if (info.containerFrames > 0) return 'container';
  return 'still';
}

function round(value) {
  return Math.round(value * 100) / 100;
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const cdn = new Cdn(options.cache);

  const emotesCss = await cdn.getText(EMOTES_CSS);
  const emotesJsonText = await cdn.getText(EMOTES_JSON);
  const allEmotes = JSON.parse(emotesJsonText);

  let selected = allEmotes;
  if (options.only) selected = selected.filter((e) => options.only.has(e.prefix));
  if (Number.isFinite(options.limit)) selected = selected.slice(0, options.limit);

  console.log(
    `baking ${selected.length} of ${allEmotes.length} emotes at ${options.fps}fps, `
      + `up to ${options.maxFrames} frames each, ${options.concurrency} pages`,
  );

  await rm(options.out, { recursive: true, force: true });
  await mkdir(options.out, { recursive: true });

  const html = harnessHtml(emotesCss);
  const browser = await chromium.launch({ args: ['--force-color-profile=srgb'] });

  const entries = {};
  const counts = { still: 0, css: 0, container: 0, 'css+container': 0 };
  const skipped = [];
  const capped = [];
  let done = 0;

  const queue = [...selected];
  const workers = Array.from({ length: options.concurrency }, async () => {
    const page = await preparePage(browser, cdn, html);
    for (let emote = queue.shift(); emote; emote = queue.shift()) {
      try {
        const result = await bakeEmote(page, emote, options);
        if (result.skipped) {
          skipped.push(`${result.prefix}: ${result.skipped}`);
        } else {
          await writeFile(path.join(options.out, result.file), result.buffer);
          entries[result.prefix] = result.entry;
          counts[result.kind]++;
          if (result.capped) capped.push(result.prefix);
        }
      } catch (error) {
        skipped.push(`${emote.prefix}: ${error.message}`);
      }
      done++;
      if (done % 10 === 0 || done === selected.length) {
        console.log(`  ${done}/${selected.length}`);
      }
    }
    await page.close();
  });

  await Promise.all(workers);

  const flairPage = await preparePage(browser, cdn, html);
  const flairs = await bakeFlairs(flairPage, cdn, options.out);
  await flairPage.close();
  await browser.close();

  // Sorted so a re-bake of unchanged input produces a byte-identical manifest.
  const sortedEntries = Object.fromEntries(
    Object.keys(entries).sort().map((prefix) => [prefix, entries[prefix]]),
  );
  const manifest = {
    version: 1,
    generatedAt: new Date().toISOString(),
    emoteCss: sha256(Buffer.from(emotesCss, 'utf8')),
    emotes: sortedEntries,
    flairs: flairs.entries,
  };
  await writeFile(path.join(options.out, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`);

  const tiles = Object.values(sortedEntries).reduce((sum, e) => sum + e.tileCount, 0);
  const icons = flairs.entries.filter((f) => f.iconFile).length;
  console.log(
    `\n${Object.keys(sortedEntries).length} emotes, ${tiles} tiles`
      + `\n  still ${counts.still}, css ${counts.css}, container ${counts.container}, both ${counts['css+container']}`
      + `\n${flairs.entries.length} flairs, ${icons} icons`,
  );
  for (const line of flairs.skipped) console.log(`  flair skipped ${line}`);
  // Never let a bound go unmentioned: a silently truncated animation reads as a faithful
  // one right up until someone notices it stutters.
  if (capped.length > 0) {
    console.log(`  sampled below full detail at --max-frames ${options.maxFrames}: ${capped.join(', ')}`);
  }
  if (skipped.length > 0) {
    console.log(`  skipped ${skipped.length}:`);
    for (const line of skipped) console.log(`    ${line}`);
  }
  console.log(`\nwritten to ${options.out}`);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
