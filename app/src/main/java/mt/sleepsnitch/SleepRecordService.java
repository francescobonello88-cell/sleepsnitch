package mt.sleepsnitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Recorder built on MediaRecorder + getMaxAmplitude for maximum device compatibility. */
public class SleepRecordService extends Service {
    public static final String ACTION_START = "mt.sleepsnitch.START";
    public static final String ACTION_STOP = "mt.sleepsnitch.STOP";
    public static final String EXTRA_SENS = "sens";
    public static final String EXTRA_CLIP = "clip";
    public static final String EXTRA_STOP_AT = "stopAt";
    private static final String CH = "sleepsnitch";
    private static final int COOLDOWN_MS = 6000;

    public static volatile SleepRecordService instance;

    private volatile boolean running = false;
    private volatile String error = null;
    private volatile int sensitivity = 60;
    private volatile int clipMs = 6000;
    private volatile long stopAtMs = 0;
    private volatile float level = 0f;
    private volatile float peak = 0f;
    private volatile float amp01 = 0f;
    private volatile float floor = 0.02f;
    private volatile long blocks = 0;
    private volatile int restarts = 0;
    private volatile boolean everHeard = false;
    private volatile boolean silenceWarned = false;
    private long startedAt = 0;
    private long lastEventAt = 0;
    private long lastSoundAt = 0;
    private MediaRecorder rec;
    private int sourceIdx = 0;
    private File curFile;
    private int segRawMax = 0;
    private float segPeak = 0f;
    private volatile int segLenMs = 10000;
    private volatile long lastSegStart = 0;
    private Thread poller;
    private PowerManager.WakeLock wake;
    private MediaPlayer player;
    private final Object lock = new Object();
    private final List<JSONObject> all = new ArrayList<>();
    private final List<JSONObject> fresh = new ArrayList<>();
    private int nextId = 1;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        loadEvents();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopEverything();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (intent != null) {
            sensitivity = intent.getIntExtra(EXTRA_SENS, sensitivity);
            clipMs = intent.getIntExtra(EXTRA_CLIP, clipMs);
            stopAtMs = intent.getLongExtra(EXTRA_STOP_AT, 0);
        }
        startForegroundNow();
        acquireWake();
        if (!running) startRecording();
        return START_STICKY;
    }

    private void startForegroundNow() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CH, "Sleep recording", NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH) : new Notification.Builder(this);
        Notification n = b.setContentTitle("SleepSnitch is listening")
                .setContentText("Recording with the screen off — tap to open")
                .setSmallIcon(R.drawable.ic_moon)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } else {
                startForeground(1, n);
            }
        } catch (Throwable t) {
            try { startForeground(1, n); } catch (Throwable ignored) { }
        }
    }

    private void acquireWake() {
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepSnitch:Rec");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    @Override public void onDestroy() {
        stopEverything();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    // ---------- capture ----------
    private void startRecording() {
        error = null;
        startedAt = System.currentTimeMillis();
        lastSoundAt = startedAt;
        peak = 0f; level = 0f; amp01 = 0f; blocks = 0; restarts = 0;
        everHeard = false; silenceWarned = false;
        segLenMs = Math.max(4000, Math.min(15000, clipMs));
        running = true;
        startSegment();
        if (!running) return;
        poller = new Thread(new Runnable() { public void run() { pollLoop(); } }, "snitch-poll");
        poller.start();
    }

    private void startSegment() {
        try {
            File dir = new File(getFilesDir(), "events");
            if (!dir.exists()) dir.mkdirs();
            curFile = new File(dir, "clip_" + System.currentTimeMillis() + ".m4a");
            int src = sourceIdx == 0 ? MediaRecorder.AudioSource.MIC
                                     : MediaRecorder.AudioSource.VOICE_RECOGNITION;
            rec = new MediaRecorder();
            rec.setAudioSource(src);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setAudioSamplingRate(44100);
            rec.setAudioEncodingBitRate(64000);
            rec.setOutputFile(curFile.getAbsolutePath());
            rec.prepare();
            rec.start();
            segRawMax = 0;
            segPeak = 0f;
            lastSegStart = System.currentTimeMillis();
        } catch (SecurityException se) {
            error = "Microphone permission was denied. Allow it and tap Start again.";
            running = false;
            cleanupRecorder();
        } catch (Throwable t) {
            error = "Recorder error: " + t.getClass().getSimpleName()
                    + (t.getMessage() != null ? " (" + t.getMessage() + ")" : "");
            running = false;
            cleanupRecorder();
            Log.w("SleepSnitch", "startSegment failed", t);
        }
    }

    private void cleanupRecorder() {
        if (rec == null) return;
        try { rec.stop(); } catch (Throwable ignored) { }
        try { rec.release(); } catch (Throwable ignored) { }
        rec = null;
    }

    private void switchSource() {
        sourceIdx = sourceIdx == 0 ? 1 : 0;
        boolean wasRunning = running;
        cleanupQuiet();
        if (wasRunning) startSegment();
        Log.i("SleepSnitch", "switched audio source to " + sourceIdx);
    }

    private void cleanupQuiet() {
        if (rec == null) return;
        try { rec.stop(); } catch (Throwable ignored) { }
        try { rec.release(); } catch (Throwable ignored) { }
        rec = null;
    }

    private float factor() {
        float s = Math.max(0, Math.min(100, sensitivity)) / 100f;
        return 6.5f - 5f * s;
    }

    private float trigger01() {
        return Math.max(0.02f, floor * factor());
    }

    private int triggerRaw() {
        return Math.max(500, Math.round(trigger01() * 12000f));
    }

    private void pollLoop() {
        while (running) {
            long now = System.currentTimeMillis();
            int raw = 0;
            try { if (rec != null) raw = rec.getMaxAmplitude(); } catch (Throwable ignored) { }

            if (raw > 0) { lastSoundAt = now; everHeard = true; }
            if (raw > segRawMax) segRawMax = raw;

            amp01 = Math.min(1f, raw / 12000f);
            if (amp01 < floor) floor = floor * 0.95f + amp01 * 0.05f;
            else floor = floor * 0.995f + amp01 * 0.005f;
            if (floor < 0.004f) floor = 0.004f;

            float span = Math.max(0.05f, floor * 8f);
            level = level * 0.45f + Math.min(1f, amp01 / span) * 0.55f;
            if (level > peak) peak = level;
            if (amp01 > segPeak) segPeak = amp01;

            if (now >= segEnd(now)) rotateSegment(now);

            if (stopAtMs > 0 && now >= stopAtMs) {
                stopEverything();
                stopSelf();
                return;
            }

            if (running && !everHeard && now - lastSoundAt > 6000 && restarts < 6) {
                restarts++;
                lastSoundAt = now;
                switchSource();
            }

            if (!everHeard && !silenceWarned && now - startedAt > 20000) {
                silenceWarned = true;
                error = "The microphone is returning pure silence. Check Samsung's microphone " +
                        "privacy toggle, close any app using the mic, then tap Start again.";
            }
            try { Thread.sleep(120); } catch (InterruptedException e) { break; }
        }
    }

    private long segEnd(long now) {
        return lastSegStart + segLenMs;
    }

    private void rotateSegment(long now) {
        File f = curFile;
        boolean loud = segRawMax >= triggerRaw();
        float lvl = segPeak;
        cleanupQuiet();
        if (f != null && f.exists()) {
            if (loud && (lastEventAt == 0 || now - lastEventAt > COOLDOWN_MS)) {
                lastEventAt = now;
                addEvent(f, lvl, segLenMs);
            } else {
                f.delete();
            }
        }
        blocks++;
        segRawMax = 0;
        segPeak = 0f;
        if (running) startSegment();
    }

    // ---------- events ----------
    private void addEvent(File f, float lvl, int durMs) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", nextId++);
            o.put("t", System.currentTimeMillis());
            o.put("level", (double) lvl);
            o.put("dur", durMs);
            o.put("file", f.getName());
            synchronized (lock) {
                all.add(o);
                fresh.add(o);
            }
            saveEvents();
        } catch (Throwable ignored) { }
    }

    private void loadEvents() {
        try {
            SharedPreferences sp = getSharedPreferences("snitch", MODE_PRIVATE);
            JSONArray arr = new JSONArray(sp.getString("events", "[]"));
            synchronized (lock) {
                all.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    all.add(o);
                    if (o.optInt("id", 0) >= nextId) nextId = o.optInt("id", 0) + 1;
                }
            }
        } catch (Throwable ignored) { }
    }

    private void saveEvents() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (lock) { for (JSONObject o : all) arr.put(o); }
            getSharedPreferences("snitch", MODE_PRIVATE).edit().putString("events", arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    public static String allEventsJson() {
        SleepRecordService s = instance;
        if (s == null) return "[]";
        try {
            JSONArray arr = new JSONArray();
            synchronized (s.lock) { for (JSONObject o : s.all) arr.put(o); }
            return arr.toString();
        } catch (Throwable t) { return "[]"; }
    }

    public String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("recording", running);
            o.put("level", (double) level);
            o.put("peak", (double) peak);
            o.put("elapsedMs", running && startedAt > 0 ? System.currentTimeMillis() - startedAt : 0);
            o.put("rms", (double) amp01);
            o.put("floor", (double) floor);
            o.put("thr", (double) trigger01());
            o.put("blocks", blocks);
            o.put("reopens", restarts);
            if (error != null) o.put("error", error);
            JSONArray arr = new JSONArray();
            synchronized (lock) {
                for (JSONObject f : fresh) arr.put(f);
                fresh.clear();
            }
            o.put("events", arr);
            return o.toString();
        } catch (Throwable t) {
            return "{\"recording\":false,\"events\":[]}";
        }
    }

    // ---------- playback and deletion ----------
    private File fileFor(int id) {
        String name = null;
        synchronized (lock) {
            for (JSONObject o : all) {
                if (o.optInt("id", 0) == id) { name = o.optString("file", ""); break; }
            }
        }
        if (name == null || name.isEmpty()) return null;
        File f = new File(new File(getFilesDir(), "events"), name);
        return f.exists() ? f : null;
    }

    public void playClip(int id) {
        stopPlayback();
        File f = fileFor(id);
        if (f == null) return;
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.prepare();
            player.start();
        } catch (Throwable t) {
            Log.w("SleepSnitch", "play failed", t);
        }
    }

    public void stopPlayback() {
        if (player == null) return;
        try { if (player.isPlaying()) player.stop(); } catch (Throwable ignored) { }
        try { player.release(); } catch (Throwable ignored) { }
        player = null;
    }

    public void deleteEvent(int id) {
        File f = fileFor(id);
        if (f != null) f.delete();
        synchronized (lock) {
            for (int i = 0; i < all.size(); i++) {
                if (all.get(i).optInt("id", 0) == id) { all.remove(i); break; }
            }
        }
        saveEvents();
    }

    public void clearAll() {
        stopPlayback();
        synchronized (lock) { all.clear(); fresh.clear(); }
        File dir = new File(getFilesDir(), "events");
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        saveEvents();
    }

    private void stopEverything() {
        running = false;
        if (poller != null) { poller.interrupt(); poller = null; }
        cleanupRecorder();
        stopPlayback();
        if (wake != null && wake.isHeld()) { try { wake.release(); } catch (Throwable ignored) { } }
        try { stopForeground(true); } catch (Throwable ignored) { }
        blocks++;
    }
}
