// ─── Nudge Content Script ────────────────────────────────────────────────────
const PIXELS_PER_UNIT  = 500;
const LINE_HEIGHT_PX   = 40;
const SYNC_INTERVAL_MS = 3000;

let pixelBuffer     = 0;
let sessionUnits    = 0;
let lastSyncedUnits = 0;
let lastUrl         = location.href;

console.log('[Nudge] Content script loaded on', location.hostname, location.pathname);

// ─── Helpers ─────────────────────────────────────────────────────────────────
function isShortFormFeed() {
  const host = location.hostname.replace('www.', '');
  return (host === 'youtube.com' && location.pathname.startsWith('/shorts/'))
      || host === 'tiktok.com';
}

function wheelToPx(e) {
  if (e.deltaY <= 0) return 0;
  switch (e.deltaMode) {
    case WheelEvent.DOM_DELTA_PIXEL: return e.deltaY;
    case WheelEvent.DOM_DELTA_LINE:  return e.deltaY * LINE_HEIGHT_PX;
    case WheelEvent.DOM_DELTA_PAGE:  return e.deltaY * window.innerHeight;
    default: return e.deltaY;
  }
}

function accumulate(px) {
  if (px <= 0) return;
  pixelBuffer += px;
  const units = Math.floor(pixelBuffer / PIXELS_PER_UNIT);
  if (units > 0) {
    pixelBuffer   = pixelBuffer % PIXELS_PER_UNIT;
    sessionUnits += units;
    console.log('[Nudge] +' + units + ' units (wheel), total=' + sessionUnits);
  }
}

// ─── Wheel events (Instagram, Reddit, Twitter, YouTube feed) ─────────────────
window.addEventListener('wheel', (e) => {
  if (isShortFormFeed()) return;
  accumulate(wheelToPx(e));
}, { passive: true });

// ─── URL polling — YouTube Shorts / TikTok ────────────────────────────────────
setInterval(() => {
  if (!isShortFormFeed()) return; // only poll on short-form feeds
  const current = location.href;
  if (current !== lastUrl) {
    lastUrl = current;
    sessionUnits += 1;
    console.log('[Nudge] +1 unit (short), total=' + sessionUnits);
    syncIfChanged();
  }
}, 500);

// ─── Send to service worker ───────────────────────────────────────────────────
function syncIfChanged() {
  const delta = sessionUnits - lastSyncedUnits;
  if (delta <= 0) return;
  console.log('[Nudge] Sending delta=' + delta + ' to service worker');
  chrome.runtime.sendMessage({
    type:   'SCROLL_DELTA',
    delta,
    domain: location.hostname.replace('www.', ''),
    date:   (() => { const d=new Date(); return `${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,"0")}-${String(d.getDate()).padStart(2,"0")}`; })(),
  }).catch((err) => console.warn('[Nudge] sendMessage failed:', err));
  lastSyncedUnits = sessionUnits;
}

setInterval(syncIfChanged, SYNC_INTERVAL_MS);

document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') syncIfChanged();
});
