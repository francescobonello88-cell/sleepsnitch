import os

BASE = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(BASE, '..', 'sleepsnitch.html'), encoding='utf-8').read()

fails = []

def rep(old, new, count=1):
    global src
    n = src.count(old)
    if n != count:
        fails.append(f'ANCHOR FAIL ({n}!={count}): {old[:80]!r}')
        return
    src = src.replace(old, new)

# 1) default: do NOT keep the screen awake (native wake lock handles the CPU instead)
rep('<input class="toggle" type="checkbox" id="wakeLock" checked>',
    '<input class="toggle" type="checkbox" id="wakeLock">')
rep('<span>Keep screen awake during session</span>',
    '<span>Keep screen on (drains battery)</span>')

# 2) start session -> native foreground service + honest toast text
rep("toast('SleepSnitch is listening. Keep this tab open.')",
    "try{if(window.SnitchBridge&&SnitchBridge.startSession)SnitchBridge.startSession();}catch(e){}toast('SleepSnitch is listening in the background \\u2014 lock the screen and sleep.')")

# 3) stop session -> native service stop
rep("toast('Session stopped. Your clips are saved locally.')",
    "try{if(window.SnitchBridge&&SnitchBridge.stopSession)SnitchBridge.stopSession();}catch(e){}toast('Session stopped. Your clips are saved locally.')")

# 4) CSV export -> native save (WebView cannot download blob URLs)
rep("const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv'}));a.download='sleepsnitch-events.csv';a.click();URL.revokeObjectURL(a.href)",
    "if(window.SnitchBridge&&SnitchBridge.saveText){SnitchBridge.saveText('sleepsnitch-events.csv',csv)}else{const a=document.createElement('a');a.href=URL.createObjectURL(new Blob([csv],{type:'text/csv'}));a.download='sleepsnitch-events.csv';a.click();URL.revokeObjectURL(a.href)}")

# 5) schedule toast no longer needs the page kept open
rep("toast('Schedule armed. Keep this page open and allow microphone access when prompted.')",
    "toast('Schedule armed. Allow microphone access when prompted.')")

if fails:
    print('FAILURES:')
    for f in fails:
        print(' -', f)
    raise SystemExit(1)

out = os.path.join(BASE, 'app', 'src', 'main', 'assets')
os.makedirs(out, exist_ok=True)
open(os.path.join(out, 'index.html'), 'w', encoding='utf-8').write(src)
print('asset OK:', len(src))
print('bridge hooks:', src.count('SnitchBridge'))
print('wakeLock default off:', 'id="wakeLock">' in src)
