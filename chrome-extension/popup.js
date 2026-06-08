// ─── Nudge Popup ─────────────────────────────────────────────────────────────
const FIREBASE_DB_URL = 'https://nudgev0-default-rtdb.firebaseio.com';

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

  const today = todayString();
  try {
    const res = await fetch(`${FIREBASE_DB_URL}/users/${raw}/laptop/${today}.json`);
    if (!res.ok && res.status !== 404) throw new Error('Could not reach database');

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
    if (nudgeSyncId) await fetchFirebaseCount(nudgeSyncId);
  } finally {
    btn.textContent = 'Sync Now';
    btn.disabled = false;
  }
});

// ─── Linked view ──────────────────────────────────────────────────────────────
async function showLinkedView(syncId) {
  setupView.style.display  = 'none';
  linkedView.style.display = 'block';

  await fetchFirebaseCount(syncId);
  updateDisplay();

  // Refresh Firebase count every 10s, pending every 2s
  if (refreshTimer) clearInterval(refreshTimer);
  let tick = 0;
  refreshTimer = setInterval(async () => {
    tick++;
    updateDisplay();                          // always update pending immediately
    if (tick % 5 === 0) await fetchFirebaseCount(syncId); // Firebase every 10s
  }, 2000);
}

// ─── Fetch synced count from Firebase (updates syncedCount) ──────────────────
async function fetchFirebaseCount(syncId) {
  const today = todayString();
  try {
    const res  = await fetch(`${FIREBASE_DB_URL}/users/${syncId}/laptop/${today}.json`);
    const data = res.ok ? await res.json() : null;

    if (data?.synced_at) {
      lastSynced.textContent = `Last synced ${timeSince(data.synced_at)}`;
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
  return new Date().toISOString().slice(0, 10);
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
