// Turning a pile of screenshots into one sprite sheet.
//
// Every capture is a generously padded rectangle, so the first job is finding how much of
// it any frame actually used. Cropping to the union of the non-transparent pixels answers
// the "how much padding do transforms need" question by measurement instead of by guess,
// and it is why the manifest can report an exact bearing and ascent.

import { createHash } from 'node:crypto';
import { PNG } from 'pngjs';

export function decode(buffer) {
  return PNG.sync.read(buffer);
}

/** The smallest rectangle containing every non-transparent pixel of every frame. */
export function unionAlphaBounds(frames, alphaThreshold = 0) {
  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;

  for (const png of frames) {
    const { width, height, data } = png;
    for (let y = 0; y < height; y++) {
      const row = y * width * 4;
      for (let x = 0; x < width; x++) {
        if (data[row + x * 4 + 3] > alphaThreshold) {
          if (x < minX) minX = x;
          if (x > maxX) maxX = x;
          if (y < minY) minY = y;
          if (y > maxY) maxY = y;
        }
      }
    }
  }

  if (minX === Infinity) return null;
  return { x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1 };
}

function cropOne(png, bounds) {
  const out = Buffer.alloc(bounds.width * bounds.height * 4);
  for (let y = 0; y < bounds.height; y++) {
    const from = ((y + bounds.y) * png.width + bounds.x) * 4;
    png.data.copy(out, y * bounds.width * 4, from, from + bounds.width * 4);
  }
  return out;
}

/**
 * Crops every frame to `bounds` and stacks the distinct ones into a single vertical strip.
 *
 * Deduplication matters most for the emotes animated with `steps(N)`: sampling those on a
 * fixed timeline lands several captures inside the same step, and without this the strip
 * would carry a dozen byte-identical copies of the same tile. `sequence` maps each timeline
 * step back to its tile, and is omitted from the manifest when it is just 0..n-1.
 */
/**
 * Box-filter downscale, averaging in premultiplied alpha.
 *
 * Averaging straight RGBA would pull the colour of fully transparent pixels into the
 * result and leave a dark halo around everything with a soft edge.
 */
function downscale(pixels, width, height, targetWidth, targetHeight) {
  const out = Buffer.alloc(targetWidth * targetHeight * 4);
  const xRatio = width / targetWidth;
  const yRatio = height / targetHeight;

  for (let ty = 0; ty < targetHeight; ty++) {
    const y0 = Math.floor(ty * yRatio);
    const y1 = Math.max(y0 + 1, Math.min(height, Math.ceil((ty + 1) * yRatio)));
    for (let tx = 0; tx < targetWidth; tx++) {
      const x0 = Math.floor(tx * xRatio);
      const x1 = Math.max(x0 + 1, Math.min(width, Math.ceil((tx + 1) * xRatio)));

      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      let n = 0;
      for (let y = y0; y < y1; y++) {
        for (let x = x0; x < x1; x++) {
          const i = (y * width + x) * 4;
          const alpha = pixels[i + 3] / 255;
          r += pixels[i] * alpha;
          g += pixels[i + 1] * alpha;
          b += pixels[i + 2] * alpha;
          a += pixels[i + 3];
          n++;
        }
      }

      const o = (ty * targetWidth + tx) * 4;
      const meanAlpha = a / n;
      if (meanAlpha > 0) {
        const weight = n * (meanAlpha / 255);
        out[o] = Math.round(r / weight);
        out[o + 1] = Math.round(g / weight);
        out[o + 2] = Math.round(b / weight);
      }
      out[o + 3] = Math.round(meanAlpha);
    }
  }
  return out;
}

/**
 * @param maxTile the largest tile edge to emit. Minecraft stitches glyphs into 256x256
 *                font pages, so anything bigger than that simply cannot be drawn, and
 *                `Chatting` is 320px wide. Well under the limit also keeps a session's
 *                worth of emotes down to a couple of pages.
 */
export function cropAndStack(frames, bounds, maxTile = Infinity) {
  const longest = Math.max(bounds.width, bounds.height);
  const scale = longest > maxTile ? maxTile / longest : 1;
  const tileWidth = Math.max(1, Math.round(bounds.width * scale));
  const tileHeight = Math.max(1, Math.round(bounds.height * scale));

  const tiles = [];
  const tileIndexByHash = new Map();
  const sequence = [];

  for (const frame of frames) {
    let pixels = cropOne(frame, bounds);
    if (scale < 1) {
      pixels = downscale(pixels, bounds.width, bounds.height, tileWidth, tileHeight);
    }
    const hash = createHash('sha1').update(pixels).digest('hex');
    let index = tileIndexByHash.get(hash);
    if (index === undefined) {
      index = tiles.length;
      tileIndexByHash.set(hash, index);
      tiles.push(pixels);
    }
    sequence.push(index);
  }

  const sheet = new PNG({ width: tileWidth, height: tileHeight * tiles.length });
  tiles.forEach((pixels, index) => {
    pixels.copy(sheet.data, index * tileWidth * tileHeight * 4);
  });

  const identity = sequence.every((tile, step) => tile === step);
  return {
    buffer: PNG.sync.write(sheet),
    tileWidth,
    tileHeight,
    tileCount: tiles.length,
    // Tile pixels per CSS pixel. The geometry in the manifest stays in CSS pixels, so the
    // client divides by this to get back to the size the emote should be drawn at.
    scale,
    sequence: identity ? null : sequence,
  };
}
