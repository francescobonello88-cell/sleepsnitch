package mt.sleepsnitch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private static final int REQ_MIC = 41;
    private WebView webView;
    private static volatile boolean askedBattery = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        webView.setBackgroundColor(0xFF08080A);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new Bridge(), "SnitchNative");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
        if (!hasMic()) webView.postDelayed(new Runnable() {
            public void run() { askMic(); }
        }, 600);
    }

    private boolean hasMic() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askMic() {
        String[] perms = Build.VERSION.SDK_INT >= 33
                ? new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS}
                : new String[]{Manifest.permission.RECORD_AUDIO};
        requestPermissions(perms, REQ_MIC);
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] res) {
        super.onRequestPermissionsResult(req, p, res);
        if (req == REQ_MIC) {
            boolean granted = hasMic();
            String js = "window.__snitchPerm && window.__snitchPerm(" + (granted ? "true" : "false") + ")";
            if (webView != null) webView.evaluateJavascript(js, null);
            if (!granted) Toast.makeText(this, "Microphone permission is needed to record.", Toast.LENGTH_LONG).show();
        }
    }

    private void maybeBattery() {
        if (askedBattery) return;
        askedBattery = true;
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        } catch (Throwable ignored) { }
    }

    private class Bridge {
        @JavascriptInterface public boolean hasMic() { return MainActivity.this.hasMic(); }

        @JavascriptInterface public void askMic() {
            runOnUiThread(new Runnable() { public void run() { MainActivity.this.askMic(); } });
        }

        @JavascriptInterface public void start(int sens, int clipMs, long stopAt) {
            final int fSens = sens, fClip = clipMs;
            final long fStop = stopAt;
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(MainActivity.this, SleepRecordService.class);
                    i.setAction(SleepRecordService.ACTION_START);
                    i.putExtra(SleepRecordService.EXTRA_SENS, fSens);
                    i.putExtra(SleepRecordService.EXTRA_CLIP, fClip);
                    i.putExtra(SleepRecordService.EXTRA_STOP_AT, fStop);
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
                    maybeBattery();
                }
            });
        }

        @JavascriptInterface public void stop() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(MainActivity.this, SleepRecordService.class);
                    i.setAction(SleepRecordService.ACTION_STOP);
                    startService(i);
                }
            });
        }

        @JavascriptInterface public String state() {
            SleepRecordService svc = SleepRecordService.instance;
            return svc != null ? svc.stateJson() : "{\"recording\":false,\"events\":[]}";
        }

        @JavascriptInterface public String events() {
            return SleepRecordService.allEventsJson();
        }

        @JavascriptInterface public void play(int id) {
            SleepRecordService svc = SleepRecordService.instance;
            if (svc != null) svc.playClip(id);
        }

        @JavascriptInterface public void stopPlay() {
            SleepRecordService svc = SleepRecordService.instance;
            if (svc != null) svc.stopPlayback();
        }

        @JavascriptInterface public void delete(int id) {
            SleepRecordService svc = SleepRecordService.instance;
            if (svc != null) svc.deleteEvent(id);
        }

        @JavascriptInterface public void clearAll() {
            SleepRecordService svc = SleepRecordService.instance;
            if (svc != null) svc.clearAll();
        }

        @JavascriptInterface public void saveText(String name, String content) {
            final String n = name, c = content;
            runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        if (Build.VERSION.SDK_INT >= 29) {
                            ContentValues v = new ContentValues();
                            v.put(MediaStore.Downloads.DISPLAY_NAME, n);
                            v.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                            if (uri != null) {
                                OutputStream out = getContentResolver().openOutputStream(uri);
                                out.write(c.getBytes("UTF-8"));
                                out.close();
                                Toast.makeText(MainActivity.this, "Saved to Downloads/" + n, Toast.LENGTH_LONG).show();
                                return;
                            }
                        }
                        File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                        File f = new File(dir, n);
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(c.getBytes("UTF-8"));
                        fo.close();
                        Toast.makeText(MainActivity.this, "Saved to " + f.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    } catch (Throwable t) {
                        Toast.makeText(MainActivity.this, "Export failed: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
                    }
                }
            });
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (webView != null) { webView.onResume(); webView.resumeTimers(); }
    }

    @Override protected void onPause() {
        super.onPause();
        // keep the page running: recording lives in the service, polling resumes on return
        if (webView != null) webView.onPause();
    }
}
