package mt.sleepsnitch;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.provider.Settings;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_AUDIO = 21;
    private WebView webView;
    private PermissionRequest pendingAudio;
    private boolean batteryPrompted = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(true);
        webView.setBackgroundColor(0xFF090B16);
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                boolean wantsAudio = false;
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsAudio = true;
                }
                if (!wantsAudio) {
                    request.deny();
                    return;
                }
                if (hasAudio()) {
                    request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                } else {
                    pendingAudio = request;
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
                }
            }
        });
        webView.addJavascriptInterface(new Bridge(), "SnitchBridge");
        webView.loadUrl("file:///android_asset/index.html");
        setContentView(webView);
    }

    private boolean hasAudio() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_AUDIO && pendingAudio != null) {
            if (hasAudio()) {
                pendingAudio.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingAudio.deny();
                Toast.makeText(this, "Microphone permission is required to record", Toast.LENGTH_LONG).show();
            }
            pendingAudio = null;
        }
    }

    private void startSession() {
        Intent i = new Intent(this, SleepRecordService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        if (!batteryPrompted) {
            batteryPrompted = true;
            try {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    Intent b = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:" + getPackageName()));
                    startActivity(b);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private String saveToDownloads(String name, String content) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "text/csv");
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri != null) {
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    if (out != null) {
                        out.write(content.getBytes(StandardCharsets.UTF_8));
                        out.close();
                        return "Saved to Downloads/" + name;
                    }
                }
            }
            File dir = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(content.getBytes(StandardCharsets.UTF_8));
            fos.close();
            MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, null, null);
            return "Saved to " + f.getAbsolutePath();
        } catch (Exception e) {
            return "Could not save file: " + e.getMessage();
        }
    }

    private class Bridge {
        @JavascriptInterface
        public void startSession() {
            runOnUiThread(() -> MainActivity.this.startSession());
        }

        @JavascriptInterface
        public void stopSession() {
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, SleepRecordService.class)));
        }

        @JavascriptInterface
        public void saveText(String name, String content) {
            final String msg = saveToDownloads(name, content);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
