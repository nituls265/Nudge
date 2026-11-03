/**
 * Nudge startup-guide mailer (Google Apps Script, runs in the Nudge Gmail account).
 *
 * Supabase calls this web app once per new 30-day-challenge sign-up
 * (supabase/migrations/20261103120100_subscribers_guide_mailer.sql) with
 * {"secret": "...", "email": "..."}. It checks the shared secret and sends the
 * startup guide from this Gmail account, so replies land in the same inbox.
 *
 * Setup (one time): see "Automatic startup-guide email" in the README.
 *   Project Settings -> Script Properties -> add MAILER_SECRET (same value as
 *   the `nudge_mailer_secret` Vault secret in Supabase).
 *   Deploy -> New deployment -> Web app; Execute as: Me; Who has access: Anyone.
 *
 * Nothing secret lives in this file. Safe to keep in the public repo.
 */

var MAX_PER_DAY = 60; // well under Gmail's 100/day limit for consumer accounts
var EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
var SITE_URL = 'https://nituls265.github.io/Nudge/';
// Opens a pre-filled "Unsubscribe" email to this inbox; handle it with query 3
// in supabase/queries/subscribers.sql.
var UNSUBSCRIBE_URL = 'mailto:nudge.here@gmail.com?subject=Unsubscribe';

var SUBJECT = 'Your Nudge setup guide (and day 1 of your 30-day challenge)';

var TEXT = [
  'Hi,',
  '',
  'Thanks for downloading Nudge and joining the 30-day challenge. Here is how to get set up in about two minutes:',
  '',
  '1. Install: open the downloaded file (app-foss-release.apk) from your notifications or Downloads. If Android asks, allow installs from your browser, then tap Install.',
  '2. Turn on tracking: open Nudge and follow the steps on the Home screen to switch on Nudge under Settings > Accessibility. If Android says the setting is restricted, go to Settings > Apps > Nudge, tap the three-dot menu, choose "Allow restricted settings", and try again.',
  '3. Show the bubble (optional): allow "Display over other apps" when asked, then tap Show Bubble in Nudge\'s settings to see your live scroll count.',
  '4. Give it a week: your wellness score works from day one, and its scroll-volume part learns your personal baseline over the first 7 days.',
  '',
  'Nudge counts on your phone. It never reads what is on your screen, and there is no account.',
  '',
  'At day 30 we will email you once to ask how it is going. You can reply to this email anytime with questions or feedback; a real person reads every reply.',
  '',
  '— Nudge',
  SITE_URL,
  '',
  '--',
  'You are getting this because this address was entered at ' + SITE_URL + '. If that was not you, just ignore this email.',
  'Unsubscribe: ' + UNSUBSCRIBE_URL
].join('\n');

var HTML = TEXT
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .split('\n\n').map(function (p) { return '<p>' + p.replace(/\n/g, '<br>') + '</p>'; }).join('')
  .replace('Unsubscribe: ' + UNSUBSCRIBE_URL, '<a href="' + UNSUBSCRIBE_URL + '">Unsubscribe</a>');

function doPost(e) {
  var props = PropertiesService.getScriptProperties();
  var data;
  try {
    data = JSON.parse(e.postData.contents);
  } catch (err) {
    return reply_('bad request');
  }
  var secret = props.getProperty('MAILER_SECRET');
  if (!secret || !data || data.secret !== secret) return reply_('forbidden');

  var email = String(data.email || '').trim();
  if (email.length > 254 || !EMAIL_RE.test(email)) return reply_('bad email');

  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var key = 'sent_' + Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
    var sentToday = Number(props.getProperty(key) || 0);
    if (sentToday >= MAX_PER_DAY || MailApp.getRemainingDailyQuota() < 1) return reply_('daily limit');

    GmailApp.sendEmail(email, SUBJECT, TEXT, { htmlBody: HTML, name: 'Nudge' });
    props.setProperty(key, String(sentToday + 1));
    return reply_('ok');
  } finally {
    lock.releaseLock();
  }
}

/** Run this once from the editor to send the guide to yourself and grant Gmail permission. */
function sendTestToMyself() {
  var me = Session.getActiveUser().getEmail();
  GmailApp.sendEmail(me, '[test] ' + SUBJECT, TEXT, { htmlBody: HTML, name: 'Nudge' });
}

function reply_(msg) {
  return ContentService.createTextOutput(msg);
}
