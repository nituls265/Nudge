// ─── Nudge Service Worker ────────────────────────────────────────────────────
// Batches scroll deltas from content scripts and uploads to Supabase
// (public.sync_state, PostgREST) every 5 minutes (or on browser close).
//
// Sync strategy:
//   - Content scripts send SCROLL_DELTA messages as the user scrolls
//   - We accumulate in chrome.storage.local (survives service worker restarts)
//   - An alarm fires every 5 min to flush to Supabase
//   - We upsert sync_state(sync_id, date) with only the laptop_* columns —
//     PostgREST's merge-duplicates resolution only overwrites columns present
//     in the payload, so phone_count is never touched.
//   - The Android app reads this row and merges with its phone count

const SUPABASE_URL      = 'https://mvfdwgcknmskadhlujgk.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im12ZmR3Z2Nrbm1za2FkaGx1amdrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEzODM3NTMsImV4cCI6MjA5Njk1OTc1M30.xN66ohuXKM6EnyYM1dt9-7ctgB29FLXFs8YeH27HqWE';
const SYNC_ALARM        = 'nudge_supabase_sync';
const ALARM_INTERVAL    = 5; // minutes

// Local date — matches Android's SimpleDateFormat("yyyy-MM-dd") which uses local timezone.
// Do NOT use toISOString() — that returns UTC, which can be a day ahead in US timezones.
function localDateString() {
  const d = new Date();
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function supabaseHeaders() {
  return {
    'apikey':        SUPABASE_ANON_KEY,
    'Authorization': `Bearer ${SUPABASE_ANON_KEY}`,
  };
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
// Also bootstrap syncedCounts from Supabase in case local storage was cleared
// (reinstall, clear data) — prevents the next flush overwriting a higher count.
chrome.storage.local.get(['pendingScrolls', 'syncedCounts', 'nudgeSyncId'], async ({ pendingScrolls, syncedCounts, nudgeSyncId }) => {
  if (nudgeSyncId) {
    const today = localDateString();
    const localSynced = (syncedCounts ?? {})[today] ?? 0;
    try {
      const row = await fetchSyncRow(nudgeSyncId, today);
      const supabaseCount = row?.laptop_count ?? 0;
      if (supabaseCount > localSynced) {
        const updated = { ...(syncedCounts ?? {}), [today]: supabaseCount };
        await chrome.storage.local.set({ syncedCounts: updated });
        console.log(`[Nudge] Bootstrapped syncedCounts from Supabase: ${supabaseCount}`);
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

// ─── Alarm: flush to Supabase ──────────────────────────────────────────────────
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
      return pushToSupabase(nudgeSyncId, date, data, alreadySynced);
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
        updDomains[date][domain] = (updDomains[date][domain] ?? 0) + count;
      }
    }
    await chrome.storage.local.set({ pendingScrolls: {}, syncedCounts: updCounts, syncedDomains: updDomains });
    console.log('[Nudge] Synced to Supabase:', byDate);
  } else {
    console.warn('[Nudge] Some Supabase writes failed — will retry next alarm.');
  }
  refreshBadge({}); // update badge after flush
}

// ─── Fetch a single sync_state row (sync_id, date) ───────────────────────────
async function fetchSyncRow(syncId, date) {
  const url = `${SUPABASE_URL}/rest/v1/sync_state?sync_id=eq.${encodeURIComponent(syncId)}&date=eq.${date}&select=laptop_count,laptop_synced_at`;
  const res = await fetch(url, { headers: supabaseHeaders() });
  if (!res.ok) throw new Error(`Could not reach Supabase (HTTP ${res.status})`);
  const rows = await res.json();
  return rows[0] ?? null;
}

// ─── Upsert laptop_* columns via PostgREST ───────────────────────────────────
// on_conflict=sync_id,date + Prefer: resolution=merge-duplicates means only the
// columns present in the payload are overwritten — phone_count is never touched.
// alreadySynced = what this extension has already confirmed pushed today (from
// local storage). This avoids reading stale Supabase data from other sessions/reinstalls.
async function pushToSupabase(syncId, date, data, alreadySynced = 0) {
  const payload = [{
    sync_id:          syncId,
    date:             date,
    laptop_count:     alreadySynced + data.total,
    laptop_domains:   data.domains,
    laptop_synced_at: new Date().toISOString(),
  }];

  const writeRes = await fetch(`${SUPABASE_URL}/rest/v1/sync_state?on_conflict=sync_id,date`, {
    method:  'POST',
    headers: {
      ...supabaseHeaders(),
      'Content-Type': 'application/json',
      'Prefer':       'resolution=merge-duplicates,return=minimal',
    },
    body: JSON.stringify(payload),
  });

  if (!writeRes.ok) {
    const body = await writeRes.text();
    console.error(`[Nudge] WRITE failed ${writeRes.status} for syncId="${syncId}":`, body);
    return false;
  }

  console.log(`[Nudge] Pushed total=${alreadySynced + data.total} to Supabase (${data.total} new)`);
  return true;
}
