// ─── Nudge Popup ─────────────────────────────────────────────────────────────
const SUPABASE_URL      = 'https://mvfdwgcknmskadhlujgk.supabase.co';
const SUPABASE_ANON_KEY = 'eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Im12ZmR3Z2Nrbm1za2FkaGx1amdrIiwicm9sZSI6ImFub24iLCJpYXQiOjE3ODEzODM3NTMsImV4cCI6MjA5Njk1OTc1M30.xN66ohuXKM6EnyYM1dt9-7ctgB29FLXFs8YeH27HqWE';

function supabaseHeaders() {
  return {
    'apikey':        SUPABASE_ANON_KEY,
    'Authorization': `Bearer ${SUPABASE_ANON_KEY}`,
  };
}

const linkedView  = document.getElementById('linked-view');
const setupView   = document.getElementById('setup-view');
const laptopCount = document.getElementById('laptop-count');
const lastSynced  = document.getElementById('last-synced');
const syncStatus  = document.getElementById('sync-status');
const pendingStatus = document.getElementById('pending-status');
const errorMsg    = document.getElementById('error-msg');

let syncedCount  = 0;
let refreshTimer = null;

// ─── Boot ─────────────────────────────────────────────────────────────────────
chrome.storage.local.get(['nudgeSyncId'], ({ nudgeSyncId }) => {
  if (nudgeSyncId) showLinkedView(nudgeSyncId);
  else             showSetupView();
});

// ─── Setup view ───────────────────────────────────────────────────────────────
function showSetupView() {
  setupView.style.display  = 'block';
  linkedView.style.display = 'none';
  if (refreshTimer) { clearInterval(refreshTimer); refreshTimer = null; }
}

document.getElementById('link-btn').addEventListener('click', async () => {
  const raw = document.getElementById('sync-id-input').value.trim();
  if (!raw) return showError('Please paste your Sync Code from the Nudge app.');

  try {
    await fetchSyncRow(raw, todayString());

    await chrome.storage.local.set({ nudgeSyncId: raw });
    hideError();
    showLinkedView(raw);
    chrome.runtime.sendMessage({ type: 'FORCE_FLUSH' }).catch(() => {});
  } catch (e) {
    showError('Invalid Sync Code or no internet connection.');
  }
});

document.getElementById('unlink-btn').addEventListener('click', async () => {
  await chrome.storage.local.remove('nudgeSyncId');
  showSetupView();
});

document.getElementById('clear-btn')?.addEventListener('click', async () => {
  await chrome.storage.local.set({ pendingScrolls: {} });
  syncedCount = 0;
  updateDisplay();
});

document.getElementById('sync-now-btn').addEventListener('click', async () => {
  const btn = document.getElementById('sync-now-btn');
  btn.textContent = 'Syncing…';
  btn.disabled = true;
  try {
    await chrome.runtime.sendMessage({ type: 'FORCE_FLUSH' });
    const { nudgeSyncId } = await chrome.storage.local.get(['nudgeSyncId']);
    if (nudgeSyncId) await refreshSyncedCount(nudgeSyncId);
  } finally {
    btn.textContent = 'Sync Now';
    btn.disabled = false;
  }
});

// ─── Linked view ──────────────────────────────────────────────────────────────
async function showLinkedView(syncId) {
  setupView.style.display  = 'none';
  linkedView.style.display = 'block';

  await refreshSyncedCount(syncId);
  updateDisplay();

  // Refresh Supabase count every 10s, pending every 2s
  if (refreshTimer) clearInterval(refreshTimer);
  let tick = 0;
  refreshTimer = setInterval(async () => {
    tick++;
    updateDisplay();                          // always update pending immediately
    if (tick % 5 === 0) await refreshSyncedCount(syncId); // Supabase every 10s
  }, 2000);
}

// ─── Fetch the sync_state row for today ───────────────────────────────────────
async function fetchSyncRow(syncId, date) {
  const url = `${SUPABASE_URL}/rest/v1/sync_state?sync_id=eq.${encodeURIComponent(syncId)}&date=eq.${date}&select=laptop_count,laptop_synced_at`;
  const res = await fetch(url, { headers: supabaseHeaders() });
  if (!res.ok) throw new Error(`Could not reach Supabase (HTTP ${res.status})`);
  const rows = await res.json();
  return rows[0] ?? null;
}

// ─── Fetch synced count from Supabase and reconcile with local storage ───────
// If Supabase has a higher count than our local syncedCounts (e.g. after a
// reinstall that wiped storage), update local so the next flush doesn't
// overwrite Supabase with a lower number.
async function refreshSyncedCount(syncId) {
  const today = todayString();
  try {
    const row = await fetchSyncRow(syncId, today);

    if (row?.laptop_count) {
      const { syncedCounts } = await chrome.storage.local.get(['syncedCounts']);
      const localSynced = (syncedCounts ?? {})[today] ?? 0;
      if (row.laptop_count > localSynced) {
        const updated = { ...(syncedCounts ?? {}), [today]: row.laptop_count };
        await chrome.storage.local.set({ syncedCounts: updated });
      }
    }

    if (row?.laptop_synced_at) {
      lastSynced.textContent = `Last synced ${timeSince(Date.parse(row.laptop_synced_at))}`;
    } else {
      lastSynced.textContent = 'No data yet today';
    }
  } catch (e) {
    // keep last known syncedCount on network error
  }
}

// ─── Update the displayed count = synced + pending ───────────────────────────
function updateDisplay() {
  const today = todayString();
  chrome.storage.local.get(['pendingScrolls', 'syncedCounts'], ({ pendingScrolls, syncedCounts }) => {
    const synced  = (syncedCounts ?? {})[today] ?? 0;
    const pending = pendingScrolls
      ? Object.entries(pendingScrolls)
          .filter(([k]) => k.startsWith(today))
          .reduce((sum, [, v]) => sum + v, 0)
      : 0;

    laptopCount.textContent = synced + pending;

    if (pending > 0) {
      pendingStatus.textContent   = `⏳ ${pending} pending sync`;
      pendingStatus.style.display = 'block';
      syncStatus.textContent      = '●  Tracking';
      syncStatus.style.color      = '#2dd4bf';
    } else if (synced > 0) {
      pendingStatus.style.display = 'none';
      syncStatus.textContent      = '●  Synced';
      syncStatus.style.color      = '#2dd4bf';
    } else {
      pendingStatus.style.display = 'none';
      syncStatus.textContent      = '●  Waiting for first scroll';
      syncStatus.style.color      = '#64748b';
    }
  });
}

// ─── Helpers ──────────────────────────────────────────────────────────────────
function todayString() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}

function timeSince(epochMs) {
  const secs = Math.floor((Date.now() - epochMs) / 1000);
  if (secs < 60)   return `${secs}s ago`;
  if (secs < 3600) return `${Math.floor(secs / 60)}m ago`;
  return `${Math.floor(secs / 3600)}h ago`;
}

function showError(msg) {
  errorMsg.textContent   = msg;
  errorMsg.style.display = 'block';
}

function hideError() {
  errorMsg.style.display = 'none';
}
