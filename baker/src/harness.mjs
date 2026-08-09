// The page destiny.gg's emotes are rendered in, and the in-page API the bake drives it with.
//
// The point of doing this in a browser at all: emotes.css is hand-written and irregular
// (multi-track shorthands, !important, negative delays, cubic-bezier, ::before content,
// sprite strips cropped by overflow), and reimplementing a useful subset in Java would
// still leave dozens of emotes as stills forever. Chromium already implements all of it.

/** Where the emote line sits in the page. Far enough in that padding never leaves the viewport. */
export const ORIGIN_X = 150;
export const ORIGIN_Y = 350;
export const VIEWPORT = { width: 1400, height: 700 };

/** Slack around the emote box, so transforms that draw outside it are captured, not clipped. */
export const PAD = 40;

const PAGE_SCRIPT = String.raw`
(() => {
  const host = document.getElementById('host');
  let state = null;

  function rect(el) { return el.getBoundingClientRect(); }

  async function nextFrames(n) {
    for (let i = 0; i < n; i++) {
      await new Promise((resolve) => requestAnimationFrame(resolve));
    }
  }

  /**
   * Frames of an animated container (GIF, WebP, AVIF) as blob URLs.
   *
   * Chromium plays these on its own clock, which no amount of seeking controls, so they
   * are decoded up front and swapped into background-image per captured frame instead.
   * That keeps every CSS rule on top of them intact: position, size, transform, filter.
   */
  /** The URL the cascade actually settled on, which is not always the one in emotes.json. */
  function backgroundUrl(el) {
    const match = /url\(["']?([^"')]+)["']?\)/.exec(getComputedStyle(el).backgroundImage || '');
    return match ? match[1] : null;
  }

  /**
   * Nothing else waits for the background image.
   *
   * Most emotes are already decoded by the time the first screenshot is taken, because a
   * previous emote warmed the connection, but a few lose the race and bake as 60 frames of
   * transparent pixels. Awaiting decode() is the difference between OMEGALUL rendering and
   * OMEGALUL silently not existing.
   */
  async function awaitImage(url) {
    if (!url) return;
    const image = new Image();
    image.src = url;
    try {
      await image.decode();
    } catch {
      // A broken image is caught later by the emote rendering nothing at all.
    }
  }

  async function decodeContainer(url) {
    if (typeof ImageDecoder === 'undefined') return null;
    const response = await fetch(url);
    if (!response.ok) return null;
    // The served content type comes from sniffing the bytes, so it beats both the URL's
    // extension and the mime in emotes.json, which disagree for at least one emote.
    const type = (response.headers.get('content-type') || '').split(';')[0].trim();
    if (!type.startsWith('image/')) return null;

    const decoder = new ImageDecoder({ data: await response.arrayBuffer(), type });
    // tracks.ready is the one to await: completed resolves before the track list is
    // populated, and reading tracks any earlier reports zero of them.
    await decoder.tracks.ready;
    await decoder.completed;
    const track = decoder.tracks.selectedTrack;
    if (!track || track.frameCount <= 1) { decoder.close(); return null; }

    const frames = [];
    let totalMs = 0;
    for (let i = 0; i < track.frameCount; i++) {
      const { image } = await decoder.decode({ frameIndex: i });
      const canvas = new OffscreenCanvas(image.displayWidth, image.displayHeight);
      canvas.getContext('2d').drawImage(image, 0, 0);
      const blob = await canvas.convertToBlob({ type: 'image/png' });
      // duration is microseconds, and browsers clamp anything under 20ms to 100ms.
      const durationMs = Math.max(20, Math.round((image.duration ?? 100000) / 1000));
      frames.push({ url: URL.createObjectURL(blob), durationMs });
      totalMs += durationMs;
      image.close();
    }
    decoder.close();
    return { frames, totalMs };
  }

  window.__bake = {
    /** Mounts one emote and reports everything the capture loop needs to know about it. */
    async setup(prefix) {
      host.textContent = '';
      const penProbe = document.createElement('span');
      const endProbe = document.createElement('span');
      const baselineProbe = document.createElement('span');
      penProbe.className = endProbe.className = baselineProbe.className = 'probe';

      const emote = document.createElement('div');
      emote.className = 'emote ' + prefix;
      emote.title = prefix;
      emote.textContent = prefix;

      host.append(baselineProbe, penProbe, emote, endProbe);
      await document.fonts.ready;

      const imageUrl = backgroundUrl(emote);
      await awaitImage(imageUrl);
      await nextFrames(2);

      const container = await decodeContainer(imageUrl);

      // Every animation on the element and its pseudo-elements. Paused now so that
      // nothing advances between measuring and capturing.
      //
      // Only ONE iteration is ever captured. Iterating is the client's job: baking four
      // identical passes of Askers would quadruple the download for nothing.
      const animations = emote.getAnimations({ subtree: true });
      let iterationMs = 0;
      let iterations = 1;
      let infinite = false;
      for (const animation of animations) {
        animation.pause();
        const timing = animation.effect.getComputedTiming();
        const duration = typeof timing.duration === 'number' ? timing.duration : 0;
        iterationMs = Math.max(iterationMs, duration + (timing.delay || 0));
        if (timing.iterations === Infinity) {
          infinite = true;
        } else {
          iterations = Math.max(iterations, Math.ceil(timing.iterations || 1));
        }
      }

      // Roughly a third of the animated emotes are sprite strips driven by steps(N).
      // Sampling those on a fixed frame rate lands several captures inside one step and
      // misses others entirely, so the step count is read off the CSS and used directly.
      let steps = 0;
      const timingFunctions = getComputedStyle(emote).animationTimingFunction || '';
      for (const match of timingFunctions.matchAll(/steps\(\s*(\d+)/g)) {
        steps = Math.max(steps, Number(match[1]));
      }

      state = { emote, container };

      const baseline = rect(baselineProbe).top;
      const pen = rect(penProbe).left;
      return {
        prefix,
        // Layout width including margins: several emotes pull themselves in with
        // negative margins, and that is part of how much room they take in a line.
        advance: rect(endProbe).left - pen,
        boxWidth: emote.offsetWidth,
        boxHeight: emote.offsetHeight,
        penX: pen,
        baselineY: baseline,
        iterationMs,
        steps,
        // 0 means forever. A finite animation stops on its last frame, which is what the
        // site does: an emote animates when the message arrives and then sits still.
        iterations: animations.length === 0 ? 0 : (infinite ? 0 : iterations),
        containerDurationMs: container ? container.totalMs : 0,
        containerFrames: container ? container.frames.length : 0,
        animationCount: animations.length,
      };
    },

    /** Puts every clock on this emote at the given time and settles the frame for capture. */
    async seek(timeMs) {
      const { emote, container } = state;
      for (const animation of emote.getAnimations({ subtree: true })) {
        animation.pause();
        animation.currentTime = timeMs;
      }
      if (container && container.frames.length > 0) {
        let remaining = timeMs % container.totalMs;
        let index = 0;
        while (index < container.frames.length - 1 && remaining >= container.frames[index].durationMs) {
          remaining -= container.frames[index].durationMs;
          index++;
        }
        emote.style.backgroundImage = 'url("' + container.frames[index].url + '")';
      }
      // Force a style recalculation, then let the compositor produce the frame.
      void getComputedStyle(emote).transform;
      await nextFrames(2);
    },

    /**
     * Re-encodes a flair icon as PNG, base64.
     *
     * Two of the twenty-five visible flairs are WebP, and Minecraft's NativeImage reads
     * PNG only. Normalising here means the client needs no decoder it does not have, and
     * it costs nothing: a browser is already running.
     */
    async toPng(url) {
      const response = await fetch(url);
      if (!response.ok) return null;
      const bitmap = await createImageBitmap(await response.blob());
      const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
      canvas.getContext('2d').drawImage(bitmap, 0, 0);
      const blob = await canvas.convertToBlob({ type: 'image/png' });
      const bytes = new Uint8Array(await blob.arrayBuffer());
      let binary = '';
      for (const byte of bytes) binary += String.fromCharCode(byte);
      const result = { width: bitmap.width, height: bitmap.height, base64: btoa(binary) };
      bitmap.close();
      return result;
    },
  };
})();
`;

export function harnessHtml(emotesCss) {
  return `<!doctype html>
<html>
<head>
<meta charset="utf-8">
<title>bake</title>
<style>
  html, body { margin: 0; padding: 0; background: transparent; }
  /* chat-gui's line context. Emote geometry is declared against .msg-chat, and the
     font only matters for where the baseline lands. */
  #host {
    position: absolute;
    left: ${ORIGIN_X}px;
    top: ${ORIGIN_Y}px;
    font: 14px/19px "DejaVu Sans", sans-serif;
    white-space: nowrap;
    color: transparent;
  }
  /* chat-gui's base .emote rule. overflow:hidden is load-bearing: it is what crops a
     6390px sprite strip down to the 32px window the animation steps across. */
  .emote {
    display: inline-block;
    position: relative;
    overflow: hidden;
    text-indent: -999em;
    background-repeat: no-repeat;
    vertical-align: baseline;
  }
  /* A baseline-aligned inline-block with no height sits exactly on the baseline, which
     is what ascent gets measured from, and with no width it does not move the pen. */
  .probe { display: inline-block; width: 0; height: 0; vertical-align: baseline; }
</style>
<style id="dgg-emotes">${emotesCss}</style>
</head>
<body>
<div id="host" class="msg-chat"></div>
<script>${PAGE_SCRIPT}</script>
</body>
</html>`;
}
