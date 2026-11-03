/*
 * Nudge landing page behaviour. Progressive enhancement only: with JavaScript
 * off, every link and all content still work (the build script writes the real
 * URLs into the HTML). This file:
 *   1. tailors the page to Android / iOS / desktop (never hides an option),
 *   2. shows a note if the demo video can't autoplay,
 *   3. falls back to the releases page if stats.json says the APK is missing,
 *   4. shows the GitHub download count only when SHOW_COUNTERS is true,
 *   5. after a Download tap, shows the setup guide + optional 30-day challenge,
 *   6. submits the iPhone waitlist and the 30-day challenge to Supabase
 *      (insert-only tables).
 * Network: only same-origin stats.json, plus a Supabase insert when someone
 * submits one of the two email forms. Nothing else.
 */
(function () {
  "use strict";
  var C = window.NUDGE_CONFIG || {};
  var $ = function (id) { return document.getElementById(id); };
  var reduceMotion = !!(window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches);

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
    if (reduceMotion) {
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

  /* 5. Post-download setup guide + 30-day challenge ------------------------ */
  var panel = $("after-download");
  if (panel) {
    if (C.GUIDE_EMAIL_ENABLED === true) {
      var g = panel.querySelectorAll(".guide-email"), n = panel.querySelectorAll(".no-guide-email");
      for (var gi = 0; gi < g.length; gi++) g[gi].hidden = false;
      for (var ni = 0; ni < n.length; ni++) n[ni].hidden = true;
    }
    var showPanel = function () {
      panel.hidden = false;
      panel.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth", block: "start" });
      try { panel.focus({ preventScroll: true }); } catch (e) { panel.focus(); }
    };
    // Let the download link do its normal job; just reveal the guide as well.
    document.addEventListener("click", function (e) {
      var a = e.target && e.target.closest ? e.target.closest(".js-download") : null;
      if (!a || a.href.indexOf("/releases/latest/download/") === -1) return; // not when falling back to the releases page
      setTimeout(showPanel, 0);
    });
    var openGuide = $("open-guide");
    if (openGuide) {
      openGuide.hidden = false;
      openGuide.addEventListener("click", showPanel);
    }
  }

  /* 6. Email forms (insert-only Supabase tables) ----------------------------- */
  // Same rule as the database CHECK constraints, plus a length cap.
  var EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;

  function wireForm(o) {
    var form = $(o.form), fieldset = $(o.fieldset), input = $(o.input);
    var honeypot = $(o.honeypot), status = $(o.status);
    if (!form || !fieldset || !input || !status) return;

    fieldset.disabled = false; // the form is inert until JS is running

    var setStatus = function (msg, kind) {
      status.textContent = msg;
      status.className = "status" + (kind ? " " + kind : "");
    };
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
        setStatus(o.successFor(o.body(email)), "ok");
        form.reset();
        return;
      }

      if (!window.fetch || !C.SUPABASE_URL || !C.SUPABASE_ANON_KEY) {
        setStatus("Sorry, signing up isn’t working right now. Please email " + C.CONTACT_EMAIL + ".", "err");
        return;
      }

      busy = true;
      var sent = o.body(email); // read options before disabling the fieldset
      var body = JSON.stringify(sent);
      fieldset.disabled = true;
      setStatus("Adding you…");

      fetch(C.SUPABASE_URL.replace(/\/+$/, "") + "/rest/v1/" + o.path, {
        method: "POST",
        headers: {
          "apikey": C.SUPABASE_ANON_KEY,
          "Content-Type": "application/json",
          // anon has no SELECT, so never ask for the inserted row back.
          "Prefer": "return=minimal"
        },
        body: body,
        credentials: "omit",
        referrerPolicy: "no-referrer"
      })
        .then(function (r) {
          // Waitlist: 201 = added, 409 = already there. Challenge (RPC): 200/204.
          // Every success shows the same message, so the response never
          // reveals whether an address was already on a list.
          if (r.status === 201 || r.status === 409 || r.status === 200 || r.status === 204) {
            setStatus(o.successFor(sent), "ok");
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
  }

  wireForm({
    form: "waitlist-form", fieldset: "waitlist-fieldset", input: "wl-email",
    honeypot: "wl-website", status: "wl-status", path: "waitlist",
    body: function (email) { return { email: email }; },
    successFor: function () { return "Thanks, you’re on the list. We’ll email you once when Nudge launches on iPhone."; }
  });

  wireForm({
    form: "challenge-form", fieldset: "challenge-fieldset", input: "ch-email",
    honeypot: "ch-website", status: "ch-status",
    // join_challenge() adds new people and, for someone already signed up,
    // records a newly ticked "updates" box. It returns nothing either way.
    path: "rpc/join_challenge",
    body: function (email) {
      var updates = $("ch-updates");
      return { p_email: email, p_wants_updates: !!(updates && updates.checked) };
    },
    // Confirm what they're signed up for (not whether they were already).
    successFor: function (sent) {
      var guide = C.GUIDE_EMAIL_ENABLED === true ? " Check your inbox for the setup guide." : "";
      return sent.p_wants_updates
        ? "You’re in!" + guide + " We’ll check in at day 30 and send you Nudge updates and articles."
        : "You’re in!" + guide + " We’ll check in once at day 30.";
    }
  });
})();
