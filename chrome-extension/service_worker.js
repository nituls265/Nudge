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

// Local date — matches Android's SimpleDateFormat("yyyy-MM-dd") which uses local timezone.
// Do NOT use toISOString() — that returns UTC, which can be a day ahead in US timezones.
function localDateString() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// ─── Badge helpers ────────────────────────────────────────────────────────────
function updateBadge(total) {
  const text = total > 0 ? (total > 9999 ? '9999' : String(total)) : '';
  chrome.action.setBadgeText({ text });
  chrome.action.setBadgeBackgroundColor({ color: '#2DD4BF' });
}

// Badge = synced (persisted in storage) + pending (local buffer)
async function refreshBadge(pendingScrolls) {
  const today = localDateString();

  // Get persisted synced count for today
  const { syncedCounts } = await chrome.storage.local.get(['syncedCounts']);
  const synced = (syncedCounts ?? {})[today] ?? 0;

  // Sum today's pending
  const pending = pendingScrolls
    ? Object.entries(pendingScrolls)
        .filter(([k]) => k.startsWith(today))
        .reduce((sum, [, v]) => sum + v, 0)
    : 0;

  updateBadge(synced + pending);
}

// ─── Initialise badge on service worker startup ───────────────────────────────
// Also bootstrap syncedCounts from Firebase in case local storage was cleared
// (reinstall, clear data) — prevents the next flush overwriting a higher count.
chrome.storage.local.get(['pendingScrolls', 'syncedCounts', 'nudgeSyncId'], async ({ pendingScrolls, syncedCounts, nudgeSyncId }) => {
  if (nudgeSyncId) {
    const today = localDateString();
    const localSynced = (syncedCounts ?? {})[today] ?? 0;
    try {
      const res = await fetch(`${FIREBASE_DB_URL}/users/${nudgeSyncId}/laptop/${today}.json`);
      if (res.ok) {
        const data = await res.json();
        const firebaseCount = data?.laptop_count ?? 0;
        if (firebaseCount > localSynced) {
          const updated = { ...(syncedCounts ?? {}), [today]: firebaseCount };
          await chrome.storage.local.set({ syncedCounts: updated });
          console.log(`[Nudge] Bootstrapped syncedCounts from Firebase: ${firebaseCount}`);
        }
      }
    } catch (_) {}
  }
  refreshBadge(pendingScrolls ?? {});
});

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
  if (msg.type === 'FORCE_FLUSH') {
    flush().then(() => sendResponse({ ok: true }));
    return true;
  }

  if (msg.type !== 'SCROLL_DELTA') return;

  chrome.storage.local.get(['pendingScrolls'], (result) => {
    const pending = result.pendingScrolls ?? {};
    const key     = `${msg.date}__${msg.domain}`;
    pending[key]  = (pending[key] ?? 0) + msg.delta;
    chrome.storage.local.set({ pendingScrolls: pending }, () => refreshBadge(pending));
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

  const { syncedCounts } = await chrome.storage.local.get(['syncedCounts']);
  const results = await Promise.allSettled(
    Object.entries(byDate).map(([date, data]) => {
      const alreadySynced = (syncedCounts ?? {})[date] ?? 0;
      return pushToFirebase(nudgeSyncId, date, data, alreadySynced);
    })
  );

  const allOk = results.every(r => r.status === 'fulfilled' && r.value);
  if (allOk) {
    const { syncedCounts, syncedDomains } = await chrome.storage.local.get(['syncedCounts', 'syncedDomains']);
    const updCounts  = syncedCounts  ?? {};
    const updDomains = syncedDomains ?? {};
    for (const [date, data] of Object.entries(byDate)) {
      // Accumulate total
      updCounts[date] = (updCounts[date] ?? 0) + data.total;
      // Accumulate per-domain breakdown
      if (!updDomains[date]) updDomains[date] = {};
      for (const [domain, count] of Object.entries(data.domains)) {
        const key = domain.replace(/\./g, '_');
        updDomains[date][key] = (updDomains[date][key] ?? 0) + count;
      }
    }
    await chrome.storage.local.set({ pendingScrolls: {}, syncedCounts: updCounts, syncedDomains: updDomains });
    console.log('[Nudge] Synced to Firebase:', byDate);
  } else {
    console.warn('[Nudge] Some Firebase writes failed — will retry next alarm.');
  }
  refreshBadge({}); // update badge after flush
}

// ─── Write to Firebase Realtime Database via REST ────────────────────────────
// Uses PATCH so it merges into existing data — never overwrites phone_count.
// Path: /users/{syncId}/laptop/{date}
//   laptop_count  → total scrolls from laptop (accumulated via server increment trick)
//   domains       → breakdown per site
//   synced_at     → epoch ms
// alreadySynced = what this extension has already confirmed pushed today (from local storage)
// This avoids reading stale Firebase data from other sessions/reinstalls
async function pushToFirebase(syncId, date, data, alreadySynced = 0) {
  // Firebase forbids '.' in key names
  const sanitizedDomains = {};
  for (const [domain, count] of Object.entries(data.domains)) {
    sanitizedDomains[domain.replace(/\./g, '_')] = count;
  }

  const writeUrl = `${FIREBASE_DB_URL}/users/${syncId}/laptop/${date}.json`;
  const payload  = {
    laptop_count: alreadySynced + data.total,
    domains:      sanitizedDomains,
    synced_at:    Date.now(),
  };

  const writeRes = await fetch(writeUrl, {
    method:  'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body:    JSON.stringify(payload),
  });

  if (!writeRes.ok) {
    const body = await writeRes.text();
    console.error(`[Nudge] WRITE failed ${writeRes.status} for syncId="${syncId}":`, body);
    return false;
  }

  console.log(`[Nudge] Pushed total=${alreadySynced + data.total} to Firebase (${data.total} new)`);
  return true;
}
