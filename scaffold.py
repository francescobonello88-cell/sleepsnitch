import os, io

ROOT = os.path.dirname(os.path.abspath(__file__))
os.chdir(ROOT)

def w(path, content):
    d = os.path.dirname(path)
    if d:
        os.makedirs(d, exist_ok=True)
    open(path, 'w', encoding='utf-8').write(content)
    print('wrote', path)

# ---------------- gradle ----------------
w('settings.gradle.kts', """pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
}
rootProject.name = "sleepsnitch"
include(":app")
""")

w('build.gradle.kts', """plugins { id("com.android.application") version "8.5.2" apply false }
""")

w('gradle.properties', """org.gradle.jvmargs=-Xmx3g
android.useAndroidX=false
""")

w('app/build.gradle.kts', """plugins { id("com.android.application") }

android {
    namespace = "mt.sleepsnitch"
    compileSdk = 34

    defaultConfig {
        applicationId = "mt.sleepsnitch"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
""")

# ---------------- manifest ----------------
w('app/src/main/AndroidManifest.xml', """<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
    <uses-permission android:name="android.permission.INTERNET" />

    <uses-feature android:name="android.hardware.microphone" android:required="true" />

    <application
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="SleepSnitch"
        android:theme="@android:style/Theme.Material.NoActionBar"
        android:usesCleartextTraffic="false">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:configChanges="orientation|screenSize|keyboardHidden"
            android:launchMode="singleTop">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".SleepRecordService"
            android:exported="false"
            android:foregroundServiceType="microphone" />
    </application>
</manifest>
""")

# ---------------- MainActivity ----------------
w('app/src/main/java/mt/sleepsnitch/MainActivity.java', """package mt.sleepsnitch;

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
""")

# ---------------- service ----------------
w('app/src/main/java/mt/sleepsnitch/SleepRecordService.java', """package mt.sleepsnitch;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

public class SleepRecordService extends Service {

    private static final String CHANNEL = "sleepsnitch_recording";
    private PowerManager.WakeLock wakeLock;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();
        acquireWakeLock();
        return START_STICKY;
    }

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Sleep recording", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            ch.setSound(null, null);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(ch);
        }

        PendingIntent pi = PendingIntent.getActivity(
                this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder nb = (Build.VERSION.SDK_INT >= 26)
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);

        Notification n = nb
                .setContentTitle("SleepSnitch is listening")
                .setContentText("Recording in the background \\u2014 screen can stay off")
                .setSmallIcon(R.drawable.ic_moon)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pi)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            } catch (Exception e) {
                startForeground(1, n);
            }
        } else {
            startForeground(1, n);
        }
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepSnitch:Recording");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    @Override
    public void onDestroy() {
        releaseWakeLock();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
""")

# ---------------- icon ----------------
w('app/src/main/res/drawable/ic_moon.xml', """<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="#9B8CFF"
        android:pathData="M21,12.8A9,9 0 1,1 11.2,3 A7,7 0 0,0 21,12.8 Z" />
</vector>
""")

# ---------------- workflow ----------------
w('.github/workflows/build.yml', """name: Build SleepSnitch APK

on:
  push:
    branches: [main]
  workflow_dispatch:

permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: '8.7'
      - name: Build debug APK
        run: gradle assembleDebug --no-daemon
      - name: Package
        run: cp app/build/outputs/apk/debug/app-debug.apk SleepSnitch.apk
      - name: Publish to GitHub Releases
        uses: softprops/action-gh-release@v2
        with:
          tag_name: v1-latest
          name: "SleepSnitch (latest build)"
          files: SleepSnitch.apk
""")

print('scaffold done')
