// Everything that talks to destiny.gg, and nothing else does.
//
// The bake is the only part of this project that touches their CDN: the mod fetches
// flairs and its own baked output, never emotes.css. Responses are kept on disk so a
// re-run costs nothing and so two bakes of the same input produce the same bytes.

import { createHash } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';

export const EMOTES_JSON = 'https://cdn.destiny.gg/emotes/emotes.json';
export const EMOTES_CSS = 'https://cdn.destiny.gg/emotes/emotes.css';
export const FLAIRS_JSON = 'https://cdn.destiny.gg/flairs/flairs.json';
export const FLAIRS_CSS = 'https://cdn.destiny.gg/flairs/flairs.css';

// cdn.destiny.gg answers 403 to user agents it does not recognise, including Node's.
const USER_AGENT =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36';

export class Cdn {
  #dir;
  #memory = new Map();

  constructor(cacheDir) {
    this.#dir = cacheDir;
  }

  #pathFor(url) {
    const hash = createHash('sha256').update(url).digest('hex').slice(0, 40);
    return path.join(this.#dir, `${hash}${path.extname(new URL(url).pathname) || '.bin'}`);
  }

  /** Bytes for `url`, from disk if they are already there. */
  async get(url) {
    if (this.#memory.has(url)) return this.#memory.get(url);

    const file = this.#pathFor(url);
    let bytes;
    try {
      bytes = await readFile(file);
    } catch {
      const response = await fetch(url, { headers: { 'user-agent': USER_AGENT } });
      if (!response.ok) throw new Error(`${response.status} ${response.statusText} for ${url}`);
      bytes = Buffer.from(await response.arrayBuffer());
      await mkdir(this.#dir, { recursive: true });
      await writeFile(file, bytes);
    }
    this.#memory.set(url, bytes);
    return bytes;
  }

  async getText(url) {
    return (await this.get(url)).toString('utf8');
  }
}

export function sha256(buffer) {
  return createHash('sha256').update(buffer).digest('hex');
}

/**
 * The image type according to the bytes, which is the only source that is always right.
 *
 * `AlienPls` is published as `image/webp` at a URL ending `.gif`, and it really is a WebP.
 * Trusting either the extension or the declared mime hands Chromium's decoder the wrong
 * type for it, and a wrong type means no frames rather than an error.
 */
export function sniffMime(buffer) {
  if (buffer.length >= 12) {
    const head = buffer.subarray(0, 12);
    if (head.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
    if (head.subarray(0, 3).toString('latin1') === 'GIF') return 'image/gif';
    if (head.subarray(0, 4).toString('latin1') === 'RIFF' && head.subarray(8, 12).toString('latin1') === 'WEBP') return 'image/webp';
    if (head.subarray(4, 8).toString('latin1') === 'ftyp') return 'image/avif';
    if (head[0] === 0xff && head[1] === 0xd8 && head[2] === 0xff) return 'image/jpeg';
  }
  return null;
}
