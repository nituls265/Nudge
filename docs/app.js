/*
 * Nudge landing page behaviour. Progressive enhancement only: with JavaScript
 * off, every link and all content still work (the build script writes the real
 * URLs into the HTML). This file:
 *   1. tailors the page to Android / iOS / desktop (never hides an option),
 *   2. shows a note if the demo video can't autoplay,
 *   3. falls back to the releases page if stats.json says the APK is missing,
 *   4. shows the GitHub download count only when SHOW_COUNTERS is true,
 *   5. submits the iPhone waitlist to Supabase (insert-only).
 * Network: only same-origin stats.json, plus the Supabase insert when someone
 * submits the waitlist form. Nothing else.
 */
(function () {
  "use strict";
  var C = window.NUDGE_CONFIG || {};
  var $ = function (id) { return document.getElementById(id); };

  /* 1. Device-aware layout ------------------------------------------------ */
  var ua = navigator.userAgent || "";
  var isAndroid = /Android/i.test(ua);
  // iPadOS 13+ reports itself as a Mac; touch support gives it away.
  var isIOS = /iPhone|iPad|iPod/i.test(ua) ||
    (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1);
  var device = isAndroid ? "android" : isIOS ? "ios" : "desktop";
  document.documentElement.setAttribute("data-device", device);

  var heroDl = $("hero-download");
  var heroWl = $("hero-waitlist");
  if (device === "ios" && heroDl && heroWl) {
    // Waitlist becomes the primary action; download stays one tap away.
    heroWl.className = "btn btn-primary";
    heroWl.textContent = "Join the iPhone waitlist";
    heroDl.className = "btn btn-secondary js-download";
    heroDl.textContent = "Have an Android phone? Download";
    heroWl.parentNode.insertBefore(heroWl, heroDl);
  }
  if (device === "android") {
    var qr = $("qr-details");
    if (qr) qr.open = false; // QR collapsed, but one tap away if detection is wrong
  }

  /* 2. Demo video ----------------------------------------------------------- */
  var video = $("demo-video");
  var note = $("autoplay-note");
  if (video) {
    var showControls = function () {
      video.controls = true;
      if (note) note.hidden = false;
    };
    var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    if (reduce) {
      video.pause();
      video.removeAttribute("autoplay");
      showControls();
    } else {
      var p = video.play();
      if (p && typeof p.catch === "function") {
        p.catch(function (err) {
          // Only a real autoplay block; ignore AbortError from a load/pause race.
          if (err && err.name === "NotAllowedError") showControls();
        });
      }
    }
  }

  /* 3 + 4. stats.json (same origin, written by .github/workflows/stats.yml) */
  if (window.fetch) {
    fetch("stats.json", { cache: "no-cache" })
      .then(function (r) { return r.ok ? r.json() : null; })
      .then(function (s) {
        if (!s) return;
        if (s.apk_available === false && C.GITHUB_REPO) {
          // The fixed-name asset is missing from the latest release: send people
          // to the release page instead of a 404.
          var fallback = "https://github.com/" + C.GITHUB_REPO + "/releases/latest";
          var links = document.querySelectorAll(".js-download");
          for (var i = 0; i < links.length; i++) links[i].href = fallback;
        }
        if (C.SHOW_COUNTERS === true && typeof s.github_downloads === "number") {
          var el = $("stat-downloads");
          if (el) {
            // Honest label: GitHub release downloads only. F-Droid reports none,
            // so never present this as total users.
            el.textContent = "Downloads on GitHub: " + s.github_downloads.toLocaleString("en-US");
            el.hidden = false;
          }
        }
      })
      .catch(function () { /* stats are optional */ });
  }

  /* 5. iPhone waitlist ------------------------------------------------------ */
  var form = $("waitlist-form");
  var fieldset = $("waitlist-fieldset");
  var input = $("wl-email");
  var honeypot = $("wl-website");
  var status = $("wl-status");
  if (!form || !fieldset || !input || !status) return;

  fieldset.disabled = false; // the form is inert until JS is running

  // Same rule as the database CHECK constraint, plus a length cap.
  var EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
  var setStatus = function (msg, kind) {
    status.textContent = msg;
    status.className = "status" + (kind ? " " + kind : "");
  };
  var SUCCESS = "Thanks, you’re on the list. We’ll email you once when Nudge launches on iPhone.";
  var busy = false;

  input.addEventListener("input", function () {
    if (input.getAttribute("aria-invalid") === "true" && EMAIL_RE.test(input.value.trim())) {
      input.removeAttribute("aria-invalid");
      setStatus("");
    }
  });

  form.addEventListener("submit", function (e) {
    e.preventDefault();
    if (busy) return;
    var email = input.value.trim();

    if (!email || email.length > 254 || !EMAIL_RE.test(email)) {
      input.setAttribute("aria-invalid", "true");
      setStatus("Please enter a valid email address, like name@example.com.", "err");
      input.focus();
      return;
    }
    input.removeAttribute("aria-invalid");

    // Bot filled the hidden field: pretend it worked, send nothing.
    if (honeypot && honeypot.value) {
      setStatus(SUCCESS, "ok");
      form.reset();
      return;
    }

    if (!window.fetch || !C.SUPABASE_URL || !C.SUPABASE_ANON_KEY) {
      setStatus("Sorry, signing up isn’t working right now. Please email " + C.CONTACT_EMAIL + ".", "err");
      return;
    }

    busy = true;
    fieldset.disabled = true;
    setStatus("Adding you…");

    fetch(C.SUPABASE_URL.replace(/\/+$/, "") + "/rest/v1/waitlist", {
      method: "POST",
      headers: {
        "apikey": C.SUPABASE_ANON_KEY,
        "Content-Type": "application/json",
        // anon has no SELECT, so never ask for the inserted row back.
        "Prefer": "return=minimal"
      },
      body: JSON.stringify({ email: email }),
      credentials: "omit",
      referrerPolicy: "no-referrer"
    })
      .then(function (r) {
        // 201 = added. 409 = already on the list: report the same success so
        // the response never reveals whether an address was already there.
        if (r.status === 201 || r.status === 409) {
          setStatus(SUCCESS, "ok");
          form.reset();
        } else if (r.status === 400) {
          input.setAttribute("aria-invalid", "true");
          setStatus("That address doesn’t look right. Please check it and try again.", "err");
        } else {
          setStatus("Something went wrong on our side. Please try again later.", "err");
        }
      })
      .catch(function () {
        setStatus("Couldn’t reach the server. Check your connection and try again.", "err");
      })
      .then(function () {
        busy = false;
        fieldset.disabled = false;
      });
  });
})();
