package com.dalsy.phonemidi;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.InputStream;

/**
 * The hand-tracking instrument (Omnichord, Hexágonos, Mano Pro…): the same page as the computer
 * version, running inside the app. Everything is bundled in the APK (page, MediaPipe, model)
 * and served from a private https origin, so it works offline. Web MIDI is replaced by a bridge
 * to the app's MIDI connections (FM-1 over Bluetooth/USB, and Ableton via Bluetooth MIDI).
 */
public class ManosActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String BASE = "https://" + HOST + "/assets/";

    private WebView web;
    private Core core;
    private PermissionRequest pendingCam;
    private final Handler main = new Handler(Looper.getMainLooper());

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        core = Core.get(this);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0E12);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "PhoneMIDI");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost()) || u.getPath() == null || !u.getPath().startsWith("/assets/")) {
                    return null;
                }
                String path = u.getPath().substring("/assets/".length());
                try {
                    InputStream in = getAssets().open(path);
                    WebResourceResponse r = new WebResourceResponse(mime(path), mime(path).startsWith("text") ? "utf-8" : null, in);
                    java.util.Map<String, String> h = new java.util.HashMap<>();
                    h.put("Access-Control-Allow-Origin", "*");
                    h.put("Cache-Control", "no-cache");
                    r.setResponseHeaders(h);
                    return r;
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "utf-8", 404, "Not found",
                            null, new java.io.ByteArrayInputStream(new byte[0]));
                }
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest req) {
                main.post(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        req.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                    } else {
                        pendingCam = req;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, 7);
                    }
                });
            }
        });
        setContentView(web);
        web.loadUrl(BASE + "manos.html?app=1&mp=" + BASE + "mp&model=" + BASE + "hand_landmarker.task");
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "text/javascript";
        if (p.endsWith(".wasm")) return "application/wasm";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (pendingCam != null) {
            if (res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) {
                pendingCam.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
            } else {
                pendingCam.deny();
            }
            pendingCam = null;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onPause() {
        // silence everything before the page is paused
        web.evaluateJavascript("window.__pm && window.__pm.allOff && window.__pm.allOff()", null);
        for (int ch = 0; ch < 16; ch++) {
            core.hub.send(new byte[]{(byte) (0xB0 | ch), 123, 0});
        }
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }

    /** Called from the page instead of Web MIDI. */
    private class Bridge {
        @JavascriptInterface
        public void send(String csv, double delayMs) {
            String[] parts = csv.split(",");
            final byte[] msg = new byte[parts.length];
            try {
                for (int i = 0; i < parts.length; i++) msg[i] = (byte) Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return;
            }
            long at = SystemClock.uptimeMillis() + Math.max(0L, Math.round(delayMs));
            main.postAtTime(() -> core.hub.send(msg), at);
        }

        @JavascriptInterface
        public String outputName() {
            StringBuilder sb = new StringBuilder();
            for (MidiHub.Out o : core.hub.outs) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(o.name);
            }
            if (core.hub.ble.running) {
                if (sb.length() > 0) sb.append(" + ");
                sb.append("Bluetooth MIDI (Ableton)");
            }
            return sb.toString();
        }
    }
}
