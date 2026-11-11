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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    // The page is served from a real https origin. file:// is NOT a secure context,
    // so navigator.mediaDevices is undefined there and getUserMedia can never work.
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START_URL = "https://" + HOST + "/assets/index.html";

    private static final int REQ_PERMS = 21;
    private WebView webView;
    private PermissionRequest pendingAudio;
    private boolean batteryPrompted = false;
    private boolean permissionAskedFromPage = false;
    private static volatile boolean sessionRunning = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window w = getWindow();
        w.setStatusBarColor(0xFF08080A);
        w.setNavigationBarColor(0xFF08080A);
        if (Build.VERSION.SDK_INT >= 23) {
            w.getDecorView().setSystemUiVisibility(0);
        }

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        webView.setBackgroundColor(0xFF08080A);

        // Serve bundled assets over https://appassets.androidplatform.net/assets/...
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (u != null && HOST.equals(u.getHost()) && u.getPath() != null
                        && u.getPath().startsWith("/assets/")) {
                    String name = u.getPath().substring("/assets/".length());
                    try {
                        InputStream in = getAssets().open(name);
                        return new WebResourceResponse(mimeFor(name), "utf-8", in);
                    } catch (Exception e) {
                        return new WebResourceResponse("text/plain", "utf-8", 404, "Not found", null, null);
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
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
                        askRuntimePermissions();
                    }
                });
            }
        });

        webView.addJavascriptInterface(new Bridge(), "SnitchBridge");
        webView.loadUrl(START_URL);
        setContentView(webView);

        // Ask for the microphone (and notifications) immediately, so the very first
        // tap on "Start listening" already has permission.
        if (!hasAudio()) askRuntimePermissions();
    }

    private static String mimeFor(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".svg")) return "image/svg+xml";
        if (n.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private boolean hasAudio() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void askRuntimePermissions() {
        List<String> need = new ArrayList<>();
        if (!hasAudio()) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERMS) return;
        final boolean granted = hasAudio();

        if (pendingAudio != null) {
            if (granted) pendingAudio.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else pendingAudio.deny();
            pendingAudio = null;
        }
        if (permissionAskedFromPage) {
            permissionAskedFromPage = false;
            final String js = "window.__snitchMicResult && window.__snitchMicResult(" + granted + ")";
            webView.post(() -> webView.evaluateJavascript(js, null));
        }
        if (!granted) {
            Toast.makeText(this, "Microphone permission is required to record", Toast.LENGTH_LONG).show();
        }
    }

    private void startSession() {
        // Android 14+: a microphone foreground service may only start once RECORD_AUDIO is granted.
        if (!hasAudio()) return;
        sessionRunning = true;
        Intent i = new Intent(this, SleepRecordService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
        } catch (Exception e) {
            Toast.makeText(this, "Could not start background service: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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
            File dir = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir != null && !dir.exists()) dir.mkdirs();
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
        public boolean hasMic() {
            return hasAudio();
        }

        @JavascriptInterface
        public void askMic() {
            runOnUiThread(() -> {
                permissionAskedFromPage = true;
                if (hasAudio()) {
                    permissionAskedFromPage = false;
                    webView.evaluateJavascript("window.__snitchMicResult && window.__snitchMicResult(true)", null);
                } else {
                    askRuntimePermissions();
                }
            });
        }

        @JavascriptInterface
        public void startSession() {
            runOnUiThread(() -> MainActivity.this.startSession());
        }

        @JavascriptInterface
        public void stopSession() {
            sessionRunning = false;
            runOnUiThread(() -> stopService(new Intent(MainActivity.this, SleepRecordService.class)));
        }

        @JavascriptInterface
        public void saveText(String name, String content) {
            final String msg = saveToDownloads(name, content);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
        }
    }


    @Override
    protected void onPause() {
        super.onPause();
        // While a session runs, keep the WebView (and its audio pipeline) alive in the background.
        if (webView != null && sessionRunning) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (webView != null && sessionRunning) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
