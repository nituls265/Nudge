/**
 * Nudge mailer (Google Apps Script, runs in the Nudge Gmail account).
 *
 * Supabase calls this web app with {"secret": "...", "email": "...", "type": ...}:
 *   - type missing or "guide": startup guide, once per new 30-day-challenge
 *     sign-up (supabase/migrations/20261103120100_subscribers_guide_mailer.sql).
 *   - type "checkin": the day-30 check-in, from a daily pg_cron job
 *     (supabase/migrations/20261103140000_subscribers_checkin_cron.sql).
 * It checks the shared secret and sends from this Gmail account, so replies
 * land in the same inbox.
 *
 * Setup (one time): see "30-day challenge emails" in the README.
 *   Project Settings -> Script Properties -> add MAILER_SECRET (same value as
 *   the `nudge_mailer_secret` Vault secret in Supabase).
 *   Deploy -> New deployment -> Web app; Execute as: Me; Who has access: Anyone.
 * After editing: Deploy -> Manage deployments -> edit -> Version: New version
 * (keeps the same URL, so Supabase needs no change).
 *
 * Nothing secret lives in this file. Safe to keep in the public repo.
 */

var EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
var SITE_URL = 'https://nituls265.github.io/Nudge/';
// Opens a pre-filled "Unsubscribe" email to this inbox; handle it with query 3
// in supabase/queries/subscribers.sql.
var UNSUBSCRIBE_URL = 'mailto:nudge.here@gmail.com?subject=Unsubscribe';

function footer_(why) {
  return ['', '--', why, 'Unsubscribe: ' + UNSUBSCRIBE_URL];
}

// Daily caps are counted separately and add up to 100, Gmail's daily limit
// for consumer accounts, so a busy sign-up day never blocks check-ins.
var EMAILS = {
  guide: {
    cap: 60,
    subject: 'Your Nudge setup guide (and day 1 of your 30-day challenge)',
    text: [
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
      SITE_URL
    ].concat(footer_('You are getting this because this address was entered at ' + SITE_URL + '. If that was not you, just ignore this email.'))
  },
  checkin: {
    cap: 40,
    subject: '30 days with Nudge: how did it go?',
    text: [
      'Hi,',
      '',
      'It\'s been about 30 days since you downloaded Nudge and joined the 30-day challenge. We\'d love to hear how it went. Just hit reply; a sentence or two is plenty:',
      '',
      '1. Are you still using Nudge? If not, what made you stop?',
      '2. Has it changed how you use your phone? Your wellness score on day 1 vs. today is a great clue.',
      '3. What one thing would make Nudge more useful for you?',
      '',
      'Every reply is read by a real person and helps decide what gets built next.',
      '',
      'Thanks for giving it a try,',
      '— Nudge',
      SITE_URL
    ].concat(footer_('You are getting this one-time check-in because you joined the 30-day challenge at ' + SITE_URL + '.'))
  }
};

function render_(kind) {
  var e = EMAILS[kind];
  var text = e.text.join('\n');
  var html = text
    .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    .split('\n\n').map(function (p) { return '<p>' + p.replace(/\n/g, '<br>') + '</p>'; }).join('')
    .replace('Unsubscribe: ' + UNSUBSCRIBE_URL, '<a href="' + UNSUBSCRIBE_URL + '">Unsubscribe</a>');
  return { subject: e.subject, text: text, html: html };
}

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

  var kind = data.type ? String(data.type) : 'guide';
  if (!EMAILS.hasOwnProperty(kind)) return reply_('bad type');

  var email = String(data.email || '').trim();
  if (email.length > 254 || !EMAIL_RE.test(email)) return reply_('bad email');

  var lock = LockService.getScriptLock();
  lock.waitLock(10000);
  try {
    var key = 'sent_' + kind + '_' + Utilities.formatDate(new Date(), 'UTC', 'yyyy-MM-dd');
    var sentToday = Number(props.getProperty(key) || 0);
    if (sentToday >= EMAILS[kind].cap || MailApp.getRemainingDailyQuota() < 1) return reply_('daily limit');

    var m = render_(kind);
    GmailApp.sendEmail(email, m.subject, m.text, { htmlBody: m.html, name: 'Nudge' });
    props.setProperty(key, String(sentToday + 1));
    return reply_('ok');
  } finally {
    lock.releaseLock();
  }
}

/** Run from the editor to send yourself the startup guide (also grants Gmail permission). */
function sendTestToMyself() {
  sendTest_('guide');
}

/** Run from the editor to send yourself the day-30 check-in. */
function sendCheckinTestToMyself() {
  sendTest_('checkin');
}

function sendTest_(kind) {
  var m = render_(kind);
  GmailApp.sendEmail(Session.getActiveUser().getEmail(), '[test] ' + m.subject, m.text, { htmlBody: m.html, name: 'Nudge' });
}

function reply_(msg) {
  return ContentService.createTextOutput(msg);
}
