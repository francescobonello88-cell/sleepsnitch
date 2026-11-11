import sys

P = 'app/src/main/assets/index.html'
src = open(P, encoding='utf-8').read()
fails = []


def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        fails.append('ANCHOR FAIL (%d!=%d): %r' % (n, count, old[:80]))
        return
    src = src.replace(old, new)


# 1. gate supports a retry mode with a proper button label
rep("function showGate(msg){var g=document.getElementById('micGate');if(!g)return;document.getElementById('micGateText').textContent=msg;g.classList.add('show')}",
    "function showGate(msg,retry){var g=document.getElementById('micGate');if(!g)return;document.getElementById('micGateText').textContent=msg;"
    "var b=document.getElementById('micGateBtn');if(b)b.textContent=retry?'Try again':'Allow microphone';"
    "if(retry)g.setAttribute('data-retry','1');else g.removeAttribute('data-retry');g.classList.add('show')}")

# 2. gate button retries the session when the failure was a busy mic
rep("document.addEventListener('click',function(ev){if(ev.target&&ev.target.id==='micGateBtn'){try{if(window.SnitchBridge&&SnitchBridge.askMic)SnitchBridge.askMic();else startSession()}catch(e){}}});",
    "document.addEventListener('click',function(ev){if(!(ev.target&&ev.target.id==='micGateBtn'))return;"
    "var g=document.getElementById('micGate');if(g&&g.getAttribute('data-retry')==='1'){startSession();return}"
    "try{if(window.SnitchBridge&&SnitchBridge.askMic)SnitchBridge.askMic();else startSession()}catch(e){}});")

# 3. mic opener with retries (transient busy states clear themselves)
rep("async function startSession(){",
    "async function openMic(){var lastErr=null;"
    "for(var i=0;i<3;i++){"
    "try{return await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:false,noiseSuppression:false,autoGainControl:false},video:false})}"
    "catch(err){lastErr=err;"
    "if(err&&(err.name==='NotAllowedError'||err.name==='SecurityError'))break;"
    "await new Promise(function(r){setTimeout(r,600*(i+1))})}}"
    "throw lastErr}\n"
    "async function startSession(){")

# 4. capture FIRST, then start the native mic service (starting it first makes
#    the platform report the mic as taken by another app on many devices)
rep("try{hideGate();try{if(window.SnitchBridge&&SnitchBridge.startSession)SnitchBridge.startSession();}catch(e){}"
    "await new Promise(function(r){setTimeout(r,250)});"
    "stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:false,noiseSuppression:false,autoGainControl:false},video:false});",
    "try{hideGate();"
    "try{if(window.SnitchBridge&&SnitchBridge.stopSession)SnitchBridge.stopSession();}catch(e){}"
    "await new Promise(function(r){setTimeout(r,150)});"
    "stream=await openMic();"
    "try{if(window.SnitchBridge&&SnitchBridge.startSession)SnitchBridge.startSession();}catch(e){}")

# 5. clear, actionable errors instead of a disappearing toast
rep("if(e&&e.name==='NotAllowedError'){showGate('Microphone is blocked. Allow it in Android settings, then tap Start again.');}"
    "else if(e&&e.name==='NotFoundError'){toast('No microphone found on this device.')}"
    "else if(e&&e.name==='NotReadableError'){toast('Another app is using the microphone. Close it and retry.')}"
    "else{toast('Could not start microphone: '+((e&&e.name)||'unknown error'))}",
    "if(e&&e.name==='NotAllowedError'){showGate('Microphone is blocked. Allow it in Android settings, then tap Start again.')}"
    "else if(e&&e.name==='NotFoundError'){showGate('No microphone found on this device.')}"
    "else if(e&&e.name==='NotReadableError'||e&&e.name==='AbortError'){"
    "showGate('The microphone is busy. A call, voice assistant or another recorder is holding it. Close that app, then tap Try again.',true)}"
    "else{showGate('Could not start the microphone ('+((e&&e.name)||'unknown error')+'). Tap Try again.',true)}")

if fails:
    print('FAILURES:')
    for f in fails:
        print(' -', f)
    sys.exit(1)

open(P, 'w', encoding='utf-8').write(src)
print('mic patch OK')
for k in ['openMic', '__snitchNotify', 'data-retry', 'stopSession();}catch(e){}await new Promise(function(r){setTimeout(r,150)})']:
    print('  contains %-24s %s' % (k, k in src))
print('  startSession bridge calls:', src.count('SnitchBridge.startSession()'))
