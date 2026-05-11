// ─── Nudge Content Script ────────────────────────────────────────────────────
// Injected into social media pages. Tracks scroll distance and translates
// raw pixels into normalised "Scroll Units" (≈ one post swiped past on mobile).
//
// Translation math:
//   - A typical Instagram/Twitter post on desktop is ~600–900px tall
//   - One phone swipe past a post ≈ 500px of scroll distance
//   - So: every 500 accumulated downward pixels = 1 Scroll Unit
//   - WheelEvent deltaMode varies (pixels/lines/pages) — we normalise all to px first
//   - Trackpad fires many small events; mouse fires fewer large ones — the buffer handles both
//   - We only count DOWNWARD scroll (upward = reviewing, not doomscrolling)

const PIXELS_PER_UNIT  = 500;   // calibrate this if units feel off
const LINE_HEIGHT_PX   = 40;    // standard browser line height
const SYNC_INTERVAL_MS = 3000;  // flush to service worker every 3s

let pixelBuffer      = 0;  // accumulates raw pixel distance between unit awards
let sessionUnits     = 0;  // total units earned this page load
let lastSyncedUnits  = 0;  // last value we sent to the service worker

// ─── Normalise WheelEvent to pixels ─────────────────────────────────────────
function wheelToPx(event) {
  const dy = event.deltaY;
  if (dy <= 0) return 0; // ignore upward scrolls

  switch (event.deltaMode) {
    case WheelEvent.DOM_DELTA_PIXEL: return dy;
    case WheelEvent.DOM_DELTA_LINE:  return dy * LINE_HEIGHT_PX;
    case WheelEvent.DOM_DELTA_PAGE:  return dy * window.innerHeight;
    default: return dy;
  }
}

// ─── Core accumulator ────────────────────────────────────────────────────────
function accumulate(px) {
  if (px <= 0) return;
  pixelBuffer += px;

  const newUnits = Math.floor(pixelBuffer / PIXELS_PER_UNIT);
  if (newUnits > 0) {
    pixelBuffer   = pixelBuffer % PIXELS_PER_UNIT; // carry remainder forward
    sessionUnits += newUnits;
  }
}

// ─── Event listeners ─────────────────────────────────────────────────────────
window.addEventListener('wheel', (e) => {
  accumulate(wheelToPx(e));
}, { passive: true });

window.addEventListener('keydown', (e) => {
  // Intentional downward navigation keys
  const KEY_PX = {
    ArrowDown: 80,
    PageDown:  window.innerHeight,
    ' ':       window.innerHeight * 0.9,
    End:       9999, // jumped to bottom = heavy scroll
  };
  accumulate(KEY_PX[e.key] ?? 0);
});

// ─── Flush delta to service worker ───────────────────────────────────────────
function syncIfChanged() {
  const delta = sessionUnits - lastSyncedUnits;
  if (delta <= 0) return;

  chrome.runtime.sendMessage({
    type:   'SCROLL_DELTA',
    delta,
    domain: location.hostname.replace('www.', ''),
    date:   todayString(),
  }).catch(() => {}); // service worker may be asleep — that's fine, alarm will flush

  lastSyncedUnits = sessionUnits;
}

// ─── Helpers ─────────────────────────────────────────────────────────────────
function todayString() {
  return new Date().toISOString().slice(0, 10); // "2026-05-11"
}

setInterval(syncIfChanged, SYNC_INTERVAL_MS);

// Flush when tab is hidden / closed (best-effort)
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') syncIfChanged();
});
