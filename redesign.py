import re

p = 'app/src/main/assets/index.html'
src = open(p, encoding='utf-8').read()

# ------------------------------------------------------------------
# Keep the script exactly as-is; replace <style> and <body> markup only
# ------------------------------------------------------------------
script_start = src.index('<script>')
script_block = src[script_start:]            # <script> ... </script></body></html>

NEW_CSS = r"""
:root{
  color-scheme:dark;
  --bg:#08080a; --surface:#0f0f12; --surface2:#141418; --hair:rgba(255,255,255,.07); --hair2:rgba(255,255,255,.12);
  --text:#ececf1; --muted:#8a8a96; --dim:#5b5b66;
  --accent:#a78bfa; --accent2:#7c5cff; --ok:#5eead4; --warn:#fbbf24; --bad:#fb7185;
  --mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;
  --sans:Inter,ui-sans-serif,system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;background:var(--bg)}
body{
  color:var(--text);font:14px/1.5 var(--sans);min-height:100vh;
  background:
    radial-gradient(900px 420px at 85% -8%,rgba(124,92,255,.16),transparent 60%),
    radial-gradient(700px 360px at -10% 30%,rgba(94,234,212,.05),transparent 60%),
    var(--bg);
  padding-bottom:env(safe-area-inset-bottom);
}
button,input,select{font:inherit;color:inherit}
button{cursor:pointer;border:0;background:none}
.app{max-width:760px;margin:0 auto;padding:calc(18px + env(safe-area-inset-top)) 16px 56px}

/* ---- top bar ---- */
.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:22px}
.brand{display:flex;align-items:center;gap:11px}
.logo{width:34px;height:34px;border-radius:10px;display:grid;place-items:center;font-size:16px;
  background:linear-gradient(160deg,#1a1a22,#0d0d11);border:1px solid var(--hair2);color:var(--accent);
  box-shadow:0 0 0 1px rgba(0,0,0,.4),0 6px 18px -6px rgba(124,92,255,.55)}
.brand h1{margin:0;font-size:15px;font-weight:650;letter-spacing:-.2px}
.brand p{margin:0;font:11px var(--mono);color:var(--dim);letter-spacing:.3px}
.privacy{display:flex;align-items:center;gap:7px;font:10.5px var(--mono);color:var(--muted);
  padding:6px 10px;border:1px solid var(--hair);border-radius:999px;background:var(--surface)}
.dot{display:inline-block;width:6px;height:6px;border-radius:50%;background:var(--ok);box-shadow:0 0 8px var(--ok)}

/* ---- cards ---- */
.panel{background:var(--surface);border:1px solid var(--hair);border-radius:16px;padding:18px;margin-bottom:12px;
  box-shadow:inset 0 1px 0 rgba(255,255,255,.035)}
.eyebrow,.count,.label-mono{font:10.5px var(--mono);letter-spacing:.9px;text-transform:uppercase;color:var(--dim)}
.section-head{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:14px}
.section-head h3,.side-title{margin:0;font-size:13px;font-weight:600;color:var(--text);letter-spacing:-.1px}
.count{padding:3px 8px;border:1px solid var(--hair);border-radius:6px;background:var(--surface2)}

/* ---- hero ---- */
.hero{display:block}
.hero h2{margin:8px 0 6px;font-size:clamp(26px,7vw,34px);line-height:1.08;letter-spacing:-1.1px;font-weight:650}
.hero h2 em{font-style:normal;color:var(--dim)}
.sub{margin:0 0 16px;color:var(--muted);font-size:13px;max-width:46ch}
.recbar{display:flex;flex-wrap:wrap;align-items:center;gap:8px}
.primary,.danger,.secondary{border-radius:10px;padding:11px 16px;font-weight:600;font-size:13px;transition:transform .15s,box-shadow .2s,opacity .2s}
.primary{background:linear-gradient(180deg,#b79cff,#8466ff);color:#0b0714;
  box-shadow:0 0 0 1px rgba(255,255,255,.18) inset,0 10px 26px -10px rgba(124,92,255,.9)}
.danger{background:var(--surface2);border:1px solid var(--hair2);color:var(--bad)}
.secondary{width:100%;background:var(--surface2);border:1px solid var(--hair);color:var(--text);text-align:center}
.primary:active,.danger:active,.secondary:active{transform:scale(.98)}
button:disabled{opacity:.35;pointer-events:none}
.status{display:flex;align-items:center;gap:7px;margin-left:auto;font:11px var(--mono);color:var(--muted)}
.status .dot{background:var(--dim);box-shadow:none}
.status.live{color:var(--ok)}
.status.live .dot{background:var(--ok);box-shadow:0 0 10px var(--ok);animation:pulse 1.6s ease-in-out infinite}
@keyframes pulse{50%{opacity:.35}}

.wavebox{margin-top:16px;height:84px;border-radius:12px;background:#0a0a0d;border:1px solid var(--hair);position:relative;overflow:hidden}
.wavebox canvas{width:100%;height:100%;display:block}
.wave-label{position:absolute;left:11px;top:8px;font:9.5px var(--mono);letter-spacing:1px;text-transform:uppercase;color:var(--dim);z-index:1}
.meter{height:3px;border-radius:2px;background:var(--surface2);margin-top:10px;overflow:hidden}
#meterFill{height:100%;width:0;background:linear-gradient(90deg,var(--ok),var(--accent));border-radius:2px;transition:width .12s}

/* ---- stats ---- */
.mini-stats{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}
.stat{background:var(--surface2);border:1px solid var(--hair);border-radius:12px;padding:13px 14px}
.stat small{display:block;font:10px var(--mono);letter-spacing:.8px;text-transform:uppercase;color:var(--dim)}
.stat strong{display:block;font-size:24px;font-weight:600;letter-spacing:-.8px;margin-top:3px}
.stat span{font-size:11px;color:var(--muted)}

/* ---- events ---- */
.events{display:flex;flex-direction:column;gap:8px}
.event{display:grid;grid-template-columns:36px 1fr auto;gap:11px;align-items:center;padding:11px;
  background:var(--surface2);border:1px solid var(--hair);border-radius:12px}
.event-icon{width:36px;height:36px;border-radius:10px;display:grid;place-items:center;font-size:16px;
  background:#0b0b0e;border:1px solid var(--hair)}
.event h4{margin:0;font-size:13px;font-weight:600}
.event p{margin:1px 0 0;font:11px var(--mono);color:var(--muted)}
.event-actions{display:flex;align-items:center;gap:7px;flex-wrap:wrap;justify-content:flex-end}
.tag{font:9px var(--mono);letter-spacing:.8px;color:var(--ok);border:1px solid rgba(94,234,212,.25);padding:2px 6px;border-radius:5px}
.tiny{background:#0b0b0e;border:1px solid var(--hair2);border-radius:8px;padding:5px 7px;font-size:11px;color:var(--text)}
.event audio{width:100%;grid-column:1/-1;height:34px;margin-top:2px;filter:invert(.9) hue-rotate(200deg) saturate(.6)}
.empty{text-align:center;color:var(--muted);padding:26px 10px;font-size:13px}
.empty .big{font-size:28px;margin-bottom:6px;opacity:.8}

/* ---- settings ---- */
.settings{display:flex;flex-direction:column;gap:12px}
.field label{display:block;font:10.5px var(--mono);letter-spacing:.7px;text-transform:uppercase;color:var(--dim);margin-bottom:6px}
.field input,.field select{width:100%;background:#0a0a0d;border:1px solid var(--hair2);border-radius:10px;padding:10px 11px;outline:none;color:var(--text);
  appearance:none;-webkit-appearance:none}
.field select{background-image:linear-gradient(45deg,transparent 50%,var(--dim) 50%),linear-gradient(135deg,var(--dim) 50%,transparent 50%);
  background-position:calc(100% - 17px) 17px,calc(100% - 12px) 17px;background-size:5px 5px,5px 5px;background-repeat:no-repeat;padding-right:30px}
.field input:focus,.field select:focus{border-color:var(--accent);box-shadow:0 0 0 3px rgba(167,139,250,.15)}
.two{display:grid;grid-template-columns:1fr 1fr;gap:10px}
.toggleline{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:11px 12px;background:var(--surface2);
  border:1px solid var(--hair);border-radius:10px;font-size:13px;color:var(--text)}
.toggle{appearance:none;-webkit-appearance:none;width:38px;height:22px;border-radius:999px;background:#26262d;position:relative;flex:none;
  transition:background .2s;border:1px solid var(--hair2);cursor:pointer}
.toggle::after{content:"";position:absolute;top:2px;left:2px;width:16px;height:16px;border-radius:50%;background:#cfcfd8;transition:transform .2s,background .2s}
.toggle:checked{background:var(--accent2);border-color:transparent}
.toggle:checked::after{transform:translateX(16px);background:#fff}
.note{margin:2px 0 0;color:var(--dim);font-size:11.5px;line-height:1.55}

.footer{margin-top:18px;text-align:center;font:10px var(--mono);letter-spacing:.5px;color:var(--dim)}
.toast{position:fixed;left:50%;bottom:calc(22px + env(safe-area-inset-bottom));transform:translate(-50%,16px);max-width:88vw;
  background:#17171c;border:1px solid var(--hair2);color:var(--text);padding:10px 14px;border-radius:12px;font-size:12.5px;
  opacity:0;pointer-events:none;transition:.25s;z-index:20;box-shadow:0 18px 40px -12px rgba(0,0,0,.8)}
.toast.show{opacity:1;transform:translate(-50%,0)}

/* ---- mic-permission gate ---- */
.gate{display:none;margin-top:12px;padding:12px 14px;border-radius:12px;background:rgba(251,113,133,.07);
  border:1px solid rgba(251,113,133,.25);color:#fecdd3;font-size:12.5px}
.gate.show{display:block}
.gate button{margin-top:9px;padding:8px 12px;border-radius:9px;background:var(--surface2);border:1px solid var(--hair2);font-size:12px;font-weight:600}
"""

NEW_BODY = r"""
<body>
<div class="app">
<header class="top">
  <div class="brand"><div class="logo">&#9790;</div><div><h1>SleepSnitch</h1><p>night recorder</p></div></div>
  <div class="privacy"><i class="dot"></i> on-device only</div>
</header>

<section class="panel hero">
  <div class="eyebrow">Tonight &middot; Recorder</div>
  <h2>Catch the sounds<br><em>you sleep through.</em></h2>
  <p class="sub">Listens in the background with the screen off, spots louder moments and saves short clips for your morning review.</p>
  <div class="recbar">
    <button class="primary" id="startBtn">&#9654;&nbsp; Start listening</button>
    <button class="danger" id="stopBtn" disabled>&#9632;&nbsp; Stop</button>
    <div class="status" id="status"><i class="dot"></i><span>Microphone off</span></div>
  </div>
  <div class="gate" id="micGate"><span id="micGateText">Microphone access is off for SleepSnitch.</span><br><button id="micGateBtn">Allow microphone</button></div>
  <div class="wavebox"><span class="wave-label">Live sound activity</span><canvas id="wave"></canvas></div>
  <div style="display:flex;justify-content:space-between;margin-top:9px;font:10px var(--mono);letter-spacing:.6px;color:var(--dim)"><span id="elapsed">SESSION 00:00:00</span><span id="levelText">INPUT LEVEL 0%</span></div>
  <div class="meter"><div id="meterFill"></div></div>
</section>

<section class="panel">
  <div class="section-head"><h3>Tonight at a glance</h3><span class="count" id="sessionBadge">NO SESSION</span></div>
  <div class="mini-stats">
    <div class="stat"><small>Sounds caught</small><strong id="eventCount">0</strong><span>saved moments</span></div>
    <div class="stat"><small>Listening</small><strong id="listenTime">0m</strong><span>this session</span></div>
    <div class="stat"><small>Peak level</small><strong id="peakLevel">0%</strong><span>input volume</span></div>
    <div class="stat"><small>Saved clips</small><strong id="clipCount">0</strong><span>on this device</span></div>
  </div>
</section>

<section class="panel">
  <div class="section-head"><h3>Detected moments</h3><span class="count" id="eventPill">0 EVENTS</span></div>
  <div class="events" id="events"><div class="empty"><div class="big">&#127769;</div>No sounds saved yet.<br>Start a session and SleepSnitch will watch for louder moments.</div></div>
</section>

<section class="panel">
  <div class="section-head"><h3>Night settings</h3><span class="count">CONFIG</span></div>
  <div class="settings">
    <div class="field"><label for="sensitivity">Detection sensitivity</label><select id="sensitivity"><option value="0.055">Low &middot; only louder sounds</option><option value="0.035" selected>Balanced &middot; recommended</option><option value="0.02">High &middot; more quiet sounds</option></select></div>
    <div class="field"><label for="cooldown">Minimum time between events</label><select id="cooldown"><option value="8">8 seconds</option><option value="15" selected>15 seconds</option><option value="30">30 seconds</option></select></div>
    <div class="field"><label for="clipLength">Clip length after a sound</label><select id="clipLength"><option value="4">4 seconds</option><option value="6" selected>6 seconds</option><option value="10">10 seconds</option></select></div>
    <div class="two">
      <div class="field"><label for="bedtime">Auto-start</label><input id="bedtime" type="time" value="22:30"></div>
      <div class="field"><label for="wake">Auto-stop</label><input id="wake" type="time" value="07:00"></div>
    </div>
    <div class="toggleline"><span>Enable schedule</span><input class="toggle" type="checkbox" id="schedule"></div>
    <div class="toggleline"><span>Keep screen on <span style="color:var(--dim)">(drains battery)</span></span><input class="toggle" type="checkbox" id="wakeLock"></div>
    <p class="note">Detection uses microphone volume changes, not an AI classifier. Label clips yourself. Audio never leaves this phone.</p>
    <button class="secondary" id="exportBtn">&#8595;&nbsp; Export event list (CSV)</button>
    <button class="secondary" id="clearBtn">Clear all saved clips</button>
  </div>
</section>

<div class="footer">SLEEPSNITCH &middot; PRIVATE BY DESIGN &middot; MIC ICON SHOWS WHILE LISTENING</div>
</div>
<div class="toast" id="toast"></div>
"""

head_end = src.index('<style>')
head = src[:head_end]
# theme colour -> new bg
head = head.replace('#090b16', '#08080a')

new_html = head + '<style>' + NEW_CSS + '</style>\n</head>\n' + NEW_BODY + script_block

# ------------------------------------------------------------------
# Mic fix (web side): never rely on a failed getUserMedia silently.
#  - ask native for permission first; wait for the answer
#  - give a precise error message
#  - expose a result callback the native side calls
# ------------------------------------------------------------------
old_start = "async function startSession(){if(sessionActive)return;try{stream=await navigator.mediaDevices.getUserMedia("
assert old_start in new_html, 'startSession anchor missing'
new_start = ("async function startSession(){if(sessionActive)return;"
  "if(window.SnitchBridge&&SnitchBridge.hasMic&&!SnitchBridge.hasMic()){"
  "showGate('Microphone permission is needed to listen.');try{SnitchBridge.askMic()}catch(e){}return}"
  "if(!navigator.mediaDevices||!navigator.mediaDevices.getUserMedia){"
  "showGate('This screen cannot reach the microphone. Update Android System WebView and reopen the app.');return}"
  "try{hideGate();stream=await navigator.mediaDevices.getUserMedia(")
new_html = new_html.replace(old_start, new_start, 1)

old_catch = "catch(e){toast(e.name==='NotAllowedError'?'Microphone permission was denied.':'Could not start microphone. Use HTTPS and check browser permissions.');console.error(e)}}"
assert old_catch in new_html, 'catch anchor missing'
new_catch = ("catch(e){console.error(e);"
  "if(e&&e.name==='NotAllowedError'){showGate('Microphone is blocked. Allow it in Android settings, then tap Start again.');}"
  "else if(e&&e.name==='NotFoundError'){toast('No microphone found on this device.')}"
  "else if(e&&e.name==='NotReadableError'){toast('Another app is using the microphone. Close it and retry.')}"
  "else{toast('Could not start microphone: '+((e&&e.name)||'unknown error'))}}}")
new_html = new_html.replace(old_catch, new_catch, 1)

helpers = r"""
function showGate(msg){var g=document.getElementById('micGate');if(!g)return;document.getElementById('micGateText').textContent=msg;g.classList.add('show')}
function hideGate(){var g=document.getElementById('micGate');if(g)g.classList.remove('show')}
document.addEventListener('click',function(ev){if(ev.target&&ev.target.id==='micGateBtn'){try{if(window.SnitchBridge&&SnitchBridge.askMic)SnitchBridge.askMic();else startSession()}catch(e){}}});
window.__snitchMicResult=function(granted){if(granted){hideGate();startSession()}else{showGate('Microphone is blocked. Open Android settings and allow it for SleepSnitch.')}};
"""
new_html = new_html.replace("<script>", "<script>" + helpers, 1)

# default: keep-screen-on stays OFF
new_html = new_html.replace('id="wakeLock" checked', 'id="wakeLock"')

open(p, 'w', encoding='utf-8').write(new_html)

# ---------------- verification ----------------
need = ['startBtn', 'stopBtn', 'status', 'wave', 'elapsed', 'levelText', 'meterFill', 'sessionBadge', 'eventCount',
        'listenTime', 'peakLevel', 'clipCount', 'events', 'eventPill', 'sensitivity', 'cooldown', 'clipLength',
        'bedtime', 'wake', 'schedule', 'wakeLock', 'exportBtn', 'clearBtn', 'toast', 'micGate']
missing = [i for i in need if ('id="%s"' % i) not in new_html]
print('missing ids:', missing)
assert not missing
ids_in_js = set(re.findall(r"\$\('([A-Za-z]+)'\)", new_html))
absent = [i for i in sorted(ids_in_js) if ('id="%s"' % i) not in new_html]
print('JS-referenced ids not in markup:', absent)
assert not absent
print('redesign OK', len(new_html))
