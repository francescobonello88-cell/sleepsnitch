package mt.sleepsnitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Records with a single native AudioRecord client (no WebView mic at all). */
public class SleepRecordService extends Service {

    public static final String ACTION_START = "mt.sleepsnitch.START";
    public static final String ACTION_STOP = "mt.sleepsnitch.STOP";
    public static final String EXTRA_SENS = "sens";
    public static final String EXTRA_CLIP = "clip";
    public static final String EXTRA_STOP_AT = "stopAt";
    private static final String CH = "sleepsnitch";
    private static final int RATE = 16000;
    private static final int PREROLL_SEC = 3;
    private static final int COOLDOWN_MS = 4000;

    public static volatile SleepRecordService instance;

    private volatile boolean running = false;
    private volatile String error = null;
    private AudioRecord record;
    private Thread loop;
    private PowerManager.WakeLock wake;
    private MediaPlayer player;

    private final ArrayDeque<short[]> ring = new ArrayDeque<>();
    private int ringSamples = 0;

    private volatile float level = 0f;
    private volatile float peak = 0f;
    private volatile long startedAt = 0;
    private volatile int sensitivity = 60;
    private volatile int clipMs = 6000;
    private volatile long stopAtMs = 0;
    private volatile long lastEventAt = 0;
    private volatile boolean capturing = false;
    private volatile long capEndsAt = 0;
    private volatile float capPeak = 0f;
    private final List<short[]> capBlocks = new ArrayList<>();

    private final List<JSONObject> events = new ArrayList<>();
    private final List<JSONObject> fresh = new ArrayList<>();
    private volatile float rmsRaw = 0f;
    private volatile float floor = 0.0025f;
    private volatile long lastAudioAt = 0;
    private volatile int reopens = 0;
    private volatile long blocksSeen = 0;
    private volatile int source = MediaRecorder.AudioSource.MIC;
    private int nextId = 1;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        loadEvents();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopRecording();
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
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder nb = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CH)
                : new Notification.Builder(this);
        Notification n = nb.setContentTitle("SleepSnitch is listening")
                .setContentText("Night recording active - screen can stay off")
                .setSmallIcon(R.drawable.ic_moon)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
                return;
            } catch (Exception e) {
                Log.w("SleepSnitch", "fg type mic failed", e);
            }
        }
        startForeground(1, n);
    }

    private void acquireWake() {
        if (wake != null && wake.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepSnitch:Rec");
        wake.setReferenceCounted(false);
        wake.acquire();
    }

    private AudioRecord buildRecord(int src) {
        int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufBytes = Math.max(min, RATE / 4);
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        return new AudioRecord.Builder()
                .setAudioSource(src)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(bufBytes * 2)
                .build();
    }

    private void startRecording() {
        error = null;
        try {
            source = MediaRecorder.AudioSource.MIC;
            record = buildRecord(source);
            if (record == null || record.getState() != AudioRecord.STATE_INITIALIZED) {
                error = "The microphone could not be opened. Close any call, voice assistant or recorder and tap Start again.";
                return;
            }
            running = true;
            startedAt = System.currentTimeMillis();
            record.startRecording();
            loop = new Thread(new Runnable() { public void run() { readLoop(); } }, "snitch-audio");
            loop.start();
        } catch (SecurityException se) {
            error = "Microphone permission was denied. Allow the microphone and try again.";
        } catch (Throwable t) {
            error = "Microphone unavailable: " + t.getClass().getSimpleName();
            Log.w("SleepSnitch", "startRecording failed", t);
        }
    }

    /** How far above the measured noise floor counts as a real sound. */
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
    }

    private void readLoop() {
        short[] buf = new short[2048];
        int ringLimit = RATE * PREROLL_SEC;
        while (running) {
            int n;
            try {
                n = record.read(buf, 0, buf.length);
            } catch (Throwable t) {
                error = "Recording stopped: " + t.getClass().getSimpleName();
                break;
            }
            if (n <= 0) {
                if (n == AudioRecord.ERROR_INVALID_OPERATION || n == AudioRecord.ERROR_BAD_VALUE) break;
                continue;
            }
            short[] block = new short[n];
            System.arraycopy(buf, 0, block, 0, n);

            double sum = 0;
            for (int i = 0; i < n; i++) {
                double v = block[i];
                sum += v * v;
            }
            float rms = (float) (Math.sqrt(sum / n) / 32768.0);
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
            if (level > peak) peak = level;

            synchronized (ring) {
                ring.addLast(block);
                ringSamples += n;
                while (ringSamples - ring.peekFirst().length >= ringLimit && ring.size() > 1) {
                    ringSamples -= ring.removeFirst().length;
                }
            }

            long now = System.currentTimeMillis();
            if (running && now - lastAudioAt > 6000 && reopens < 6) {
                reopenCapture();
                lastAudioAt = now;
            }
            if (stopAtMs > 0 && now >= stopAtMs) {
                stopRecording();
                stopSelf();
                return;
            }

            if (capturing) {
                capBlocks.add(block);
                if (level > capPeak) capPeak = level;
                if (now >= capEndsAt) finishCapture(now);
            } else if (isLoud(now)) {
                lastEventAt = now;
                capturing = true;
                capPeak = level;
                capEndsAt = now + clipMs;
                synchronized (capBlocks) {
                    capBlocks.clear();
                    synchronized (ring) { capBlocks.addAll(ring); }
                }
            }
            try { Thread.sleep(4); } catch (InterruptedException ignored) { }
        }
    }

    private void finishCapture(long now) {
        List<short[]> copy;
        synchronized (capBlocks) {
            copy = new ArrayList<>(capBlocks);
            capBlocks.clear();
        }
        capturing = false;
        try {
            File f = writeWav(copy);
            JSONObject o = new JSONObject();
            int id = nextId++;
            o.put("id", id);
            o.put("t", now);
            o.put("level", (double) capPeak);
            o.put("dur", clipMs);
            o.put("file", f.getName());
            synchronized (events) {
                events.add(o);
                fresh.add(o);
                saveEvents();
            }
        } catch (Throwable t) {
            Log.w("SleepSnitch", "write clip failed", t);
        }
    }

    private File writeWav(List<short[]> blocks) throws Exception {
        File dir = new File(getFilesDir(), "events");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, "clip_" + System.currentTimeMillis() + ".wav");
        int samples = 0;
        for (short[] b : blocks) samples += b.length;
        byte[] data = new byte[samples * 2];
        int p = 0;
        for (short[] b : blocks) {
            for (short s : b) {
                data[p++] = (byte) (s & 0xff);
                data[p++] = (byte) ((s >> 8) & 0xff);
            }
        }
        FileOutputStream out = new FileOutputStream(f);
        out.write(wavHeader(samples * 2));
        out.write(data);
        out.flush();
        out.close();
        return f;
    }

    private byte[] wavHeader(int dataLen) {
        int rate = RATE;
        byte[] h = new byte[44];
        int fileLen = dataLen + 36;
        h[0]='R'; h[1]='I'; h[2]='F'; h[3]='F';
        h[4]=(byte)(fileLen & 0xff); h[5]=(byte)((fileLen>>8)&0xff); h[6]=(byte)((fileLen>>16)&0xff); h[7]=(byte)((fileLen>>24)&0xff);
        h[8]='W'; h[9]='A'; h[10]='V'; h[11]='E';
        h[12]='f'; h[13]='m'; h[14]='t'; h[15]=' ';
        h[16]=16; h[17]=0; h[18]=0; h[19]=0;
        h[20]=1; h[21]=0;
        h[22]=1; h[23]=0;
        h[24]=(byte)(rate & 0xff); h[25]=(byte)((rate>>8)&0xff); h[26]=(byte)((rate>>16)&0xff); h[27]=(byte)((rate>>24)&0xff);
        int byteRate = rate * 2;
        h[28]=(byte)(byteRate & 0xff); h[29]=(byte)((byteRate>>8)&0xff); h[30]=(byte)((byteRate>>16)&0xff); h[31]=(byte)((byteRate>>24)&0xff);
        h[32]=2; h[33]=0;
        h[34]=16; h[35]=0;
        h[36]='d'; h[37]='a'; h[38]='t'; h[39]='a';
        h[40]=(byte)(dataLen & 0xff); h[41]=(byte)((dataLen>>8)&0xff); h[42]=(byte)((dataLen>>16)&0xff); h[43]=(byte)((dataLen>>24)&0xff);
        return h;
    }

    // ---------- events storage ----------
    private void loadEvents() {
        try {
            SharedPreferences sp = getSharedPreferences("snitch", MODE_PRIVATE);
            String raw = sp.getString("events", "[]");
            JSONArray arr = new JSONArray(raw);
            synchronized (events) {
                events.clear();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    events.add(o);
                    if (o.optInt("id", 0) >= nextId) nextId = o.optInt("id", 0) + 1;
                }
            }
        } catch (Throwable ignored) { }
    }

    private void saveEvents() {
        try {
            JSONArray arr = new JSONArray();
            synchronized (events) {
                for (JSONObject o : events) arr.put(o);
            }
            getSharedPreferences("snitch", MODE_PRIVATE).edit().putString("events", arr.toString()).apply();
        } catch (Throwable ignored) { }
    }

    // ---------- called from the page ----------
    public String stateJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("recording", running);
            o.put("level", (double) level);
            o.put("peak", (double) peak);
            o.put("rms", (double) rmsRaw);
            o.put("floor", (double) floor);
            o.put("thr", (double) triggerLevel());
            o.put("reopens", reopens);
            o.put("blocks", blocksSeen);
            o.put("source", source);
            o.put("elapsedMs", startedAt > 0 ? System.currentTimeMillis() - startedAt : 0);
            if (error != null) o.put("error", error);
            JSONArray arr = new JSONArray();
            synchronized (events) {
                for (JSONObject e : fresh) arr.put(e);
                fresh.clear();
            }
            o.put("events", arr);
            return o.toString();
        } catch (Throwable t) {
            return "{\"recording\":false}";
        }
    }

    public static String allEventsJson() {
        try {
            JSONArray arr = new JSONArray();
            SleepRecordService s = instance;
            if (s != null) {
                synchronized (s.events) {
                    for (JSONObject o : s.events) arr.put(o);
                }
            } else {
                return "[]";
            }
            return arr.toString();
        } catch (Throwable t) {
            return "[]";
        }
    }

    public void playClip(int id) {
        stopPlayback();
        String fileName = null;
        synchronized (events) {
            for (JSONObject o : events) {
                if (o.optInt("id", 0) == id) { fileName = o.optString("file", ""); break; }
            }
        }
        if (fileName == null || fileName.isEmpty()) return;
        File f = new File(new File(getFilesDir(), "events"), fileName);
        if (!f.exists()) return;
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
        if (player != null) {
            try { player.stop(); } catch (Throwable ignored) { }
            try { player.release(); } catch (Throwable ignored) { }
            player = null;
        }
    }

    public void deleteEvent(int id) {
        String fileName = null;
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                JSONObject o = events.get(i);
                if (o.optInt("id", -1) == id) {
                    fileName = o.optString("file", "");
                    events.remove(i);
                    break;
                }
            }
            saveEvents();
        }
        if (fileName != null && !fileName.isEmpty()) {
            File f = new File(new File(getFilesDir(), "events"), fileName);
            if (f.exists()) f.delete();
        }
    }

    public void clearAll() {
        synchronized (events) {
            events.clear();
            fresh.clear();
            saveEvents();
        }
        File dir = new File(getFilesDir(), "events");
        File[] all = dir.listFiles();
        if (all != null) for (File f : all) f.delete();
    }

    private void stopRecording() {
        running = false;
        try { if (loop != null) loop.interrupt(); } catch (Throwable ignored) { }
        try {
            if (record != null) {
                record.stop();
                record.release();
            }
        } catch (Throwable ignored) { }
        record = null;
        try { stopForeground(true); } catch (Throwable ignored) { }
        if (wake != null && wake.isHeld()) wake.release();
        startedAt = 0;
        level = 0f;
        capturing = false;
        synchronized (capBlocks) { capBlocks.clear(); }
        synchronized (ring) { ring.clear(); ringSamples = 0; }
    }

    @Override public void onDestroy() {
        stopRecording();
        stopPlayback();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
