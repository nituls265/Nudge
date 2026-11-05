/*
 * Nudge landing page — the ONE place to edit site settings.
 *
 * After editing, run from the repo root:
 *     python3 scripts/build_site.py
 * which regenerates docs/qr.svg from DOWNLOAD_URL and copies these values
 * into the static HTML (links that must work with JavaScript disabled, and
 * the Content-Security-Policy). CI fails if you forget.
 *
 * SUPABASE_ANON_KEY is the *publishable* key: safe to ship in a static site.
 * It can only INSERT an email into `waitlist` (see
 * supabase/migrations/20261102120000_waitlist.sql). NEVER put a service_role /
 * secret key in this file or anywhere in this repository.
 */
window.NUDGE_CONFIG = Object.freeze({
  GITHUB_REPO: "nituls265/Nudge",
  // Every GitHub release MUST attach the APK under exactly this file name
  // (no version number), or this "latest" link breaks. release.yml does this.
  DOWNLOAD_URL: "https://github.com/nituls265/Nudge/releases/latest/download/app-foss-release.apk",
  APK_ASSET_NAME: "app-foss-release.apk",
  FDROID_URL: "https://f-droid.org/packages/com.nitulshah.nudge/", // empty = F-Droid button hidden
  SHOW_COUNTERS: false,      // true = show "Downloads on GitHub: N" from stats.json
  SUPABASE_URL: "https://mvfdwgcknmskadhlujgk.supabase.co",
  SUPABASE_ANON_KEY: "sb_publishable_evEXLt2hGLkCHbIY7yweaA_SSu2XwWX",
  CONTACT_EMAIL: "nudge.here@gmail.com",
  // true once the Apps Script mailer is set up (README: "Automatic startup-guide
  // email"). Only changes the wording: the form promises the guide by email.
  GUIDE_EMAIL_ENABLED: true,
  // Search-console ownership tokens. Leave blank until you have one — the
  // build script only emits the <meta> tag when the value is non-empty.
  GSC_VERIFICATION: "SIWR__jD_O1DpjRyXzKeC8BOsf7HbWXKmmQnk3GkL1Q",   // Google Search Console HTML-tag token
  BING_VERIFICATION: "",  // Bing Webmaster Tools HTML-tag token
});
