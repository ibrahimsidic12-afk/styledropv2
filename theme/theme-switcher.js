/* ============================================================================
 * StyleDrop — Theme Runtime (theme-switcher.js)
 * ----------------------------------------------------------------------------
 * Zero dependencies. ~4 KB unminified. Safe to load with <script defer>.
 *
 * Responsibilities
 *   1. Resolve mode:  light | dark | auto  (auto follows the OS, live)
 *   2. Resolve contrast: normal | high      (WCAG AAA override)
 *   3. Resolve motion: normal | reduced     (vestibular-safety override)
 *   4. Persist all three in localStorage (+ mirror to the native shell)
 *   5. Paint [data-theme] / [data-contrast] / [data-motion] on <html>
 *   6. Keep every .sd-theme-switch control in sync (aria-pressed, labels)
 *   7. Broadcast changes across tabs, listen to OS + forced-colors changes
 *
 * Why an attribute on <html> and not a .dark class on <body>?
 *   <html> exists before <body> is parsed, so the mode can be applied with zero
 *   flash of the wrong theme (see the inline bootstrap snippet in the docs).
 *
 * Public API (window.StyleDropTheme)
 *   .mode / .setMode(m) / .toggleMode()
 *   .contrast / .setContrast(c) / .toggleContrast()
 *   .motion / .setMotion(m) / .toggleMotion()
 *   .resolved                 -> "light" | "dark"  (what is actually painted)
 *   .preference               -> { mode, contrast, motion }
 *   .subscribe(fn)            -> unsubscribe fn
 * ========================================================================== */
(function (global, doc) {
  "use strict";

  var STORAGE_KEY = "styledrop.theme.v1";
  var HTML = doc.documentElement;
  var MODES = ["light", "dark", "auto"];
  var CONTRASTS = ["normal", "high"];
  var MOTIONS = ["normal", "reduced"];

  /* ---- 1. Preference store -------------------------------------------------
   * localStorage can throw (Safari private mode, embedded WebView with
   * storage disabled) so every access is guarded. */
  var memoryFallback = null;

  function readStore() {
    try {
      var raw = global.localStorage.getItem(STORAGE_KEY);
      if (raw) return JSON.parse(raw);
    } catch (e) { /* storage unavailable */ }
    return memoryFallback || {};
  }

  function writeStore(state) {
    memoryFallback = state;
    try {
      global.localStorage.setItem(STORAGE_KEY, JSON.stringify(state));
    } catch (e) { /* ignore — in-memory only this session */ }
  }

  function oneOf(value, allowed, fallback) {
    return allowed.indexOf(value) !== -1 ? value : fallback;
  }

  var stored = readStore();
  var state = {
    mode:     oneOf(stored.mode,     MODES,     "auto"),
    contrast: oneOf(stored.contrast, CONTRASTS, "normal"),
    motion:   oneOf(stored.motion,   MOTIONS,   "normal")
  };

  /* ---- 2. OS signal listeners --------------------------------------------- */
  var darkQuery   = global.matchMedia ? global.matchMedia("(prefers-color-scheme: dark)") : null;
  var reduceQuery = global.matchMedia ? global.matchMedia("(prefers-reduced-motion: reduce)") : null;
  var forcedQuery = global.matchMedia ? global.matchMedia("(forced-colors: active)") : null;

  function onMediaChange(event) {
    // Re-resolve whenever the OS flips. Only matters while that axis is "auto"
    // or when the OS *reduces* motion (which we always honour, never oppose).
    if (event.matches && event.media.indexOf("prefers-reduced-motion") !== -1 &&
        state.motion === "normal") {
      // OS now asks for reduced motion — honour it for this session without
      // overwriting the user's saved preference.
      applySystemMotion(true);
    }
    if (event.media.indexOf("prefers-color-scheme") !== -1) apply();
    if (event.media.indexOf("forced-colors") !== -1) apply();
  }

  function bindMedia(query) {
    if (!query) return;
    if (query.addEventListener) query.addEventListener("change", onMediaChange);
    else if (query.addListener) query.addListener(onMediaChange); // Safari < 14
  }
  bindMedia(darkQuery);
  bindMedia(reduceQuery);
  bindMedia(forcedQuery);

  /* ---- 3. Resolution ------------------------------------------------------ */
  function resolvedTheme() {
    if (state.mode === "light" || state.mode === "dark") return state.mode;
    return darkQuery && darkQuery.matches ? "dark" : "light";
  }

  function resolvedMotion() {
    if (state.motion === "reduced") return "reduced";
    // System-level reduce always wins over an explicit "normal" preference.
    if (reduceQuery && reduceQuery.matches) return "reduced";
    return "normal";
  }

  function applySystemMotion() {
    if (resolvedMotion() === "reduced") {
      HTML.setAttribute("data-motion", "reduced");
    } else {
      HTML.setAttribute("data-motion", state.motion);
    }
    publish();
  }

  /* ---- 4. Paint ----------------------------------------------------------- */
  function apply() {
    var theme = resolvedTheme();
    HTML.setAttribute("data-theme", theme);              // resolved, never "auto"
    HTML.setAttribute("data-theme-pref", state.mode);    // what the user chose
    HTML.setAttribute("data-contrast", state.contrast);
    HTML.setAttribute("data-motion", resolvedMotion());
    HTML.style.colorScheme = theme;                      // native form controls

    // theme-color for the browser chrome / PWA title bar, read from the live
    // token so it can never drift from the palette.
    var pageBg = getComputedStyle(HTML).getPropertyValue("--color-page-bg").trim();
    var metaTheme = doc.querySelector('meta[name="theme-color"]');
    if (metaTheme && pageBg) metaTheme.setAttribute("content", pageBg);

    syncControls();
    publish();
  }

  /* ---- 5. Controls -------------------------------------------------------- */
  function syncControls() {
    var groups = [
      { attr: "data-theme-set",    value: state.mode,     on: state.mode },
      { attr: "data-contrast-set", value: state.contrast, on: state.contrast },
      { attr: "data-motion-set",   value: state.motion,   on: state.motion }
    ];
    Array.prototype.forEach.call(
      doc.querySelectorAll("[data-theme-set],[data-contrast-set],[data-motion-set]"),
      function (btn) {
        var group = null;
        for (var i = 0; i < groups.length; i++) {
          if (btn.hasAttribute(groups[i].attr)) { group = groups[i]; break; }
        }
        if (!group) return;
        var pressed = btn.getAttribute(group.attr) === group.value;
        btn.setAttribute("aria-pressed", pressed ? "true" : "false");
        if (group.attr === "data-theme-set" && btn.hasAttribute("data-theme-set-title")) {
          var label = pressed
            ? btn.getAttribute("data-theme-set-title") + " (active)"
            : btn.getAttribute("data-theme-set-title");
          btn.setAttribute("aria-label", label);
        }
      }
    );
    // Reflect on <html> so CSS can style "auto is on but OS is light".
    HTML.setAttribute("data-theme-source", state.mode === "auto" ? "system" : "user");
  }

  function bindControls() {
    doc.addEventListener("click", function (event) {
      var btn = event.target.closest
        ? event.target.closest("[data-theme-set],[data-contrast-set],[data-motion-set]")
        : null;
      if (!btn) return;
      if (btn.hasAttribute("data-theme-set"))    api.setMode(btn.getAttribute("data-theme-set"));
      if (btn.hasAttribute("data-contrast-set")) api.setContrast(btn.getAttribute("data-contrast-set"));
      if (btn.hasAttribute("data-motion-set"))   api.setMotion(btn.getAttribute("data-motion-set"));
    });
  }

  /* ---- 6. Subscribers ----------------------------------------------------- */
  var subscribers = [];
  function publish() {
    var detail = {
      preference: { mode: state.mode, contrast: state.contrast, motion: state.motion },
      resolved: { theme: resolvedTheme(), motion: resolvedMotion() }
    };
    for (var i = 0; i < subscribers.length; i++) {
      try { subscribers[i](detail); } catch (e) { /* never break the chain */ }
    }
    try {
      doc.dispatchEvent(new CustomEvent("styledrop:themechange", { detail: detail }));
    } catch (e) { /* CustomEvent unsupported */ }
    // Mirror into the native Android shell, if present, so Compose screens can
    // react without a reload (MainActivity adds a @JavascriptInterface).
    try {
      if (global.StyleDropNative && global.StyleDropNative.onThemeChange) {
        global.StyleDropNative.onThemeChange(JSON.stringify(detail));
      }
    } catch (e) { /* not in the app shell */ }
  }

  /* ---- 6b. Colour utilities -----------------------------------------------
   * Garment colours are DATA. A swatch must paint the real colour in every
   * mode, so the ink drawn on top has to be chosen by measurement, never by a
   * theme token. These helpers implement WCAG 2.1 relative luminance. */
  function parseColor(input) {
    if (!input) return null;
    var c = String(input).trim();
    var m = c.match(/^#([0-9a-f]{3})$/i);
    if (m) return [0, 1, 2].map(function (i) { return parseInt(m[1][i] + m[1][i], 16) / 255; });
    m = c.match(/^#([0-9a-f]{6})$/i);
    if (m) return [0, 2, 4].map(function (i) { return parseInt(m[1].substr(i, 2), 16) / 255; });
    m = c.match(/^rgba?\(\s*([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)/i);
    if (m) return [1, 2, 3].map(function (i) { return parseFloat(m[i]) / 255; });
    return null;
  }

  function toLinear(channel) {
    return channel <= 0.03928 ? channel / 12.92 : Math.pow((channel + 0.055) / 1.055, 2.4);
  }

  function luminance(rgb) {
    return 0.2126 * toLinear(rgb[0]) + 0.7152 * toLinear(rgb[1]) + 0.0722 * toLinear(rgb[2]);
  }

  function contrastRatio(colorA, colorB) {
    var a = parseColor(colorA), b = parseColor(colorB);
    if (!a || !b) return null;
    var la = luminance(a), lb = luminance(b);
    var hi = Math.max(la, lb), lo = Math.min(la, lb);
    return (hi + 0.05) / (lo + 0.05);
  }

  /**
   * Pick the ink that reads best ON a given garment colour.
   * Tries white, near-black and the two brand inks; returns the highest
   * contrast option plus the measured ratio so callers can assert a floor.
   */
  function bestInk(color) {
    var candidates = ["#FFFFFF", "#12110D", "#1B1A17", "#FAF7F0"];
    var best = { ink: "#FFFFFF", tone: "light", ratio: 0 };
    for (var i = 0; i < candidates.length; i++) {
      var r = contrastRatio(color, candidates[i]);
      if (r !== null && r > best.ratio) {
        best = { ink: candidates[i], tone: candidates[i] === "#FFFFFF" ? "light" : "dark", ratio: r };
      }
    }
    return best;
  }

  /**
   * Walk .sd-swatch under `root` and stamp data-swatch-tone / --swatch-ink from
   * the REAL colour found in --garment-color. Safe to call repeatedly.
   */
  function applySwatchTones(root) {
    var scope = root && root.querySelectorAll ? root : doc;
    var nodes = scope.querySelectorAll(".sd-swatch");
    Array.prototype.forEach.call(nodes, function (node) {
      var inline = node.style.getPropertyValue("--garment-color");
      var color = (inline && inline.trim()) ||
        getComputedStyle(node).getPropertyValue("--garment-color").trim();
      var label = node.querySelector(".sd-swatch__label");
      if (!label) return;
      if (!color || color === "initial") {
        node.setAttribute("data-swatch-tone", "neutral");
        node.style.setProperty("--swatch-ink", "#FFFFFF");
        return;
      }
      var choice = bestInk(color);
      node.setAttribute("data-swatch-tone", choice.tone);
      node.style.setProperty("--swatch-ink", choice.ink);
      node.setAttribute("data-swatch-contrast", choice.ratio.toFixed(2));
      if (choice.ratio < 4.5) {
        node.setAttribute("data-swatch-warn", "low-contrast");
      } else if (node.hasAttribute("data-swatch-warn")) {
        node.removeAttribute("data-swatch-warn");
      }
    });
  }

  var color = {
    parse: parseColor,
    luminance: luminance,
    contrastRatio: contrastRatio,
    bestInk: bestInk
  };

  /* ---- 7. Public API ------------------------------------------------------ */
  var api = {
    color: color,
    applySwatchTones: applySwatchTones,
    get mode()     { return state.mode; },
    get contrast() { return state.contrast; },
    get motion()   { return state.motion; },
    get resolved() { return resolvedTheme(); },
    get preference() { return { mode: state.mode, contrast: state.contrast, motion: state.motion }; },

    setMode: function (mode) {
      mode = oneOf(mode, MODES, "auto");
      if (mode === state.mode) return mode;
      state.mode = mode; writeStore(state); apply(); return mode;
    },
    setContrast: function (value) {
      value = oneOf(value, CONTRASTS, "normal");
      if (value === state.contrast) return value;
      state.contrast = value; writeStore(state); apply(); return value;
    },
    setMotion: function (value) {
      value = oneOf(value, MOTIONS, "normal");
      if (value === state.motion) return value;
      state.motion = value; writeStore(state); apply(); return value;
    },

    toggleMode: function () {
      // Cycle light -> dark -> auto -> light
      var next = MODES[(MODES.indexOf(state.mode) + 1) % MODES.length];
      return api.setMode(next);
    },
    toggleContrast: function () {
      return api.setContrast(state.contrast === "high" ? "normal" : "high");
    },
    toggleMotion: function () {
      return api.setMotion(state.motion === "reduced" ? "normal" : "reduced");
    },

    subscribe: function (fn) {
      if (typeof fn !== "function") return function () {};
      subscribers.push(fn);
      fn({ preference: api.preference, resolved: { theme: resolvedTheme(), motion: resolvedMotion() } });
      return function () {
        subscribers = subscribers.filter(function (f) { return f !== fn; });
      };
    },

    /** Recovery escape hatch — clears the stored preference and goes back to auto. */
    reset: function () {
      state = { mode: "auto", contrast: "normal", motion: "normal" };
      writeStore(state);
      apply();
    }
  };

  global.StyleDropTheme = api;

  /* ---- 8. Cross-tab + boot ------------------------------------------------ */
  global.addEventListener("storage", function (event) {
    if (event.key !== STORAGE_KEY || !event.newValue) return;
    try {
      var incoming = JSON.parse(event.newValue);
      state.mode     = oneOf(incoming.mode,     MODES,     "auto");
      state.contrast = oneOf(incoming.contrast, CONTRASTS, "normal");
      state.motion   = oneOf(incoming.motion,   MOTIONS,   "normal");
      apply();
    } catch (e) { /* malformed — ignore */ }
  });

  function boot() {
    bindControls();
    apply();
    applySwatchTones();
    // New garment cards can be appended at any time (infinite scroll, category
    // change) — re-measure their swatches as they appear.
    if (global.MutationObserver && doc.body) {
      new MutationObserver(function () { applySwatchTones(); })
        .observe(doc.body, { childList: true, subtree: true });
    }
  }

  if (doc.readyState === "loading") {
    doc.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }

  // If the inline bootstrap in <head> already painted the attributes, do not
  // re-run apply() — nothing is stale. boot() above is idempotent anyway.
})(window, document);
