/* SleepSnitch native bridge: audio capture and clips come from the Android foreground service. */
(function () {
  "use strict";
  var SN = window.SnitchNative;
  if (!SN) return;

  function labelsMap() {
    try { return JSON.parse(localStorage.getItem("snitch_labels") || "{}"); } catch (e) { return {}; }
  }
  function saveLabels(m) {
    try { localStorage.setItem("snitch_labels", JSON.stringify(m)); } catch (e) {}
  }
  window.__labelSet = function (id, label) { var m = labelsMap(); m[id] = label; saveLabels(m); };
  window.__labelDel = function (id) { var m = labelsMap(); delete m[id]; saveLabels(m); };

  window.__nativeRms = 0;
  window.__nativePoll = null;
  var seen = {};

  function startPoll() {
    if (window.__nativePoll) clearInterval(window.__nativePoll);
    window.__nativePoll = setInterval(function () {
      var st = {};
      try { st = JSON.parse(SN.state() || "{}"); } catch (e) { return; }
      window.__nativeRms = st.rms || 0;
      if (st.peak && st.peak > peakRms) peakRms = st.peak;
      if (st.events && st.events.length) {
        for (var i = 0; i < st.events.length; i++) {
          var ev = st.events[i];
          if (!seen[ev.id]) { seen[ev.id] = 1; eventsThisSession++; }
        }
        renderEvents();
      }
      if (st.error) toast(st.error);
      if (st.recording === false && sessionActive) stopSession();
    }, 200);
  }
  function stopPoll() {
    if (window.__nativePoll) { clearInterval(window.__nativePoll); window.__nativePoll = null; }
  }

  window.getRms = function () { return window.__nativeRms || 0; };
  window.startKeepAlive = function () {};
  window.stopKeepAlive = function () {};
  window.requestWakeLock = async function () {};

  window.startSessionInner = async function (opts) {
    var auto = !!(opts && opts.auto);
    $("micHelp").hidden = true;
    if (!SN.hasMic()) {
      showNotice("Allow the microphone, then tap the button again.");
      try { SN.askMic(); } catch (e) {}
      return;
    }
    var sens = Number($("sensitivity").value) || 70;
    var clipMs = Math.round((Number($("clipLength").value) || 6) * 1000);
    try { SN.start(sens, clipMs, 0); }
    catch (e) { toast("Could not start recording: " + e); return; }

    sessionActive = true;
    autoStarted = auto;
    sessionStart = Date.now();
    peakRms = 0; lastEventAt = 0; aboveCount = 0; eventsThisSession = 0;
    history = []; meterLevel = 0; pending = null;
    $("lastSummary").hidden = true;
    updateSessionUI();
    clearInterval(elapsedTimer);
    elapsedTimer = setInterval(updateStats, 500);
    startPoll();
    updateStats();
    toast("Listening in the background — lock the screen and sleep.");
  };

  /* the native engine does detection and builds clips, so the page must not */
  window.beginEvent = function () {};
  window.finishEvent = async function () { pending = null; };

  var realStop = window.stopSessionInner;
  window.stopSessionInner = async function () {
    stopPoll();
    try { SN.stop(); } catch (e) {}
    return realStop.apply(this, arguments);
  };

  window.getClips = async function () {
    var list = [];
    try { list = JSON.parse(SN.events() || "[]"); } catch (e) { return []; }
    var labels = labelsMap();
    return list.map(function (ev) {
      var lvl = Number(ev.level) || 0;
      return {
        id: ev.id,
        time: ev.t,
        label: labels[ev.id] || "Other",
        level: lvl,
        db: Math.round(20 * Math.log10(Math.max(0.000001, lvl))),
        duration: Math.round((Number(ev.dur) || 0) / 1000) + " sec",
        native: true
      };
    }).sort(function (a, b) { return a.time - b.time; });
  };

  window.playClip = function (e, btn) {
    try {
      SN.play(e.id);
      if (btn) {
        btn.classList.add("playing");
        setTimeout(function () { btn.classList.remove("playing"); }, 9000);
      }
    } catch (err) { toast("Could not play this clip."); }
  };

  window.downloadClip = function (e, silent) {
    try { SN.saveAudio(e.id); if (!silent) toast("Saved to Downloads"); }
    catch (err) { toast("Could not save this clip."); }
  };

  window.downloadAllClips = async function () {
    var list = await getClips();
    if (!list.length) { toast("No clips to save yet."); return; }
    for (var i = 0; i < list.length; i++) { try { SN.saveAudio(list[i].id); } catch (e) {} }
    toast("Saving " + list.length + " clips to Downloads");
  };

  window.deleteClip = async function (id) {
    try { SN.delete(id); } catch (e) {}
    window.__labelDel(id);
    await renderEvents();
  };

  window.clearClips = async function () {
    try { SN.clearAll(); } catch (e) {}
    saveLabels({});
    await renderEvents();
  };

  window.refreshDevices = async function () {
    var sel = $("deviceSelect");
    if (sel) {
      var row = sel.parentElement;
      while (row && row !== document.body && !row.className.match(/field|row|setting/)) row = row.parentElement;
      if (row) row.style.display = "none";
      else sel.style.display = "none";
    }
  };

  window.runDiagnostics = async function () {
    var st = {};
    try { st = JSON.parse(SN.state() || "{}"); } catch (e) {}
    var clips = 0;
    try { clips = (JSON.parse(SN.events() || "[]")).length; } catch (e) {}
    diagRow("Native recorder", st.recording ? "running" : "idle", !!st.recording);
    diagRow("Microphone permission", SN.hasMic() ? "granted" : "missing", !!SN.hasMic());
    diagRow("Input level", (Number(st.rms) || 0).toFixed(4), (Number(st.rms) || 0) > 0);
    diagRow("Noise floor", (Number(st.floor) || 0).toFixed(4), true);
    diagRow("Trigger level", (Number(st.thr) || 0).toFixed(4), true);
    diagRow("Clips stored", String(clips), true);
    if (st.error) diagRow("Recorder error", String(st.error), false);
  };

  window.exportBackup = function () { toast("Use Download all clips instead — backup files need the browser version."); };
  window.importBackup = function () { toast("Restore is only available in the browser version."); };

  /* label edits: capture before the page's own handler, persist natively */
  document.addEventListener("change", function (ev) {
    var t = ev.target;
    if (!t || !t.classList || !t.classList.contains("labelSelect")) return;
    var row = t.closest(".event");
    var id = row ? row.getAttribute("data-id") : null;
    if (id) window.__labelSet(Number(id), t.value);
  }, true);

  function boot() {
    try { refreshDevices(); } catch (e) {}
    ["backupBtn", "importBtn"].forEach(function (id) {
      var el = $(id);
      if (el) el.style.display = "none";
    });
    try {
      var st = JSON.parse(SN.state() || "{}");
      if (st.recording) {
        sessionActive = true;
        sessionStart = Date.now();
        updateSessionUI();
        clearInterval(elapsedTimer);
        elapsedTimer = setInterval(updateStats, 500);
        startPoll();
      }
    } catch (e) {}
    renderEvents();
  }
  if (document.readyState === "complete" || document.readyState === "interactive") setTimeout(boot, 60);
  else document.addEventListener("DOMContentLoaded", function () { setTimeout(boot, 60); });
})();
