// ─── Nudge Service Worker ────────────────────────────────────────────────────
// Batches scroll deltas from content scripts and uploads to Firebase
// Realtime Database every 5 minutes (or on browser close).
//
// Sync strategy:
//   - Content scripts send SCROLL_DELTA messages as the user scrolls
//   - We accumulate in chrome.storage.local (survives service worker restarts)
//   - An alarm fires every 5 min to flush to Firebase
//   - We write to users/{syncId}/laptop/{date} using PATCH (merge, not overwrite)
//   - The Android app reads this path and merges with its phone count

const FIREBASE_DB_URL = 'https://nudgev0-default-rtdb.firebaseio.com';
const SYNC_ALARM      = 'nudge_firebase_sync';
const ALARM_INTERVAL  = 5; // minutes

// ─── Setup ───────────────────────────────────────────────────────────────────
chrome.runtime.onInstalled.addListener(() => {
  chrome.alarms.create(SYNC_ALARM, { periodInMinutes: ALARM_INTERVAL });
  console.log('[Nudge] Extension installed, sync alarm set.');
});

// Re-register alarm on service worker restart (MV3 workers can be killed)
chrome.alarms.get(SYNC_ALARM, (alarm) => {
  if (!alarm) {
    chrome.alarms.create(SYNC_ALARM, { periodInMinutes: ALARM_INTERVAL });
  }
});

// ─── Accumulate deltas from content scripts ───────────────────────────────────
chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (msg.type !== 'SCROLL_DELTA') return;

  chrome.storage.local.get(['pendingScrolls'], (result) => {
    const pending = result.pendingScrolls ?? {};
    const key     = `${msg.date}__${msg.domain}`;
    pending[key]  = (pending[key] ?? 0) + msg.delta;
    chrome.storage.local.set({ pendingScrolls: pending });
  });

  sendResponse({ ok: true });
  return true;
});

// ─── Alarm: flush to Firebase ─────────────────────────────────────────────────
chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === SYNC_ALARM) flush();
});

// Also flush when Chrome is about to shut down
chrome.runtime.onSuspend?.addListener(flush);

// ─── Core flush function ──────────────────────────────────────────────────────
async function flush() {
  const { pendingScrolls, nudgeSyncId } = await chrome.storage.local.get([
    'pendingScrolls',
    'nudgeSyncId',
  ]);

  if (!nudgeSyncId) {
    console.warn('[Nudge] No Sync ID configured. Open the popup to link your phone.');
    return;
  }

  if (!pendingScrolls || Object.keys(pendingScrolls).length === 0) return;

  // Group by date, sum across all domains
  const byDate = {};
  for (const [key, count] of Object.entries(pendingScrolls)) {
    const [date, domain] = key.split('__');
    if (!byDate[date]) byDate[date] = { total: 0, domains: {} };
    byDate[date].total            += count;
    byDate[date].domains[domain]   = (byDate[date].domains[domain] ?? 0) + count;
  }

  const results = await Promise.allSettled(
    Object.entries(byDate).map(([date, data]) => pushToFirebase(nudgeSyncId, date, data))
  );

  const allOk = results.every(r => r.status === 'fulfilled' && r.value);
  if (allOk) {
    // Only clear buffer if all writes succeeded
    await chrome.storage.local.set({ pendingScrolls: {} });
    console.log('[Nudge] Synced to Firebase:', byDate);
  } else {
    console.warn('[Nudge] Some Firebase writes failed — will retry next alarm.');
  }
}

// ─── Write to Firebase Realtime Database via REST ────────────────────────────
// Uses PATCH so it merges into existing data — never overwrites phone_count.
// Path: /users/{syncId}/laptop/{date}
//   laptop_count  → total scrolls from laptop (accumulated via server increment trick)
//   domains       → breakdown per site
//   synced_at     → epoch ms
async function pushToFirebase(syncId, date, data) {
  // First read current laptop_count so we can add to it (RTDB has no server increment)
  const readUrl  = `${FIREBASE_DB_URL}/users/${syncId}/laptop/${date}/laptop_count.json`;
  const readRes  = await fetch(readUrl);
  const existing = readRes.ok ? (await readRes.json() ?? 0) : 0;

  const writeUrl  = `${FIREBASE_DB_URL}/users/${syncId}/laptop/${date}.json`;
  const payload   = {
    laptop_count: existing + data.total,
    domains:      data.domains,
    synced_at:    Date.now(),
  };

  const res = await fetch(writeUrl, {
    method:  'PATCH', // PATCH merges; PUT would overwrite
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(payload),
  });

  return res.ok;
}
