// ─── Nudge Popup ─────────────────────────────────────────────────────────────
const FIREBASE_DB_URL = 'https://nudgev0-default-rtdb.firebaseio.com';

const linkedView  = document.getElementById('linked-view');
const setupView   = document.getElementById('setup-view');
const laptopCount = document.getElementById('laptop-count');
const lastSynced  = document.getElementById('last-synced');
const domainBreak = document.getElementById('domain-breakdown');
const syncStatus  = document.getElementById('sync-status');
const errorMsg    = document.getElementById('error-msg');

// ─── Boot ─────────────────────────────────────────────────────────────────────
chrome.storage.local.get(['nudgeSyncId'], ({ nudgeSyncId }) => {
  if (nudgeSyncId) showLinkedView(nudgeSyncId);
  else             showSetupView();
});

// ─── Setup view ───────────────────────────────────────────────────────────────
function showSetupView() {
  setupView.style.display  = 'block';
  linkedView.style.display = 'none';
}

document.getElementById('link-btn').addEventListener('click', async () => {
  const raw = document.getElementById('sync-id-input').value.trim();
  if (!raw) return showError('Please paste your Sync Code from the Nudge app.');

  // Validate: try to fetch today's data for this sync ID
  const today = todayString();
  try {
    const res = await fetch(`${FIREBASE_DB_URL}/users/${raw}/laptop/${today}.json`);
    if (!res.ok && res.status !== 404) throw new Error('Could not reach database');

    // Valid — save and switch to linked view
    await chrome.storage.local.set({ nudgeSyncId: raw });
    hideError();
    showLinkedView(raw);

    // Trigger an immediate sync flush
    chrome.runtime.sendMessage({ type: 'FORCE_FLUSH' }).catch(() => {});
  } catch (e) {
    showError('Invalid Sync Code or no internet connection.');
  }
});

document.getElementById('unlink-btn').addEventListener('click', async () => {
  await chrome.storage.local.remove('nudgeSyncId');
  showSetupView();
});

// ─── Linked view ──────────────────────────────────────────────────────────────
async function showLinkedView(syncId) {
  setupView.style.display  = 'none';
  linkedView.style.display = 'block';

  const today = todayString();
  try {
    const res  = await fetch(`${FIREBASE_DB_URL}/users/${syncId}/laptop/${today}.json`);
    const data = res.ok ? await res.json() : null;

    if (data) {
      laptopCount.textContent = data.laptop_count ?? 0;

      const ts = data.synced_at;
      lastSynced.textContent  = ts
        ? `Last synced ${timeSince(ts)}`
        : 'Not yet synced today';

      // Domain breakdown
      if (data.domains) {
        const sorted = Object.entries(data.domains).sort((a, b) => b[1] - a[1]);
        domainBreak.innerHTML = sorted.map(([domain, count]) => `
          <div class="domain-row">
            <span class="domain-name">${domain}</span>
            <span class="domain-count">${count}</span>
          </div>
        `).join('');
      }

      syncStatus.textContent = '●  Synced';
      syncStatus.style.color = '#2dd4bf';
    } else {
      laptopCount.textContent = '0';
      lastSynced.textContent  = 'No data yet today';
      syncStatus.textContent  = '●  Waiting for first scroll';
      syncStatus.style.color  = '#64748b';
    }
  } catch (e) {
    laptopCount.textContent = '?';
    syncStatus.textContent  = '●  Offline';
    syncStatus.style.color  = '#f87171';
  }
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
  errorMsg.textContent    = msg;
  errorMsg.style.display  = 'block';
}

function hideError() {
  errorMsg.style.display = 'none';
}
