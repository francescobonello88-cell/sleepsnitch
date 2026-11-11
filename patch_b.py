p = 'app/src/main/java/mt/sleepsnitch/SleepRecordService.java'
s = open(p, encoding='utf-8').read()

# 1) adaptive level instead of fixed gain
old = """            float rms = (float) (Math.sqrt(sum / n) / 32768.0);
            float lvl = Math.min(1f, rms * 6f);
            level = level * 0.6f + lvl * 0.4f;
            if (level > peak) peak = level;"""
new = """            float rms = (float) (Math.sqrt(sum / n) / 32768.0);
            rmsRaw = rms;
            blocksSeen++;
            if (rms > 0.0006f) lastAudioAt = System.currentTimeMillis();
            // adaptive noise floor: falls quickly, rises slowly
            if (rms < floor) floor = floor * 0.97f + rms * 0.03f;
            else floor = floor * 0.998f + rms * 0.002f;
            if (floor < 0.0008f) floor = 0.0008f;
            float span = Math.max(0.02f, floor * 9f);
            float lvl = Math.min(1f, rms / span);
            level = level * 0.55f + lvl * 0.45f;
            if (level > peak) peak = level;"""
assert old in s
s = s.replace(old, new, 1)

# 2) watchdog: reopen capture if the stream goes silent
old = """            long now = System.currentTimeMillis();
            if (stopAtMs > 0 && now >= stopAtMs) {"""
new = """            long now = System.currentTimeMillis();
            if (running && now - lastAudioAt > 6000 && reopens < 6) {
                reopenCapture();
                lastAudioAt = now;
            }
            if (stopAtMs > 0 && now >= stopAtMs) {"""
assert old in s
s = s.replace(old, new, 1)

# 3) adaptive trigger instead of fixed threshold
old = """            } else if (level >= threshold() && now - lastEventAt > COOLDOWN_MS) {"""
new = """            } else if (isLoud(now)) {"""
assert old in s
s = s.replace(old, new, 1)

# 4) replace threshold() with adaptive helpers + reopenCapture
old = """    private float threshold() {
        float s = Math.max(0, Math.min(100, sensitivity)) / 100f;
        return 0.17f - 0.13f * s;
    }"""
new = """    /** How far above the measured noise floor counts as a real sound. */
    private float factor() {
        float s = Math.max(0, Math.min(100, sensitivity)) / 100f;
        return 6.5f - 5.0f * s;   // sensitivity 0 -> 6.5x, 100 -> 1.5x above floor
    }

    private float triggerLevel() {
        return Math.max(0.0035f, floor * factor());
    }

    private boolean isLoud(long now) {
        if (now - lastEventAt <= COOLDOWN_MS) return false;
        return rmsRaw >= triggerLevel();
    }

    private void reopenCapture() {
        reopens++;
        try { record.stop(); } catch (Throwable ignored) { }
        try { record.release(); } catch (Throwable ignored) { }
        source = (source == MediaRecorder.AudioSource.MIC)
                ? MediaRecorder.AudioSource.VOICE_RECOGNITION
                : MediaRecorder.AudioSource.MIC;
        try {
            record = buildRecord(source);
            record.startRecording();
            Log.i("SleepSnitch", "capture reopened, source=" + source);
        } catch (Throwable t) {
            Log.w("SleepSnitch", "reopen failed", t);
        }
    }"""
assert old in s
s = s.replace(old, new, 1)

# 5) expose diagnostics in the state JSON
old = """            o.put("recording", running);
            o.put("level", (double) level);
            o.put("peak", (double) peak);"""
new = """            o.put("recording", running);
            o.put("level", (double) level);
            o.put("peak", (double) peak);
            o.put("rms", (double) rmsRaw);
            o.put("floor", (double) floor);
            o.put("thr", (double) triggerLevel());
            o.put("reopens", reopens);
            o.put("blocks", blocksSeen);
            o.put("source", source);"""
assert old in s
s = s.replace(old, new, 1)

open(p, 'w', encoding='utf-8').write(s)
print('service part B written')
